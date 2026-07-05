package com.edlflash.buildtools;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * 控制流混淆（Radon 式：不透明谓词 + 伪边 + 伪 switch 散转），在 JVM 字节码层做。
 * 不做"真平坦化"(把真实块搬进 dispatcher)——那对 RN 复杂方法风险过高；改用恒假谓词驱动的
 * 死分支/伪散转破坏线性反编译与 CFG，配合 EdlsecAsmFactory 的逐方法验证+失败回退保证不崩。
 *
 * 安全铁律(全部经 ASM/JVM 校验器约束)：
 *  - 谓词源用运行期不可折叠的 EdlState.x((x*x-x)%2 恒为 0)，R8 无法常量折叠删分支；
 *  - 伪边只在"操作数栈为空"点插入(Analyzer<BasicValue> 求得)，避免帧不一致 VerifyError；
 *  - GOTO 改写保持栈高不变(ILOAD pred;IFEQ 净效果与 GOTO 相同)，死边自封闭(ACONST_NULL;ATHROW)；
 *  - 跳过 <init>/<clinit>/被排除方法；变换后由调用方跑 Analyzer 自检，失败整体回退原方法体。
 */
final class FlowObfuscator {
  private FlowObfuscator() {}

  private static final String STATE_OWNER = "com/edlflash/edl/EdlState";

  /**
   * 对 mn 原地施加控制流混淆。pred 槽与 fakeExit 块按需创建。
   * 仅当方法可被 Analyzer 成功分析(栈空点可求)才变换；任何异常向上抛由调用方回退。
   * 返回是否实际改动。
   */
  static boolean transform(String owner, MethodNode mn, int seed) throws Exception {
    // 入口先求一次帧：分析不过(JSR/RET、畸形)直接放弃，交调用方回退。
    Frame<BasicValue>[] frames = analyze(owner, mn);

    int predSlot = mn.maxLocals;
    mn.maxLocals += 1;

    insertPredicateSetup(mn, predSlot);

    boolean changed = false;
    changed |= replaceGotos(mn, predSlot);

    // GOTO 改写后指令变了，重新求帧拿最新栈空点。
    frames = analyze(owner, mn);
    changed |= insertBogusJumps(mn, predSlot, frames, seed);

    frames = analyze(owner, mn);
    changed |= insertBogusSwitch(mn, predSlot, frames, seed);

    return changed;
  }

  private static Frame<BasicValue>[] analyze(String owner, MethodNode mn) throws Exception {
    return new Analyzer<>(new BasicInterpreter()).analyze(owner, mn);
  }

