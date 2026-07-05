/*
 * 自写 ARX 流密码 + keyed MAC（非 AES/标准算法），用于 license 数据包的应用层加密。
 * 结构借鉴 ARX(加-转-异或)以获得真实扩散，但常量与轮转量全自定义，标准工具不认；
 * 与后台 server.js 的 JS 实现逐字节一致。
 *
 * 密钥由签名绑定的 license secret 派生（CK 加密、MK 认证），抓包只见密文、改包即 MAC 失败。
 */
#ifndef EDLCIPHER_H
#define EDLCIPHER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// 生成 64 字节 keystream 块：key(32) + nonce(12) + 32 位计数器。
void edl_block(const uint8_t key[32], const uint8_t nonce[12],
               uint32_t counter, uint8_t out[64]);

// 流式异或：用 (key,nonce) keystream 异或 in→out（计数器自 0 起按 64 字节块递增）。
void edl_stream_xor(const uint8_t key[32], const uint8_t nonce[12],
                    const uint8_t *in, size_t len, uint8_t *out);

// 自定义 keyed MAC：对 (nonce||data) 出 8 字节 tag。
void edl_mac(const uint8_t mk[32], const uint8_t nonce[12],
             const uint8_t *data, size_t len, uint8_t tag[8]);

// 从 secret 派生加密密钥 ck 与认证密钥 mk（自定义展开 + 一块 keystream）。
void edl_kdf(const uint8_t *secret, size_t slen, uint8_t ck[32], uint8_t mk[32]);

#ifdef __cplusplus
}
#endif

#endif
