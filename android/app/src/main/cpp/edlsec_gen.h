/*
 * 默认占位生成头。真实值由 Gradle 任务 generate<Variant>EdlSecHeader 在
 * build/generated/edlsec/<variant>/ 下生成同名头，CMake 把该目录置于更高优先级
 * include 路径覆盖本文件。这里给占位值仅保证脱离 Gradle 也能独立编译/自测。
 *
 * 占位用一个全 0 证书 hash 推导，blob 是对 secret 的对应加密；脱离 Gradle 构建时
 * 实际签名不会等于全 0，故签名校验自然失败（安全的失败方向）。
 */
#ifndef EDLSEC_GEN_H
#define EDLSEC_GEN_H

#include <stdint.h>

#define EDLSEC_KDF_ROUNDS 4096u

/* 占位：全 0 证书 hash 与本仓固定 mask 的异或，即等于 mask 本身。 */
static const uint8_t EDLSEC_EXPECTED_CERT_SHA256_MASKED[32] = {
    0x31, 0x5a, 0xc7, 0x09, 0x44, 0x8e, 0x1d, 0xa2,
    0xe0, 0x6b, 0x93, 0x17, 0x5c, 0xd4, 0x28, 0xb9,
    0x7f, 0x02, 0xaa, 0x61, 0x3d, 0xce, 0x84, 0x10,
    0x59, 0xf1, 0x26, 0x9b, 0x0c, 0x72, 0xdd, 0x48};

/* 占位 blob：用占位 secret 与全 0 证书 hash 派生的密钥流加密的结果长度为 0，
 * 运行期解出空 secret，HMAC 必不匹配（占位构建即拒绝放行）。 */
static const uint8_t EDLSEC_LICENSE_BLOB[1] = {0x00};
static const uint32_t EDLSEC_LICENSE_BLOB_LEN = 0u;

#endif
