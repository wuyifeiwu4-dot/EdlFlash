#include "edlcipher.h"
#include <string.h>

static uint32_t rotl32(uint32_t x, int n) { return (x << n) | (x >> (32 - n)); }
static uint64_t rotl64(uint64_t x, int n) { return (x << n) | (x >> (64 - n)); }

static uint32_t ld32(const uint8_t *p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
         ((uint32_t)p[3] << 24);
}
static void st32(uint8_t *p, uint32_t v) {
  p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8);
  p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}
static uint64_t ld64(const uint8_t *p) {
  uint64_t v = 0;
  for (int i = 0; i < 8; i++) v |= (uint64_t)p[i] << (8 * i);
  return v;
}

/* 自定义 quarter-round（轮转量 15/11/8/7，区别于标准 ChaCha 的 16/12/8/7）。 */
#define QR(s, a, b, c, d)                          \
  do {                                             \
    s[a] += s[b]; s[d] ^= s[a]; s[d] = rotl32(s[d], 15); \
    s[c] += s[d]; s[b] ^= s[c]; s[b] = rotl32(s[b], 11); \
    s[a] += s[b]; s[d] ^= s[a]; s[d] = rotl32(s[d], 8);  \
    s[c] += s[d]; s[b] ^= s[c]; s[b] = rotl32(s[b], 7);  \
  } while (0)

void edl_block(const uint8_t key[32], const uint8_t nonce[12],
               uint32_t counter, uint8_t out[64]) {
  /* 自定义常量（"edlf" "lash" "v2x0" + 黄金比） */
  uint32_t st[16];
  st[0] = 0x65646c66u; st[1] = 0x6c617368u;
  st[2] = 0x76327830u; st[3] = 0x9e3779b9u;
  for (int i = 0; i < 8; i++) st[4 + i] = ld32(key + i * 4);
  st[12] = counter;
  st[13] = ld32(nonce + 0);
  st[14] = ld32(nonce + 4);
  st[15] = ld32(nonce + 8);

  uint32_t w[16];
  memcpy(w, st, sizeof(w));
  for (int r = 0; r < 10; r++) {        /* 20 轮 = 10 个双轮 */
    QR(w, 0, 4, 8, 12); QR(w, 1, 5, 9, 13);
    QR(w, 2, 6, 10, 14); QR(w, 3, 7, 11, 15);
    QR(w, 0, 5, 10, 15); QR(w, 1, 6, 11, 12);
    QR(w, 2, 7, 8, 13); QR(w, 3, 4, 9, 14);
  }
  for (int i = 0; i < 16; i++) st32(out + i * 4, w[i] + st[i]);
}

void edl_stream_xor(const uint8_t key[32], const uint8_t nonce[12],
                    const uint8_t *in, size_t len, uint8_t *out) {
  uint8_t ks[64];
  uint32_t counter = 0;
  size_t off = 0;
  while (off < len) {
    edl_block(key, nonce, counter++, ks);
    size_t n = len - off;
    if (n > 64) n = 64;
    for (size_t i = 0; i < n; i++) out[off + i] = in[off + i] ^ ks[i];
    off += n;
  }
}

/* 自定义 keyed MAC：keystream 取一次性密钥，乘-转-异或累加 (nonce||data)。 */
void edl_mac(const uint8_t mk[32], const uint8_t nonce[12],
             const uint8_t *data, size_t len, uint8_t tag[8]) {
  uint8_t pad[64];
  edl_block(mk, nonce, 0xffffffffu, pad);
  uint64_t k0 = ld64(pad + 0), k1 = ld64(pad + 8);
  const uint64_t PRIME = 0x9e3779b97f4a7c15ull;

  uint64_t acc = k0;
  uint8_t chunk[8];

  /* 先吸收 nonce(12 字节，补 4 字节长度信息) */
  memset(chunk, 0, 8);
  memcpy(chunk, nonce, 8);
  acc ^= ld64(chunk);
  acc = acc * PRIME; acc = rotl64(acc, 23) ^ (acc >> 29); acc += k1;
  memset(chunk, 0, 8);
  memcpy(chunk, nonce + 8, 4);
  chunk[4] = (uint8_t)len; chunk[5] = (uint8_t)(len >> 8);
  chunk[6] = (uint8_t)(len >> 16); chunk[7] = (uint8_t)(len >> 24);
  acc ^= ld64(chunk);
  acc = acc * PRIME; acc = rotl64(acc, 23) ^ (acc >> 29); acc += k1;

  size_t off = 0;
  while (off < len) {
    memset(chunk, 0, 8);
    size_t n = len - off;
    if (n > 8) n = 8;
    memcpy(chunk, data + off, n);
    acc ^= ld64(chunk);
    acc = acc * PRIME; acc = rotl64(acc, 23) ^ (acc >> 29); acc += k1;
    off += n;
  }
  acc ^= k1;
  for (int i = 0; i < 8; i++) tag[i] = (uint8_t)(acc >> (8 * i));
}

void edl_kdf(const uint8_t *secret, size_t slen, uint8_t ck[32], uint8_t mk[32]) {
  /* 自定义把 secret 展开成 32 字节种子密钥 */
  uint8_t key0[32];
  for (int i = 0; i < 32; i++)
    key0[i] = (uint8_t)(secret[slen ? i % slen : 0] ^ (i * 0x6d) ^ 0xa5);
  /* 固定 KDF nonce（自定义魔串） */
  static const uint8_t kdf_nonce[12] = {0x45, 0x44, 0x4c, 0x4b, 0x44, 0x46,
                                        0x31, 0x00, 0x00, 0x00, 0x00, 0x00};
  uint8_t out[64];
  edl_block(key0, kdf_nonce, 0, out);
  memcpy(ck, out, 32);
  memcpy(mk, out + 32, 32);
}
