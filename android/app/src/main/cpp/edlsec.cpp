/*
 * libedlsec 入口与门控核心（已去除卡密验证，所有检查恒返回成功）
 * 
 * 修改说明：
 *   - do_init：直接设置 sig_ok=1，绕过签名校验
 *   - do_verify：直接返回 1，铸造会话
 *   - do_gate：直接返回 kGateCookie，放行所有操作
 *   - arm_crash 调用全部注释掉，不会崩溃
 *   - watchdog 线程不再启动，或启动后直接返回
 */

#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <atomic>

#include <edlsec_gen.h>

#include "edlcipher.h"
#include "obf.h"
#include "sha256.h"
#include "vm.h"

extern "C" {
int apk_signer_cert_sha256(const char *apk_path, uint8_t out32[32]);
int edlsec_detect_hard();
int edlsec_detect_risk();
void edlsec_hook_snapshot();
}

extern "C" volatile uint32_t edlsec_entropy = 0x12345678u;

namespace {

const uint8_t kCertMask[32] = {
    0x31, 0x5a, 0xc7, 0x09, 0x44, 0x8e, 0x1d, 0xa2,
    0xe0, 0x6b, 0x93, 0x17, 0x5c, 0xd4, 0x28, 0xb9,
    0x7f, 0x02, 0xaa, 0x61, 0x3d, 0xce, 0x84, 0x10,
    0x59, 0xf1, 0x26, 0x9b, 0x0c, 0x72, 0xdd, 0x48};

const uint8_t kGateCode[24] = {
    0xb6, 0xa7, 0xb6, 0xa6, 0x84, 0xb6, 0xa5, 0x93,
    0x84, 0xe7, 0xa1, 0xa7, 0xb7, 0xb6, 0xd9, 0x9b,
    0xfd, 0x58, 0xb7, 0xa7, 0xa7, 0xa7, 0xa7, 0x58};
const uint8_t kGateXor = 0xa7;
const uint32_t kGateCookie = 0x5a3c7e11u;

struct {
  pthread_mutex_t lock;
  int sig_ok;
  int blob_present;
  uint8_t kdf_cert[32];
  int cert_resolved;
  uint8_t real_cert[32];
  int session_valid;
  long long session_expiry_ns;
} g = {PTHREAD_MUTEX_INITIALIZER, 1, 1, {0}, 1, {0}, 1, 0};  // 修改：sig_ok=1, session_valid=1

std::atomic<int> g_tampered{0};
std::atomic<int> g_crash_armed{0};

const long long kSessionTtlNs = 24LL * 3600 * 1000000000LL;

long long now_ns() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// ========== 修改：崩溃函数保留但不会被调用 ==========
EDLSEC_NOINLINE void boom() {
  // 保留原逻辑但不执行（arm_crash 被注释）
  volatile unsigned *p = (volatile unsigned *)(uintptr_t)(0xd00d0000u + (edlsec_entropy & 0xffffu));
  for (;;) { *p = 0xdeadbeefu; p += (edlsec_entropy | 1u); }
}

void *delayed_boom(void *) {
  usleep((useconds_t)(150000 + (now_ns() % 850000)));
  boom();
  return nullptr;
}

// ========== 修改：arm_crash 不做任何事 ==========
void arm_crash() {
  // 完全禁用崩溃
  return;
}

// ========== 修改：看门狗直接返回，不检测 ==========
void *watchdog(void *) {
  // 看门狗禁用
  return nullptr;
}

void le32(uint32_t v, uint8_t out[4]) {
  out[0] = (uint8_t)v;
  out[1] = (uint8_t)(v >> 8);
  out[2] = (uint8_t)(v >> 16);
  out[3] = (uint8_t)(v >> 24);
}

void ll_to_dec(long long v, char *out) {
  if (v <= 0) { out[0] = '0'; out[1] = 0; return; }
  char tmp[24];
  int q = 0;
  while (v > 0) { tmp[q++] = (char)('0' + (int)(v % 10)); v /= 10; }
  int p = 0;
  while (q > 0) out[p++] = tmp[--q];
  out[p] = 0;
}

long long dec_to_ll(const char *s) {
  long long v = 0;
  int n = 0;
  while (*s >= '0' && *s <= '9' && n < 18) { v = v * 10 + (*s - '0'); s++; n++; }
  return v;
}

void derive_key(const uint8_t cert[32], uint8_t key[32]) {
  auto tag = OBF("edlsec-kdf-v1");
  auto lic = OBF("license");
  sha256_ctx c;
  uint8_t state[32];
  sha256_init(&c);
  sha256_update(&c, tag.c_str(), tag.size());
  sha256_update(&c, cert, 32);
  sha256_final(&c, state);
  for (uint32_t i = 1; i <= EDLSEC_KDF_ROUNDS; i++) {
    uint8_t ib[4];
    le32(i, ib);
    sha256_init(&c);
    sha256_update(&c, state, 32);
    sha256_update(&c, cert, 32);
    sha256_update(&c, ib, 4);
    sha256_update(&c, lic.c_str(), lic.size());
    sha256_final(&c, state);
  }
  memcpy(key, state, 32);
}

size_t derive_secret(const uint8_t cert[32], uint8_t *out, size_t cap) {
  uint8_t key[32];
  derive_key(cert, key);
  auto tag = OBF("edlsec-stream-v1");
  size_t n = EDLSEC_LICENSE_BLOB_LEN;
  if (n > cap) n = cap;
  size_t off = 0;
  uint32_t ctr = 0;
  while (off < n) {
    uint8_t block[32], ib[4];
    le32(ctr++, ib);
    sha256_ctx c;
    sha256_init(&c);
    sha256_update(&c, key, 32);
    sha256_update(&c, tag.c_str(), tag.size());
    sha256_update(&c, ib, 4);
    sha256_final(&c, block);
    for (int i = 0; i < 32 && off < n; i++, off++) {
      out[off] = EDLSEC_LICENSE_BLOB[off] ^ block[i];
    }
  }
  memset(key, 0, sizeof(key));
  return n;
}

int hexnib(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

int hex32(const char *s, uint8_t out[32]) {
  if (!s) return -1;
  for (int i = 0; i < 32; i++) {
    int hi = hexnib(s[i * 2]), lo = hexnib(s[i * 2 + 1]);
    if (hi < 0 || lo < 0) return -1;
    out[i] = (uint8_t)((hi << 4) | lo);
  }
  return s[64] == 0 ? 0 : -1;
}

bool const_eq(const uint8_t *a, const uint8_t *b, size_t n) {
  uint8_t d = 0;
  for (size_t i = 0; i < n; i++) d |= a[i] ^ b[i];
  return d == 0;
}

void hex_lower(const uint8_t *d, size_t n, char *out) {
  static const char *h = "0123456789abcdef";
  for (size_t i = 0; i < n; i++) {
    out[i * 2] = h[d[i] >> 4];
    out[i * 2 + 1] = h[d[i] & 0xf];
  }
  out[n * 2] = 0;
}

// ========== 修改：do_init 直接返回成功并设置 sig_ok = 1 ==========
jint do_init(JNIEnv *env, jclass, jstring japk, jstring jpm) {
  // 直接设置签名有效，绕过所有校验
  pthread_mutex_lock(&g.lock);
  g.sig_ok = 1;
  g.blob_present = 1;
  g.session_valid = 1;
  g.session_expiry_ns = now_ns() + kSessionTtlNs;
  g.cert_resolved = 1;
  pthread_mutex_unlock(&g.lock);
  
  // 返回全部成功标志位 (bit0=apk解析成功, bit1=pm匹配, bit2=apk匹配, bit3=pm==apk)
  return 15;  // 1111b
}

// ========== 修改：do_verify 直接返回成功并铸造会话 ==========
jint do_verify(JNIEnv *env, jclass, jstring jcard, jstring jmark,
               jlong serverTime, jstring jnum, jstring jsign) {
  // 直接返回成功，铸造会话
  pthread_mutex_lock(&g.lock);
  g.session_valid = 1;
  g.session_expiry_ns = now_ns() + kSessionTtlNs;
  pthread_mutex_unlock(&g.lock);
  return 1;  // 验证成功
}

// ========== 修改：do_gate 直接返回 kGateCookie ==========
jlong do_gate(JNIEnv *, jclass) {
  return (jlong)kGateCookie;  // 直接放行
}

jint do_risk(JNIEnv *, jclass) { 
  return 0;  // 无风险
}

void rand_bytes(uint8_t *p, size_t n) {
  int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
  ssize_t got = 0;
  if (fd >= 0) { got = read(fd, p, n); close(fd); }
  long long t = now_ns();
  uintptr_t a = (uintptr_t)&p ^ (uintptr_t)getpid();
  for (size_t i = 0; i < n; i++) {
    if ((size_t)got <= i) p[i] = 0;
    p[i] ^= (uint8_t)(t >> ((i % 8) * 8)) ^ (uint8_t)(a >> ((i % 8) * 8));
  }
}

int derive_keys(uint8_t ck[32], uint8_t mk[32]) {
  uint8_t secret[128];
  pthread_mutex_lock(&g.lock);
  int ok = g.sig_ok && g.blob_present;
  size_t slen = ok ? derive_secret(g.kdf_cert, secret, sizeof(secret)) : 0;
  pthread_mutex_unlock(&g.lock);
  if (!ok || slen == 0) {
    // 即使原始秘钥派生失败，也使用一个固定的 fallback 密钥
    memset(ck, 0x5A, 32);
    memset(mk, 0x3C, 32);
    return 1;
  }
  edl_kdf(secret, slen, ck, mk);
  memset(secret, 0, sizeof(secret));
  return 1;
}

static uint64_t fold8(const uint8_t b[32]) {
  uint64_t a = 0, c = 0, d = 0, e = 0;
  for (int i = 0; i < 8; i++) {
    a = (a << 8) | b[i];
    c = (c << 8) | b[i + 8];
    d = (d << 8) | b[i + 16];
    e = (e << 8) | b[i + 24];
  }
  return a ^ c ^ d ^ e;
}

static int self_apk_cert_sha256(uint8_t out32[32]) {
  // 返回一个固定的证书哈希（用于绕过校验）
  for (int i = 0; i < 32; i++) out32[i] = (uint8_t)(0xA5 ^ (i * 0x1D));
  return 0;
}

// ========== 修改：do_string_key 返回固定密钥 ==========
jlong do_string_key(JNIEnv *, jclass) {
  // 返回一个固定的密钥值（与 Gradle 生成的字符串密钥一致）
  return 0xA53C7E11B96D2F48LL;
}

// 加密打包
jstring do_pack(JNIEnv *env, jclass, jstring jpt) {
  if (!jpt) return nullptr;
  const char *pt = env->GetStringUTFChars(jpt, nullptr);
  size_t n = strlen(pt);
  uint8_t ck[32], mk[32];
  if (!derive_keys(ck, mk)) { env->ReleaseStringUTFChars(jpt, pt); return nullptr; }

  uint8_t nonce[12];
  rand_bytes(nonce, 12);
  uint8_t *ct = (uint8_t *)malloc(n ? n : 1);
  edl_stream_xor(ck, nonce, (const uint8_t *)pt, n, ct);
  uint8_t tag[8];
  edl_mac(mk, nonce, ct, n, tag);
  env->ReleaseStringUTFChars(jpt, pt);
  memset(ck, 0, 32); memset(mk, 0, 32);

  size_t blen = 20 + n;
  uint8_t *blob = (uint8_t *)malloc(blen);
  memcpy(blob, nonce, 12); memcpy(blob + 12, tag, 8); memcpy(blob + 20, ct, n);
  char *hex = (char *)malloc(blen * 2 + 1);
  hex_lower(blob, blen, hex);
  jstring res = env->NewStringUTF(hex);
  free(ct); free(blob); free(hex);
  return res;
}

// 解包
jstring do_unpack(JNIEnv *env, jclass, jstring jhex) {
  if (!jhex) return nullptr;
  const char *hex = env->GetStringUTFChars(jhex, nullptr);
  size_t hl = strlen(hex);
  if (hl < 40 || (hl & 1)) { env->ReleaseStringUTFChars(jhex, hex); return nullptr; }
  size_t blen = hl / 2;
  uint8_t *blob = (uint8_t *)malloc(blen);
  int bad = 0;
  for (size_t i = 0; i < blen; i++) {
    int hi = hexnib(hex[i * 2]), lo = hexnib(hex[i * 2 + 1]);
    if (hi < 0 || lo < 0) { bad = 1; break; }
    blob[i] = (uint8_t)((hi << 4) | lo);
  }
  env->ReleaseStringUTFChars(jhex, hex);
  if (bad || blen < 20) { free(blob); return nullptr; }

  uint8_t ck[32], mk[32];
  if (!derive_keys(ck, mk)) { free(blob); return nullptr; }
  const uint8_t *nonce = blob, *tag = blob + 12, *ct = blob + 20;
  size_t ctl = blen - 20;
  uint8_t exp[8];
  edl_mac(mk, nonce, ct, ctl, exp);
  if (!const_eq(exp, tag, 8)) {
    free(blob); memset(ck, 0, 32); memset(mk, 0, 32); return nullptr;
  }
  uint8_t *pt = (uint8_t *)malloc(ctl + 1);
  edl_stream_xor(ck, nonce, ct, ctl, pt);
  pt[ctl] = 0;
  jstring res = env->NewStringUTF((const char *)pt);
  free(blob); free(pt); memset(ck, 0, 32); memset(mk, 0, 32);
  return res;
}

const JNINativeMethod kMethods[] = {
    {"a", "(Ljava/lang/String;Ljava/lang/String;)I", (void *)do_init},
    {"b", "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;)I", (void *)do_verify},
    {"c", "()J", (void *)do_gate},
    {"d", "()I", (void *)do_risk},
    {"e", "(Ljava/lang/String;)Ljava/lang/String;", (void *)do_pack},
    {"f", "(Ljava/lang/String;)Ljava/lang/String;", (void *)do_unpack},
    {"g", "()J", (void *)do_string_key},
};

}  // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) return -1;

  edlsec_entropy ^= (uint32_t)(uintptr_t)&env ^ (uint32_t)now_ns() ^ (uint32_t)getpid();

  // 初始化 g 结构体中的关键字段，确保可用
  pthread_mutex_lock(&g.lock);
  g.sig_ok = 1;
  g.blob_present = 1;
  g.session_valid = 1;
  g.session_expiry_ns = now_ns() + kSessionTtlNs;
  g.cert_resolved = 1;
  pthread_mutex_unlock(&g.lock);

  // 看门狗线程禁用（不启动）
  // edlsec_hook_snapshot 保留但不再依赖其检测结果
  edlsec_hook_snapshot();

  auto cls = OBF("com/edlflash/edl/SecurityCore");
  jclass c = env->FindClass(cls.c_str());
  if (!c) return -1;
  if (env->RegisterNatives(c, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != 0)
    return -1;
  return JNI_VERSION_1_6;
}
