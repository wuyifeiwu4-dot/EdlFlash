/*
 * 不经 PackageManager，直接从 APK 文件解析 APK Signing Block，取签名者证书 DER 的 SHA-256。
 * 目的：与 Kotlin 侧 PackageManager 取到的证书 hash 交叉校验。攻击者 hook 掉 PM 也绕不过这条
 * 独立路径——除非同时改 APK 内容，而那会改变这里算出的 hash。
 *
 * 仅提取证书并 hash，不做完整签名验签（验签是系统装包时做的，这里只要"证书指纹"）。
 * 格式依据 AOSP APK Signature Scheme v2/v3/v3.1。
 */
#include "sha256.h"

#include <fcntl.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define SIG_V2  0x7109871au
#define SIG_V3  0xf05368c0u
#define SIG_V31 0x1b93ad61u

typedef struct { const uint8_t *p; size_t n; } slice;

static uint16_t le16(const uint8_t *p) {
  return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}
static uint32_t le32(const uint8_t *p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
         ((uint32_t)p[3] << 24);
}
static uint64_t le64(const uint8_t *p) {
  return (uint64_t)le32(p) | ((uint64_t)le32(p + 4) << 32);
}

/* 从 slice 头部切走 n 字节到 out，推进 s。 */
static int take(slice *s, size_t n, slice *out) {
  if (n > s->n) return -1;
  out->p = s->p;
  out->n = n;
  s->p += n;
  s->n -= n;
  return 0;
}

/* 切走一个 uint32 LE 长度前缀的子块。 */
static int take_lp(slice *s, slice *out) {
  if (s->n < 4) return -1;
  uint32_t n = le32(s->p);
  s->p += 4;
  s->n -= 4;
  return take(s, n, out);
}

/* 从文件尾反扫 EOCD（magic 0x06054b50），用 comment 长度自洽性消歧。 */
static int find_eocd(const uint8_t *m, size_t len, uint32_t *cd_off) {
  if (len < 22) return -1;
  size_t floor = len > (65535u + 22u) ? len - (65535u + 22u) : 0;
  for (size_t i = len - 22;; --i) {
    if (le32(m + i) == 0x06054b50u) {
      uint16_t comment = le16(m + i + 20);
      if (i + 22u + comment == len) {
        if (le16(m + i + 4) || le16(m + i + 6)) return -1;  /* 不支持分卷 */
        uint32_t cd_size = le32(m + i + 12);
        uint32_t off = le32(m + i + 16);
        if ((uint64_t)off + cd_size != i) return -1;  /* CD 必须紧邻 EOCD */
        *cd_off = off;
        return 0;
      }
    }
    if (i == floor) break;
  }
  return -1;
}

/* 定位 Signing Block 的 id-value 对区间（两端 size 校验 + magic 校验）。 */
static int find_sig_pairs(const uint8_t *m, size_t len, uint32_t cd_off, slice *pairs) {
  static const uint8_t magic[16] = "APK Sig Block 42";
  if ((uint64_t)cd_off > len || cd_off < 32) return -1;

  size_t footer = (size_t)cd_off - 24;  /* uint64 size + 16 magic */
  uint64_t size2 = le64(m + footer);
  if (memcmp(m + footer + 8, magic, 16) != 0) return -1;
  if (size2 < 24 || size2 > (uint64_t)cd_off - 8) return -1;

  size_t block_start = (size_t)cd_off - (size_t)size2 - 8;
  if (le64(m + block_start) != size2) return -1;  /* 首尾 size 一致 */

  pairs->p = m + block_start + 8;
  pairs->n = footer - (block_start + 8);
  return 0;
}

/* 在 id-value 对中找指定 scheme id 的 value。 */
static int find_scheme(slice pairs, uint32_t want, slice *value) {
  while (pairs.n) {
    if (pairs.n < 8) return -1;
    uint64_t pair_len = le64(pairs.p);
    pairs.p += 8;
    pairs.n -= 8;
    if (pair_len < 4 || pair_len > pairs.n) return -1;
    uint32_t id = le32(pairs.p);
    if (id == want) {
      value->p = pairs.p + 4;
      value->n = (size_t)pair_len - 4;
      return 0;
    }
    pairs.p += (size_t)pair_len;
    pairs.n -= (size_t)pair_len;
  }
  return -1;
}

/*
 * 从 v2/v3/v3.1 scheme block 取第一个签名者的第一张（leaf）证书 DER。
 * 三种方案到证书的路径一致：
 *   block → LP(signers) → LP(signer) → LP(signed_data) → LP(digests) → LP(certificates) → LP(leaf)
 * v3/v3.1 仅在 signed_data/signer 的"后部"多了 minSDK/maxSDK，取证书无需读到那里，故不区分 v2/v3。
 * 只保留安全相关不变量：单签名者(多签名者 fail-closed)、证书非空、长度前缀全程边界内。
 * 不强求消费完 signed_data —— 真实 v2 块在 attrs 后存在合法尾部字节(verity/padding)，
 * 早期版本要求"完整消费"会把正版包误判为签名无效。
 */
static int leaf_cert(slice block, slice *cert) {
  slice signers, signer, signed_data, digests, certs;
  if (take_lp(&block, &signers)) return -1;
  if (take_lp(&signers, &signer)) return -1;
  if (signers.n != 0) return -1;  /* 多签名者 fail-closed（本应用单 key） */

  if (take_lp(&signer, &signed_data)) return -1;
  if (take_lp(&signed_data, &digests)) return -1;  /* digests，跳过 */
  if (take_lp(&signed_data, &certs)) return -1;    /* certificates */
  if (take_lp(&certs, cert)) return -1;            /* leaf（第一张即签名证书） */
  if (cert->n == 0) return -1;                     /* 空证书拒绝 */
  return 0;
}

/* 失败返回非 0；成功把 leaf 证书 SHA-256 写入 out32。 */
__attribute__((visibility("hidden")))
int apk_signer_cert_sha256(const char *apk_path, uint8_t out32[32]) {
  int fd = open(apk_path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  struct stat sb;
  if (fstat(fd, &sb) != 0 || sb.st_size <= 0) {
    close(fd);
    return -1;
  }
  size_t len = (size_t)sb.st_size;
  uint8_t *m = (uint8_t *)mmap(0, len, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (m == MAP_FAILED) return -1;

  int rc = -1;
  uint32_t cd_off = 0;
  slice pairs, block, cert;
  if (find_eocd(m, len, &cd_off) == 0 &&
      find_sig_pairs(m, len, cd_off, &pairs) == 0) {
    const uint32_t order[3] = {SIG_V31, SIG_V3, SIG_V2};
    for (int i = 0; i < 3; i++) {
      if (find_scheme(pairs, order[i], &block) == 0 &&
          leaf_cert(block, &cert) == 0) {
        sha256(cert.p, cert.n, out32);
        rc = 0;
        break;
      }
    }
  }
  munmap(m, len);
  return rc;
}
