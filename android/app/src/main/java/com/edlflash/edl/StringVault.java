package com.edlflash.edl;

import java.nio.charset.StandardCharsets;

/**
 * 运行期字符串解密器（与 buildSrc EdlStrings.encrypt 逐字节互逆）。
 *
 * 反 MT 一键解密的关键：解密方法返回 char[]（不是 String）。MT 管理器"dex字符串解密"的
 * 自动识别只接受返回 String/Object/CharSequence 的方法，char[] 越出其白名单 → MT 无法自动
 * 定位本方法、无法一键批量还原。调用点由 ASM 注入 String.valueOf(char[]) 还原成字符串。
 *
 * 叠加：密钥下沉 native(stringKey→libedlsec)，MT 解释器执行不了 .so → 即便手工指定也跑不出明文；
 * 密钥与签名证书异或抵消绑定，重打包/异己环境得错 key→乱码。本类被 ASM 排除(避免成环)。
 */
public final class StringVault {
  private StringVault() {}

  private static long k;
  private static volatile boolean ready;
  private static boolean computing;

  private static long key() {
    if (ready) return k;
    // 全 dex 插桩后，取 key 链路(SecurityCore→kotlin stdlib)里若有被加密的字符串会回调本方法，
    // 在 ready 置位前形成重入→无限递归。重入时返回 0(那一个 stdlib 异常消息串得乱码，无害)，
    // 待首次 native 取到真 key 并 ready 后，其余字符串全部正常解密。
    if (computing) return 0L;
    computing = true;
    long v;
    try {
      v = SecurityCore.stringKey();
    } catch (Throwable t) {
      v = 0L;
    }
    k = v;
    ready = true;
    computing = false;
    return k;
  }

  /** 解密为 char[]（故意不返回 String，破坏 MT 的解密器自动识别签名白名单）。 */
  public static char[] dc(String e) {
    int n = e.length() - 2;
    if (n < 0) return e.toCharArray();
    int nonce0 = e.charAt(0) & 0xFF;
    int nonce1 = e.charAt(1) & 0xFF;
    long state = key() ^ (((long) nonce0 << 8) | nonce1) ^ ((long) n * 0x9E3779B97F4A7C15L);
    byte[] u = new byte[n];
    for (int i = 0; i < n; i++) {
      state = state * 6364136223846793005L + 1442695040888963407L;
      int ks = (int) (((state >>> 40) ^ (state >>> 17) ^ ((long) i * 0x9DL)) & 0xFFL);
      u[i] = (byte) ((e.charAt(i + 2) & 0xFF) ^ ks);
    }
    return new String(u, StandardCharsets.UTF_8).toCharArray();
  }
}
