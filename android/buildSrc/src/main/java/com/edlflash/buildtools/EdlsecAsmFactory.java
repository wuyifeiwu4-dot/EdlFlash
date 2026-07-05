package com.edlflash.buildtools;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AGP 编译期字节码插桩，只对 com.edlflash.edl.** 做：
 *  1) 字符串常量加密——LDC String 换成密文 + 运行期 StringVault.d 解密（密钥下沉 native，
 *     MT 管理器静态解释器无法还原）。对所有方法施加，安全且已验证。
 *  2) 控制流混淆——不透明谓词驱动的 GOTO 改写 + 伪边 + 伪 switch 散转（见 FlowObfuscator），
 *     破坏线性反编译与 CFG。仅对"安全子集"方法施加，且每方法变换后跑 ASM 校验，失败即整体
 *     回退到"仅字符串加密"版本——把"流混淆写错=运行崩"降级为"该方法没被流混淆"。
 *
 * 改造为 tree-API(MethodNode-as-adapter)：流混淆需 StackHeightZeroFinder 这类全方法回看分析，
 * 流式 MethodVisitor 做不到。帧由 AGP 的 COMPUTE_FRAMES 重算（见 app/build.gradle 注册处）。
 */
public abstract class EdlsecAsmFactory
    implements AsmClassVisitorFactory<InstrumentationParameters.None> {

  // 跨类统计流混淆回退方法数，便于发现栈分析/变换 bug（回退率异常高=有问题）。
  static final AtomicInteger FLOW_APPLIED = new AtomicInteger();
  static final AtomicInteger FLOW_FALLBACK = new AtomicInteger();

  @Override
  public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor next) {
    String cn = classContext.getCurrentClassData().getClassName();
    return new EdlClassVisitor(next, cn.replace('.', '/'), cn.hashCode());
  }

  @Override
  public boolean isInstrumentable(ClassData classData) {
    String n = classData.getClassName();
    // 全 dex 字符串加密(用户要求"全dex都要混淆")：本模块 + 所有依赖(RN/AndroidX/Kotlin/三方)。
    // 仅排除解密器自身与其取密钥链路上的类，否则解密会成环/无限递归：
    //  - StringVault/EdlState/SecurityCore：解密器、谓词源、native 桥；
    //  - kotlin.jvm.internal.Intrinsics：SecurityCore(Kotlin)取 key 时高频调用，排除以缩小重入面
    //    (StringVault.key() 另有重入守卫兜底)。
    if (n.equals("com.edlflash.edl.StringVault")
        || n.equals("com.edlflash.edl.EdlState")
        || n.equals("com.edlflash.edl.SecurityCore")
        || n.equals("kotlin.jvm.internal.Intrinsics")) {
      return false;
    }
    return true;
  }
}

class EdlClassVisitor extends ClassVisitor {
  private final String owner;   // internal name, e.g. com/edlflash/edl/FlashEngine
  private final int seed;

  EdlClassVisitor(ClassVisitor cv, String owner, int seed) {
    super(Opcodes.ASM9, cv);
    this.owner = owner;
    this.seed = seed;
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String desc,
                                   String sig, String[] exc) {
    final MethodVisitor downstream = super.visitMethod(access, name, desc, sig, exc);
    if (downstream == null) return null;
    if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return downstream;

    final int methodSeed = seed ^ (name + desc).hashCode();
    // 缓冲为 MethodNode，在 visitEnd 做全方法分析+变换，再回放给下游。
    return new MethodNode(Opcodes.ASM9, access, name, desc, sig, exc) {
      @Override
      public void visitEnd() {
        super.visitEnd();
        process(this, downstream, methodSeed);
      }
    };
  }