  // 在方法第一条真实指令前算出 pred=(x*x-x)%2(恒 0) 存入 predSlot。
  // 仅对非 <init>/<clinit> 调用(调用方已门控)，故首指令前插入安全。
  private static void insertPredicateSetup(MethodNode mn, int predSlot) {
    InsnList pre = new InsnList();
    pre.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, STATE_OWNER, "x", "I"));
    pre.add(new InsnNode(Opcodes.DUP));
    pre.add(new InsnNode(Opcodes.DUP));
    pre.add(new InsnNode(Opcodes.IMUL));   // [x, x*x]
    pre.add(new InsnNode(Opcodes.ISUB));   // [x - x*x]  (仍恒偶)
    pre.add(new InsnNode(Opcodes.ICONST_2));
    pre.add(new InsnNode(Opcodes.IREM));   // (x - x*x) % 2 == 0
    pre.add(new VarInsnNode(Opcodes.ISTORE, predSlot));
    mn.instructions.insert(pre);
  }

  // 每个无条件 GOTO L → ILOAD pred; IFEQ L(pred==0 恒跳) + 死代码 ACONST_NULL; ATHROW。
  // 栈高与 GOTO 完全一致；死边自封闭，不会带错误栈汇入真实代码。
  private static boolean replaceGotos(MethodNode mn, int predSlot) {
    boolean changed = false;
    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
      AbstractInsnNode next = insn.getNext();
      if (insn.getOpcode() == Opcodes.GOTO) {
        LabelNode target = ((JumpInsnNode) insn).label;
        InsnList rep = new InsnList();
        rep.add(new VarInsnNode(Opcodes.ILOAD, predSlot));
        rep.add(new JumpInsnNode(Opcodes.IFEQ, target));
        rep.add(new InsnNode(Opcodes.ACONST_NULL));
        rep.add(new InsnNode(Opcodes.ATHROW));
        mn.instructions.insertBefore(insn, rep);
        mn.instructions.remove(insn);
        changed = true;
      }
      insn = next;
    }
    return changed;
  }

  // 在若干"栈空"点插 ILOAD pred; IFNE fakeExit(pred==0 恒不跳)，制造永不执行的伪边。
  // fakeExit 是按返回类型生成的假返回块，只建一次、被所有伪边复用。
  private static boolean insertBogusJumps(MethodNode mn, int predSlot,
                                          Frame<BasicValue>[] frames, int seed) {
    AbstractInsnNode[] insns = mn.instructions.toArray();
    LabelNode fakeExit = null;
    int placed = 0;
    int step = 3 + (Math.abs(seed) % 4);  // 每隔几个栈空点插一个，控制膨胀
    int count = 0;
    for (int i = 0; i < insns.length; i++) {
      Frame<BasicValue> f = frames[i];
      if (f == null || f.getStackSize() != 0) continue;
      AbstractInsnNode insn = insns[i];
      // 跳过标签/帧/行号等伪指令本身之前不插；只在真实指令前且非方法首条插。
      if (insn.getType() == AbstractInsnNode.LABEL
          || insn.getType() == AbstractInsnNode.FRAME
          || insn.getType() == AbstractInsnNode.LINE) continue;
      if (insn == mn.instructions.getFirst()) continue;
      if ((count++ % step) != 0) continue;
      if (fakeExit == null) fakeExit = appendFakeExit(mn);
      InsnList j = new InsnList();
      j.add(new VarInsnNode(Opcodes.ILOAD, predSlot));
      j.add(new JumpInsnNode(Opcodes.IFNE, fakeExit));
      mn.instructions.insertBefore(insn, j);
      placed++;
    }
    return placed > 0;
  }

  // 一个由 pred(=0) 驱动的伪 TABLESWITCH 散转：default 落回真实续点，cases 指向互相 GOTO 的死
  // landing pad，制造"控制流平坦化/散转"观感而不触碰真实逻辑。仅在方法体内第一个栈空点插一次。
  private static boolean insertBogusSwitch(MethodNode mn, int predSlot,
                                           Frame<BasicValue>[] frames, int seed) {
    AbstractInsnNode[] insns = mn.instructions.toArray();
    int at = -1;
    for (int i = 1; i < insns.length; i++) {
      Frame<BasicValue> f = frames[i];
      if (f == null || f.getStackSize() != 0) continue;
      AbstractInsnNode insn = insns[i];
      if (insn.getType() == AbstractInsnNode.LABEL
          || insn.getType() == AbstractInsnNode.FRAME
          || insn.getType() == AbstractInsnNode.LINE) continue;
      at = i;
      break;
    }
    if (at < 0) return false;
    AbstractInsnNode anchor = insns[at];

    int pads = 3 + (Math.abs(seed) % 3);  // 3~5 个 landing pad
    LabelNode realCont = new LabelNode();
    LabelNode[] padLabels = new LabelNode[pads];
    for (int i = 0; i < pads; i++) padLabels[i] = new LabelNode();

    InsnList sw = new InsnList();
    sw.add(new VarInsnNode(Opcodes.ILOAD, predSlot));        // 恒 0
    // tableswitch 0..pads-1, default=realCont, case0=realCont(pred==0 必走), 其余=死 pad
    LabelNode[] cases = new LabelNode[pads];
    cases[0] = realCont;
    for (int i = 1; i < pads; i++) cases[i] = padLabels[i];
    sw.add(new TableSwitchInsnNode(0, pads - 1, realCont, cases));
    // 死 landing pad：栈空进入，做点无害 junk 再 GOTO 下一个 pad，最后回 realCont（永不执行）。
    for (int i = 1; i < pads; i++) {
      sw.add(padLabels[i]);
      sw.add(new VarInsnNode(Opcodes.ILOAD, predSlot));
      sw.add(new InsnNode(Opcodes.POP));
      LabelNode nextPad = (i + 1 < pads) ? padLabels[i + 1] : realCont;
      sw.add(new JumpInsnNode(Opcodes.GOTO, nextPad));
    }
    sw.add(realCont);  // 真实续点：原 anchor 及之后照常执行
    mn.instructions.insertBefore(anchor, sw);
    return true;
  }

  // 追加一个按返回类型返回默认值的假出口块；伪边 IFNE 永不跳到它。入口栈空。
  private static LabelNode appendFakeExit(MethodNode mn) {
    LabelNode l = new LabelNode();
    InsnList ex = new InsnList();
    ex.add(l);
    Type ret = Type.getReturnType(mn.desc);
    switch (ret.getSort()) {
      case Type.VOID:
        ex.add(new InsnNode(Opcodes.RETURN));
        break;
      case Type.BOOLEAN: case Type.CHAR: case Type.BYTE:
      case Type.SHORT: case Type.INT:
        ex.add(new InsnNode(Opcodes.ICONST_0));
        ex.add(new InsnNode(Opcodes.IRETURN));
        break;
      case Type.LONG:
        ex.add(new InsnNode(Opcodes.LCONST_0));
        ex.add(new InsnNode(Opcodes.LRETURN));
        break;
      case Type.FLOAT:
        ex.add(new InsnNode(Opcodes.FCONST_0));
        ex.add(new InsnNode(Opcodes.FRETURN));
        break;
      case Type.DOUBLE:
        ex.add(new InsnNode(Opcodes.DCONST_0));
        ex.add(new InsnNode(Opcodes.DRETURN));
        break;
      default:  // 对象/数组
        ex.add(new InsnNode(Opcodes.ACONST_NULL));
        ex.add(new InsnNode(Opcodes.ARETURN));
        break;
    }
    mn.instructions.add(ex);
    return l;
  }

  // —— 字符串加密(tree 模式)：把 String LDC 替换为密文 + StringVault.d 调用。 ——
  // 与原流式实现等价，挪到 tree 管线统一处理。返回是否改动。
  static boolean encryptStrings(MethodNode mn, int seedBase) {
    boolean changed = false;
    int counter = 0;
    // 必须先取 next 再 remove：remove(insn) 后 insn.getNext() 为 null，
    // 用 for(;insn=insn.getNext();) 会在删掉第一个 String LDC 后提前终止循环，
    // 导致每个方法只加密第一个字符串（"一大堆没被混淆"的根因）。
    AbstractInsnNode insn = mn.instructions.getFirst();
    while (insn != null) {
      AbstractInsnNode next = insn.getNext();
      if (insn instanceof LdcInsnNode) {
        Object cst = ((LdcInsnNode) insn).cst;
        if (cst instanceof String && !((String) cst).isEmpty()) {
          String enc = EdlStrings.encrypt((String) cst, seedBase + (counter++));
          InsnList rep = new InsnList();
          rep.add(new LdcInsnNode(enc));
          // dc 返回 char[]，再 String.valueOf 还原。char[] 越出 MT 解密器自动识别的返回类型
          // 白名单(String/Object/CharSequence)，MT 无法一键自动定位 dc；String.valueOf 是 JDK
          // 方法，MT 不会当作本应用解密器。双重破坏自动识别。
          rep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/edlflash/edl/StringVault",
              "dc", "(Ljava/lang/String;)[C", false));
          rep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String",
              "valueOf", "([C)Ljava/lang/String;", false));
          mn.instructions.insertBefore(insn, rep);
          mn.instructions.remove(insn);
          changed = true;
        }
      }
      insn = next;
    }
    return changed;
  }
}
