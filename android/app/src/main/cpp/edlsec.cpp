/*
 * libedlsec 入口与门控核心：
 *  - JNI 动态注册（Java 方法名与 native 符号解耦，逆向难定位）。
 *  - 签名校验做成"非布尔"：用实际证书 hash 经 KDF 解密内嵌的 license secret blob。
 *    只有签名与构建期一致才能解出正确 secret；否则后续 HMAC 验签必失败、后台响应被拒。
 *    重打包 → 证书变 → 解密错 → 卡密永远验不过，且无原私钥无法修复。
 *  - 卡密响应 HMAC 在 native 用 secret 验签，secret 明文只在签名正确时短暂出现于此。
 *  - 授权下沉为 native 会话；EDL 真实入口(runOp) 必须经 gate 拿到非零 cookie 才放行，
 *    JS 层 hook verify 返 ok 无法凭空铸造该会话。
 *  - 最终门控决策由 mini-VM 执行加密字节码，逆向须先还原 VM。
 *
 * KDF（与 Gradle 任务逐字节一致）：
 *   state0 = SHA256("edlsec-kdf-v1" || cert)
 *   state_i = SHA256(state_{i-1} || cert || LE32(i) || "license")  i=1..ROUNDS
 *   key = state_ROUNDS
 *   stream_c = SHA256(key || "edlsec-stream-v1" || LE32(c))
 *   secret[j] = blob[j] ^ stream[j]
 */
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <atomic>

// 尖括号：只按 -I 顺序查找（generated 目录在前），避免引号优先命中本目录占位头。
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

// obf.h 的不透明谓词所用熵源，启动时扰动。
extern "C" volatile uint32_t edlsec_entropy = 0x12345678u;