  private void process(MethodNode mn, MethodVisitor downstream, int methodSeed) {
    // 1) 字符串加密：所有方法都做，安全且已验证。
    try {
      FlowObfuscator.encryptStrings(mn, methodSeed);
    } catch (Throwable ignore) {
      // 极不应发生；即便失败也只是该方法没加密字符串，继续。
    }

    // 2) 控制流混淆：仅对本应用 com.edlflash.** 代码(框架类 flow 开销大且易崩，只做字符串加密)；
    //    且变换后校验，失败回退原（仅字符串加密）方法体。
    if (owner.startsWith("com/edlflash/") && flowEligible(mn)) {
      MethodNode copy = deepCopy(mn);
      boolean ok = false;
      try {
        FlowObfuscator.transform(owner, copy, methodSeed);
        // 构建期自检：栈/类型不一致即抛 AnalyzerException → 回退，杜绝运行期 VerifyError。
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(owner, copy);
        ok = true;
      } catch (Throwable t) {
        ok = false;
      }
      if (ok) {
        EdlsecAsmFactory.FLOW_APPLIED.incrementAndGet();
        copy.accept(downstream);
        return;
      }
      EdlsecAsmFactory.FLOW_FALLBACK.incrementAndGet();
    }
    mn.accept(downstream);
  }

  private static MethodNode deepCopy(MethodNode mn) {
    String[] exc = mn.exceptions == null ? null : mn.exceptions.toArray(new String[0]);
    MethodNode c = new MethodNode(Opcodes.ASM9, mn.access, mn.name, mn.desc, mn.signature, exc);
    mn.accept(c);
    return c;
  }

  // 流混淆安全门控。注意：flow 只在方法体内部插不透明谓词死边，不改方法签名/注解，故
  //  - @ReactMethod：RN 按名/注解反射派发，方法体内部混淆不影响派发 → 允许(卡密 verify 等核心要混)；
  //  - Kotlin $lambda$：是承载真实业务逻辑的普通静态方法(卡密 HTTP 都在 lambda 里) → 允许；
  //    仅排除桥接(ACC_BRIDGE)与 access$ 合成访问器(极小、改写无益)。
  // 任何不安全的方法由调用方的 BasicVerifier 自检失败回退原方法体兜底，不会崩。
  private static boolean flowEligible(MethodNode mn) {
    if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0)
      return false;
    if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) return false;
    if (mn.name.startsWith("access$")) return false;
    // 合成方法里只放过 lambda(真实逻辑)，其余合成(访问器/默认参数桩等)跳过。
    if ((mn.access & Opcodes.ACC_SYNTHETIC) != 0 && !mn.name.contains("lambda")) return false;
    if (mn.instructions == null || mn.instructions.size() == 0) return false;
    // 超大方法施加 flow 易把字节码推过 64KB 方法上限(且收益边际)，保守跳过。
    if (mn.instructions.size() > 12000) return false;

    // Kotlin suspend：末位参数为 Continuation，flow 会破坏协程状态机，跳过。
    Type[] args = Type.getArgumentTypes(mn.desc);
    if (args.length > 0
        && "kotlin/coroutines/Continuation".equals(args[args.length - 1].getInternalName())) {
      return false;
    }
    return true;
  }
}

/**
 * 构建期字符串加密：与运行期 StringVault.d 逐字节互逆（同为 Java，算法天然一致）。
 * keystream 由 64bit 密钥 K 派生，K 与 native edlsec.cpp do_string_key 基值逐位一致
 * (0xA53C7E11B96D2F48)，运行期经签名绑定异或抵消后还原；K 不进 DEX。Latin-1 安全 char 承载密文。
 */
class EdlStrings {
  private static final long K = 0xA53C7E11B96D2F48L;

  static String encrypt(String s, int key) {
    byte[] u = s.getBytes(StandardCharsets.UTF_8);
    int n = u.length;
    int nonce0 = (key * 0x9E3779B1) & 0xFF;
    int nonce1 = ((key * 0x85EBCA77) >>> 11) & 0xFF;
    if (nonce0 == 0) nonce0 = 0x5A;
    if (nonce1 == 0) nonce1 = 0xA5;
    long state = K ^ (((long) nonce0 << 8) | nonce1) ^ ((long) n * 0x9E3779B97F4A7C15L);
    char[] c = new char[n + 2];
    c[0] = (char) nonce0;
    c[1] = (char) nonce1;
    for (int i = 0; i < n; i++) {
      state = state * 6364136223846793005L + 1442695040888963407L;
      int ks = (int) (((state >>> 40) ^ (state >>> 17) ^ ((long) i * 0x9DL)) & 0xFFL);
      c[i + 2] = (char) ((u[i] ^ ks) & 0xFF);
    }
    return new String(c);
  }
}
