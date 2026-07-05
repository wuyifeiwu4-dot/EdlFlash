/*
 * 自包含 SHA-256 / HMAC-SHA256。
 * 刻意不依赖 OpenSSL/BoringSSL —— 一来避免动态符号被逆向直接定位 hash 入口，
 * 二来与构建期 Gradle 任务的 KDF 实现逐字节对齐（同一套 SHA-256 语义）。
 */
#ifndef EDLSEC_SHA256_H
#define EDLSEC_SHA256_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
  uint32_t state[8];
  uint64_t bitlen;
  uint8_t buf[64];
  size_t buflen;
} sha256_ctx;

void sha256_init(sha256_ctx *c);
void sha256_update(sha256_ctx *c, const void *data, size_t len);
void sha256_final(sha256_ctx *c, uint8_t out[32]);
void sha256(const void *data, size_t len, uint8_t out[32]);

/* HMAC-SHA256：key 任意长度，输出 32 字节。 */
void hmac_sha256(const uint8_t *key, size_t key_len,
                 const uint8_t *msg, size_t msg_len,
                 uint8_t out[32]);

/* 流式 HMAC：消息分段 update，避免拼接定长缓冲带来的截断。 */
typedef struct {
  sha256_ctx ctx;
  uint8_t opad[64];
} hmac_ctx;

void hmac_sha256_init(hmac_ctx *h, const uint8_t *key, size_t key_len);
void hmac_sha256_update(hmac_ctx *h, const void *data, size_t len);
void hmac_sha256_final(hmac_ctx *h, uint8_t out[32]);

#ifdef __cplusplus
}
#endif

#endif
