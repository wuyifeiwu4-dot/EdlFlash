package com.edlflash.edl;

/**
 * 不透明谓词用的运行期熵源。x 取自运行期不可在编译/反编译期确定的值，
 * 使 (x*x-x)%2==0 这类恒真谓词无法被静态求值消除，bogus 分支得以保留。
 * 本类被 ASM 排除、被 ProGuard keep。
 */
public final class EdlState {
  public static int x;

  static {
    int v;
    try {
      v = (int) (Runtime.getRuntime().freeMemory() & 0x7fff) | 1;
    } catch (Throwable t) {
      v = 0x4d2;
    }
    x = v;
  }

  private EdlState() {}
}