namespace {

// 与 Gradle 任务一致的 32 字节遮罩。
const uint8_t kCertMask[32] = {
    0x31, 0x5a, 0xc7, 0x09, 0x44, 0x8e, 0x1d, 0xa2,
    0xe0, 0x6b, 0x93, 0x17, 0x5c, 0xd4, 0x28, 0xb9,
    0x7f, 0x02, 0xaa, 0x61, 0x3d, 0xce, 0x84, 0x10,
    0x59, 0xf1, 0x26, 0x9b, 0x0c, 0x72, 0xdd, 0x48};

// 单字节异或封装的 gate 字节码（运行前解出，跑完即弃）。
const uint8_t kGateCode[24] = {
    0xb6, 0xa7, 0xb6, 0xa6, 0x84, 0xb6, 0xa5, 0x93,
    0x84, 0xe7, 0xa1, 0xa7, 0xb7, 0xb6, 0xd9, 0x9b,
    0xfd, 0x58, 0xb7, 0xa7, 0xa7, 0xa7, 0xa7, 0x58};
const uint8_t kGateXor = 0xa7;
const uint32_t kGateCookie = 0x5a3c7e11u;  // 与字节码 PUSH 立即数一致

// 会话/签名状态（进程内）。不常驻明文 secret：仅存派生用的证书 hash，验签时瞬时派生再擦除。
struct {
  pthread_mutex_t lock;
  int sig_ok;            // 实际证书 == 构建期期望（双路严格一致）
  int blob_present;      // 内嵌 secret blob 长度 > 0
  uint8_t kdf_cert[32];  // 派生 secret 用的实际证书 hash
  int cert_resolved;     // 至少一路真实解析出了证书（区别于回退到 expected）
  uint8_t real_cert[32]; // 仅真实解析结果（绝不存 expected 兜底），供字符串密钥绑定
  int session_valid;
  long long session_expiry_ns;
} g = {PTHREAD_MUTEX_INITIALIZER, 0, 0, {0}, 0, {0}, 0, 0};

// positively 篡改标志（跨线程读写，用 atomic 避免数据竞争）+ 一次性崩溃 arm 守卫。
std::atomic<int> g_tampered{0};
std::atomic<int> g_crash_armed{0};

const long long kSessionTtlNs = 24LL * 3600 * 1000000000LL;

long long now_ns() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// 篡改即崩：写非法地址触发 SIGSEGV（非 abort 字样，难 grep/定位），
// 地址掺入运行期熵，崩溃点不固定。这是"确定重打包"时的硬响应，正版不会走到这。
EDLSEC_NOINLINE void boom() {
  volatile unsigned *p = (volatile unsigned *)(uintptr_t)(0xd00d0000u + (edlsec_entropy & 0xffffu));
  for (;;) { *p = 0xdeadbeefu; p += (edlsec_entropy | 1u); }
}

void *delayed_boom(void *) {
  // 随机短延迟，使崩溃与校验点解耦，增加定位/patch 成本。
  long long n = now_ns();
  usleep((useconds_t)(150000 + (n % 850000)));  // 0.15~1.0s
  boom();
  return nullptr;
}

// 仅在 positively 篡改时调用：起后台线程延迟崩，且多点冗余（verify/gate 也会触发），
// hook 掉一处仍被另一处带走。一次性 arm，避免重复起线程。
void arm_crash() {
  if (g_crash_armed.exchange(1)) return;
  pthread_t t;
  if (pthread_create(&t, nullptr, delayed_boom, nullptr) == 0) pthread_detach(t);
}

// 连续看门狗：周期(带抖动)复检高置信 Frida/调试信号。运行后才注入 Frida 也会被抓到，
// 命中即置篡改标志并延迟崩——单次启动检测可被"启动后再注入"绕过，看门狗堵这个口。
void *watchdog(void *) {
  for (;;) {
    long long n = now_ns();
    usleep((useconds_t)(500000 + (n % 600000)));  // 0.5~1.1s 抖动，难预测
    if (edlsec_detect_hard()) {
      g_tampered.store(1);
      arm_crash();
    }
  }
  return nullptr;
}

void le32(uint32_t v, uint8_t out[4]) {
  out[0] = (uint8_t)v;
  out[1] = (uint8_t)(v >> 8);
  out[2] = (uint8_t)(v >> 16);
  out[3] = (uint8_t)(v >> 24);
}

// 非负整数转十进制字符串。
void ll_to_dec(long long v, char *out) {
  if (v <= 0) { out[0] = '0'; out[1] = 0; return; }
  char tmp[24];
  int q = 0;
  while (v > 0) { tmp[q++] = (char)('0' + (int)(v % 10)); v /= 10; }
  int p = 0;
  while (q > 0) out[p++] = tmp[--q];
  out[p] = 0;
}

// 解析前导十进制数字（非数字处停止）。限 18 位封顶，避免 signed overflow(UB)。
long long dec_to_ll(const char *s) {
  long long v = 0;
  int n = 0;
  while (*s >= '0' && *s <= '9' && n < 18) { v = v * 10 + (*s - '0'); s++; n++; }
  return v;
}

// 派生 key：与 Gradle 逐字节一致。
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

// 用 cert 经 KDF 把内嵌 blob 瞬时解密到 out（封顶 cap），返回 secret 长度。
// 调用方用完务必擦除 out。
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
// 64 字符 hex → 32 字节，失败返回非 0。
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

// ---- JNI 实现（动态注册，名字与 Java 无关） ----

// init(apkPath, pmCertHex)：双路证书交叉校验 + KDF 解出 secret。
// 返回 bit0=apk解析成功 bit1=pm==期望 bit2=apk==期望 bit3=pm==apk。
jint do_init(JNIEnv *env, jclass, jstring japk, jstring jpm) {
  uint8_t expected[32];
  for (int i = 0; i < 32; i++)
    expected[i] = EDLSEC_EXPECTED_CERT_SHA256_MASKED[i] ^ kCertMask[i];

  const char *apk = japk ? env->GetStringUTFChars(japk, nullptr) : nullptr;
  uint8_t apk_hash[32];
  int apk_ok = (apk && apk_signer_cert_sha256(apk, apk_hash) == 0) ? 1 : 0;
  if (apk) env->ReleaseStringUTFChars(japk, apk);

  const char *pm = jpm ? env->GetStringUTFChars(jpm, nullptr) : nullptr;
  uint8_t pm_hash[32];
  int pm_ok = (pm && hex32(pm, pm_hash) == 0) ? 1 : 0;
  if (pm) env->ReleaseStringUTFChars(jpm, pm);

  int pm_eq = pm_ok && const_eq(pm_hash, expected, 32);
  int apk_eq = apk_ok && const_eq(apk_hash, expected, 32);
  int pm_apk_eq = pm_ok && apk_ok && const_eq(pm_hash, apk_hash, 32);

  // 严格 fail-closed：两路都成功、都命中期望、且彼此一致才算签名有效。
  // APK 独立解析失败 / 多签名者 / 任一不一致 → 视为签名无效（不退化为只信 PM）。
  int sig_ok = apk_ok && pm_ok && apk_eq && pm_eq && pm_apk_eq;

  // positively 篡改：某条路成功解析出证书但 != 期望，或两路彼此不一致 → 确定重打包。
  // 区别于"解析失败"(读不到 APK/PM 异常)——后者只 fail-closed 不崩，避免误杀异常环境。
  int tampered = (apk_ok && !apk_eq) || (pm_ok && !pm_eq) ||
                 (apk_ok && pm_ok && !pm_apk_eq);

  g_tampered.store(tampered ? 1 : 0);
  pthread_mutex_lock(&g.lock);
  g.sig_ok = sig_ok ? 1 : 0;
  // 派生 secret 用的实际证书 hash：优先 APK 解析结果，回退 PM（签名无效时此值无意义）。
  memcpy(g.kdf_cert, apk_ok ? apk_hash : (pm_ok ? pm_hash : expected), 32);
  // 字符串密钥绑定专用：仅在真实解析出证书时记录真实值，绝不回退 expected——
  // 否则解析失败环境(actual==expected)会异或抵消出正确 key，等于没绑。
  g.cert_resolved = (apk_ok || pm_ok) ? 1 : 0;
  if (apk_ok)
    memcpy(g.real_cert, apk_hash, 32);
  else if (pm_ok)
    memcpy(g.real_cert, pm_hash, 32);
  g.blob_present = EDLSEC_LICENSE_BLOB_LEN > 0 ? 1 : 0;
  pthread_mutex_unlock(&g.lock);

  // 确定重打包 → 延迟硬崩（后台线程，崩溃点与校验解耦）。正版不触发。
  if (tampered) arm_crash();

  jint status = (apk_ok ? 1 : 0) | (pm_eq ? 2 : 0) | (apk_eq ? 4 : 0) |
                (pm_apk_eq ? 8 : 0);
  return status;
}

// verifyLicense(card, markcode, serverTime, num, sign)
// 用签名派生的 secret 重算 HMAC("card|markcode|serverTime|num") 比对 sign。
// 命中且签名有效且无强信号篡改 → 铸造会话，返回 1；否则 0。
jint do_verify(JNIEnv *env, jclass, jstring jcard, jstring jmark,
               jlong serverTime, jstring jnum, jstring jsign) {
  if (serverTime < 0) return 0;
  // 冗余触发：若 init 判定确定重打包，这里也带走（hook 掉 init 的崩溃仍逃不掉）。
  if (g_tampered.load()) arm_crash();
  // 注意：登录验签只做签名+HMAC，不在此处做 Frida/调试检测——检测放在 gate()
  // (真实 EDL 操作前)进行，避免任何环境检测误报把正版用户卡在登录。

  const char *card = jcard ? env->GetStringUTFChars(jcard, nullptr) : "";
  const char *mark = jmark ? env->GetStringUTFChars(jmark, nullptr) : "";
  const char *num = jnum ? env->GetStringUTFChars(jnum, nullptr) : "";
  const char *sign = jsign ? env->GetStringUTFChars(jsign, nullptr) : "";

  char tbuf[24];
  ll_to_dec((long long)serverTime, tbuf);
  const char bar = '|';

  int ok = 0;
  pthread_mutex_lock(&g.lock);
  if (g.sig_ok && g.blob_present) {
    // secret 瞬时派生，HMAC 后立即擦除，不常驻内存。流式 update 避免定长缓冲截断。
    uint8_t secret[128];
    size_t slen = derive_secret(g.kdf_cert, secret, sizeof(secret));
    hmac_ctx h;
    hmac_sha256_init(&h, secret, slen);
    hmac_sha256_update(&h, card, strlen(card));
    hmac_sha256_update(&h, &bar, 1);
    hmac_sha256_update(&h, mark, strlen(mark));
    hmac_sha256_update(&h, &bar, 1);
    hmac_sha256_update(&h, tbuf, strlen(tbuf));
    hmac_sha256_update(&h, &bar, 1);
    hmac_sha256_update(&h, num, strlen(num));
    uint8_t mac[32];
    hmac_sha256_final(&h, mac);
    memset(secret, 0, sizeof(secret));

    char machex[65];
    hex_lower(mac, 32, machex);
    if (strlen(sign) == 64 &&
        const_eq((const uint8_t *)machex, (const uint8_t *)sign, 64)) {
      // 会话 TTL 取 min(本地上限, 服务端剩余有效期)。时卡 num=到期 epoch，剩余=num-serverTime。
      // 仅当剩余落在 (0, 24h] 才据其收紧：避免大数相乘溢出，且次卡(num=剩余次数,远小于
      // serverTime)/剩余>24h 一律按本地 24h 上限。
      long long ttl = kSessionTtlNs;
      long long numv = dec_to_ll(num);
      long long delta = (serverTime > 0) ? numv - (long long)serverTime : -1;
      if (delta > 0 && delta <= 86400LL) {
        long long rem = delta * 1000000000LL;
        if (rem < ttl) ttl = rem;
      }
      g.session_valid = 1;
      g.session_expiry_ns = now_ns() + ttl;
      ok = 1;
    }
  }
  pthread_mutex_unlock(&g.lock);

  if (jcard) env->ReleaseStringUTFChars(jcard, card);
  if (jmark) env->ReleaseStringUTFChars(jmark, mark);
  if (jnum) env->ReleaseStringUTFChars(jnum, num);
  if (jsign) env->ReleaseStringUTFChars(jsign, sign);
  return ok;
}

// gate()：EDL 真实入口放行判定。实时复检签名/会话/篡改，交 VM 出最终 cookie。
jlong do_gate(JNIEnv *, jclass) {
  if (g_tampered.load()) arm_crash();  // 冗余触发：确定重打包者在真实操作前也带走
  int hard = edlsec_detect_hard();
  pthread_mutex_lock(&g.lock);
  int sig = g.sig_ok;
  int sess = (g.session_valid && now_ns() < g.session_expiry_ns) ? 1 : 0;
  pthread_mutex_unlock(&g.lock);

  uint8_t code[sizeof(kGateCode)];
  for (size_t i = 0; i < sizeof(kGateCode); i++) code[i] = kGateCode[i] ^ kGateXor;

  uint32_t in[3] = {(uint32_t)sig, (uint32_t)sess, (uint32_t)hard};
  uint32_t result = 0;
  int rc = vm_run(code, sizeof(code), in, 3, &result);
  memset(code, 0, sizeof(code));
  if (rc != 0 || result != kGateCookie) return 0;
  return (jlong)(uint32_t)result;
}

// 弱信号风险分（VPN 等），供前端提示，不作硬封禁。
jint do_risk(JNIEnv *, jclass) { return edlsec_detect_risk(); }

// 随机 nonce：优先 /dev/urandom，混入时间/地址做兜底。
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

// 取签名绑定的 secret 派生 CK/MK。成功返回 1。
int derive_keys(uint8_t ck[32], uint8_t mk[32]) {
  uint8_t secret[128];
  pthread_mutex_lock(&g.lock);
  int ok = g.sig_ok && g.blob_present;
  size_t slen = ok ? derive_secret(g.kdf_cert, secret, sizeof(secret)) : 0;
  pthread_mutex_unlock(&g.lock);
  if (!ok || slen == 0) return 0;
  edl_kdf(secret, slen, ck, mk);
  memset(secret, 0, sizeof(secret));
  return 1;
}

// 32 字节折叠成 64bit：4 个 8 字节 lane 异或（线性，便于异或抵消推导）。
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

// 扫 /proc/self/maps 找已映射的 APK(优先 base.apk)，解析其签名证书 SHA-256。
// 用于加强版/脱壳等不经 do_init 的环境自取真实证书。成功返回 0。
static int self_apk_cert_sha256(uint8_t out32[32]) {
  auto mp = OBF("/proc/self/maps");
  FILE *f = fopen(mp.c_str(), "r");
  if (!f) return -1;
  char line[512];
  char best[256];
  best[0] = 0;
  while (fgets(line, sizeof(line), f)) {
    char *p = strchr(line, '/');
    if (!p) continue;
    size_t len = strlen(p);
    while (len && (p[len - 1] == '\n' || p[len - 1] == '\r')) p[--len] = 0;
    if (len < 4 || strcmp(p + len - 4, ".apk") != 0) continue;
    if (strstr(p, "base.apk")) { snprintf(best, sizeof(best), "%s", p); break; }
    if (!best[0]) snprintf(best, sizeof(best), "%s", p);
  }
  fclose(f);
  if (!best[0]) return -1;
  return apk_signer_cert_sha256(best, out32);
}

// DEX 字符串混淆密钥。基值与 buildSrc EdlStrings.K 一致(0xA53C7E11B96D2F48)，但**运行期与
// 实际签名证书异或抵消绑定**：K = K_base ^ fold8(actual_cert) ^ fold8(expected_cert)。
//   - 正版：actual==expected → 两项抵消 → 返回 K_base，解密正常；
//   - 重打包/MT 加强版异己环境：actual≠expected → K 偏移 → 全字符串乱码(fail-closed)。
// 无显式分支可被 .so patch 翻转，签名不符即数学上得错 key。MT 普通模式更因解密链含 native
// 调用无法静态折叠。real_cert 只取真实解析值(绝不回退 expected，否则解析失败会抵消出正确 key)。
jlong do_string_key(JNIEnv *, jclass) {
  static const uint8_t enc[8] = {0x94, 0x66, 0xb9, 0x18, 0xfd, 0xe3, 0x32, 0xea};
  static const uint8_t msk[8] = {0x31, 0x5a, 0xc7, 0x09, 0x44, 0x8e, 0x1d, 0xa2};
  uint64_t kbase = 0;
  for (int i = 0; i < 8; i++) kbase = (kbase << 8) | (uint8_t)(enc[i] ^ msk[i]);

  uint8_t expected[32];
  for (int i = 0; i < 32; i++)
    expected[i] = EDLSEC_EXPECTED_CERT_SHA256_MASKED[i] ^ kCertMask[i];

  uint8_t actual[32];
  int have = 0;
  pthread_mutex_lock(&g.lock);
  if (g.cert_resolved) {
    memcpy(actual, g.real_cert, 32);
    have = 1;
  }
  pthread_mutex_unlock(&g.lock);
  if (!have && self_apk_cert_sha256(actual) == 0) have = 1;
  if (!have)
    for (int i = 0; i < 32; i++) actual[i] = (uint8_t)(0xa5 ^ (i * 0x9d));  // poison≠expected

  uint64_t k = kbase ^ fold8(actual) ^ fold8(expected);
  memset(actual, 0, sizeof(actual));
  memset(expected, 0, sizeof(expected));
  return (jlong)k;
}

// pack(plaintext) → hex( nonce(12) || tag(8) || ciphertext )。自定义流密码加密 + keyed MAC。
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

// unpack(hexblob) → plaintext。MAC 不符返回 null（防改包/伪造）。
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

  // 扰动不透明谓词熵源（地址 + 时间），编译期不可知。
  edlsec_entropy ^= (uint32_t)(uintptr_t)&env ^ (uint32_t)now_ns() ^ (uint32_t)getpid();

  // 尽早建立内联 hook 自卫基线（此刻通常还未被 hook），再起连续看门狗。
  edlsec_hook_snapshot();
  {
    pthread_t t;
    if (pthread_create(&t, nullptr, watchdog, nullptr) == 0) pthread_detach(t);
  }

  auto cls = OBF("com/edlflash/edl/SecurityCore");
  jclass c = env->FindClass(cls.c_str());
  if (!c) return -1;
  if (env->RegisterNatives(c, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != 0)
    return -1;
  return JNI_VERSION_1_6;
}
