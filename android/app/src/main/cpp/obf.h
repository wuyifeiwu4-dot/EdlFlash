/*
 * 混淆基元（仅头文件）：
 *  - OBF("..."): 编译期把字面量按每个调用点不同的密钥流异或，只有密文进 .rodata；
 *    运行期解密到栈缓冲，析构时擦除。逆向静态扫不到明文字符串。
 *  - opaque_true/opaque_false: 不透明谓词，值在编译期不可折叠，用来制造迷惑分支。
 *  - mix_add/mix_xor: 轻量 MBA，把简单算术拆成等价但不直观的形式。
 * 关键逻辑的控制流扁平化采用手写状态机（见 vm.c / gate），不用宏堆。
 */
#ifndef EDLSEC_OBF_H
#define EDLSEC_OBF_H

#include <stddef.h>
#include <stdint.h>

namespace edlsec {

constexpr uint32_t obf_step(uint32_t k) { return k * 1664525u + 1013904223u; }

template <size_t N>
struct cipher_str {
  char data[N];
  uint32_t seed;
};

// 编译期加密：literal 只在 constexpr 上下文出现，不会以明文落入产物。
template <size_t N>
constexpr cipher_str<N> obf_encrypt(const char (&s)[N], uint32_t seed) {
  cipher_str<N> out{};
  out.seed = seed;
  uint32_t k = seed;
  for (size_t i = 0; i < N; i++) {
    k = obf_step(k);
    out.data[i] = (char)((uint8_t)s[i] ^ (uint8_t)(k >> 24));
  }
  return out;
}

// 运行期持有者：构造即解密到栈，析构按 volatile 擦除，防 dump。
template <size_t N>
struct obf_guard {
  char buf[N];
  explicit obf_guard(const cipher_str<N> &c) {
    uint32_t k = c.seed;
    for (size_t i = 0; i < N; i++) {
      k = obf_step(k);
      buf[i] = (char)((uint8_t)c.data[i] ^ (uint8_t)(k >> 24));
    }
  }
  ~obf_guard() {
    volatile char *p = buf;
    for (size_t i = 0; i < N; i++) p[i] = 0;
  }
  const char *c_str() const { return buf; }
  size_t size() const { return N - 1; }  // 去掉结尾 NUL
};

}  // namespace edlsec

// 每个调用点用 __LINE__ 派生独立种子，避免相同字符串产生相同密文。
#define OBF(literal)                                                        \
  edlsec::obf_guard<sizeof(literal)>([] {                                   \
    constexpr auto c = edlsec::obf_encrypt(                                 \
        literal, (uint32_t)(0x9e3779b9u ^ ((uint32_t)__LINE__ * 2654435761u))); \
    return c;                                                               \
  }())

// 运行期不可折叠的熵源（在 edlsec.c 里定义并随启动扰动）。
extern "C" volatile uint32_t edlsec_entropy;

// 恒真/恒假不透明谓词：x*(x-1) 必为偶数，编译器无法据 volatile 折叠。
static inline int opaque_true() {
  volatile uint32_t x = edlsec_entropy | 1u;
  return (int)(((x * (x - 1u)) & 1u) == 0u);
}
static inline int opaque_false() { return !opaque_true(); }

// 轻量 MBA：等价于 a+b / a^b，但展开成更绕的位运算组合。
static inline uint32_t mix_add(uint32_t a, uint32_t b) {
  return (a ^ b) + ((a & b) << 1);
}
static inline uint32_t mix_xor(uint32_t a, uint32_t b) {
  return (a | b) - (a & b);
}

#define EDLSEC_HIDDEN __attribute__((visibility("hidden")))
#define EDLSEC_NOINLINE __attribute__((noinline))

#endif
