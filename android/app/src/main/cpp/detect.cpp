/*
 * 运行期对抗检测：Frida、调试器、抓包/VPN。
 * 强信号（Frida 模块/线程、调试器附加）→ hard 命中，授权与刷机入口拒绝；
 * 弱信号（VPN 网卡）→ 仅计风险分，不单独封禁（公司 VPN 很常见，避免误杀）。
 * 所有特征字符串经 OBF 编译期加密，逆向无法直接 grep 出检测关键词。
 */
#include "obf.h"

#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

namespace {

// 在文件内容里搜任一关键词；分块读并保留重叠，命中跨块也不漏。
bool file_has_any(const char *path, const char *const *keys, int nkeys) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return false;
  char buf[4096];
  char tail[64];
  size_t tail_len = 0;
  bool hit = false;
  ssize_t n;
  while (!hit && (n = read(fd, buf, sizeof(buf) - 1)) > 0) {
    // 把上块尾部拼到本块前面，覆盖跨界关键词
    char work[4096 + 64];
    memcpy(work, tail, tail_len);
    memcpy(work + tail_len, buf, (size_t)n);
    size_t total = tail_len + (size_t)n;
    work[total] = 0;
    for (int i = 0; i < nkeys; i++) {
      if (strstr(work, keys[i])) { hit = true; break; }
    }
    tail_len = total > sizeof(tail) - 1 ? sizeof(tail) - 1 : total;
    memcpy(tail, work + total - tail_len, tail_len);
  }
  close(fd);
  return hit;
}

// 扫 /proc/self/maps 找 Frida/注入相关模块名（用具体关键词降误报）。
bool maps_has_frida() {
  auto k1 = OBF("frida");
  auto k2 = OBF("gum-js");
  auto k3 = OBF("linjector");
  const char *keys[] = {k1.c_str(), k2.c_str(), k3.c_str()};
  return file_has_any("/proc/self/maps", keys, 3);
}

// 扫 /proc/self/fd 的链接目标找 Frida 相关 fd（agent/gadget/注入管道）。
bool fd_has_frida() {
  DIR *d = opendir("/proc/self/fd");
  if (!d) return false;
  auto k1 = OBF("frida");
  auto k2 = OBF("linjector");
  auto k3 = OBF("gum-js");
  const char *keys[] = {k1.c_str(), k2.c_str(), k3.c_str()};
  struct dirent *e;
  bool hit = false;
  while (!hit && (e = readdir(d)) != nullptr) {
    if (e->d_name[0] == '.') continue;
    char path[64];
    int p = 0;
    const char *pre = "/proc/self/fd/";
    while (*pre) path[p++] = *pre++;
    const char *nm = e->d_name;
    while (*nm && p < 50) path[p++] = *nm++;
    path[p] = 0;
    char link[256];
    ssize_t n = readlink(path, link, sizeof(link) - 1);
    if (n <= 0) continue;
    link[n] = 0;
    for (int i = 0; i < 3; i++) {
      if (strstr(link, keys[i])) { hit = true; break; }
    }
  }
  closedir(d);
  return hit;
}

// 扫 /proc/net/unix 找 Frida 抽象域套接字（注入/控制通道）。
bool unix_socket_has_frida() {
  auto k1 = OBF("frida");
  auto k2 = OBF("linjector");
  auto k3 = OBF("gum-js");
  const char *keys[] = {k1.c_str(), k2.c_str(), k3.c_str()};
  return file_has_any("/proc/net/unix", keys, 3);
}

// 扫线程名 /proc/self/task/<tid>/comm 找 Frida 工作线程。
bool threads_have_frida() {
  DIR *d = opendir("/proc/self/task");
  if (!d) return false;
  auto k1 = OBF("gum-js-loop");
  auto k2 = OBF("gmain");
  auto k3 = OBF("gdbus");
  auto k4 = OBF("pool-frida");
  auto k5 = OBF("pool-spawner");
  const char *keys[] = {k1.c_str(), k2.c_str(), k3.c_str(), k4.c_str(), k5.c_str()};
  struct dirent *e;
  bool hit = false;
  while (!hit && (e = readdir(d)) != nullptr) {
    if (e->d_name[0] == '.') continue;
    char path[64];
    // /proc/self/task/<tid>/comm
    int p = 0;
    const char *pre = "/proc/self/task/";
    while (*pre) path[p++] = *pre++;
    const char *nm = e->d_name;
    while (*nm && p < 50) path[p++] = *nm++;
    const char *suf = "/comm";
    while (*suf) path[p++] = *suf++;
    path[p] = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) continue;
    char comm[64];
    ssize_t n = read(fd, comm, sizeof(comm) - 1);
    close(fd);
    if (n <= 0) continue;
    comm[n] = 0;
    for (int i = 0; i < 5; i++) {
      if (strstr(comm, keys[i])) { hit = true; break; }
    }
  }
  closedir(d);
  return hit;
}

// 探 Frida 默认控制端口（27042/27043）是否在监听。
bool frida_port_open() {
  const int ports[2] = {27042, 27043};
  for (int i = 0; i < 2; i++) {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0) continue;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)ports[i]);
    addr.sin_addr.s_addr = htonl(0x7f000001);  // 127.0.0.1
    int r = connect(s, (struct sockaddr *)&addr, sizeof(addr));
    close(s);
    if (r == 0) return true;
  }
  return false;
}

// /proc/self/status 的 TracerPid != 0 即被调试器附加。
bool tracer_attached() {
  int fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return false;
  char buf[2048];
  ssize_t n = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  if (n <= 0) return false;
  buf[n] = 0;
  auto tag = OBF("TracerPid:");
  const char *p = strstr(buf, tag.c_str());
  if (!p) return false;
  p += tag.size();
  while (*p == ' ' || *p == '\t') p++;
  return *p && *p != '0';
}

// ---- 内联 hook 自卫：快照关键 libc 函数序言，被 Frida Interceptor 改写即发现 ----
// Frida 的内联 hook 会把目标函数开头若干字节改写成跳板。启动时(尚未被 hook)快照，
// 之后比对，字节变了说明这些函数被 hook 了——这是高置信信号，几乎不会误报。
struct prologue_snap {
  const unsigned char *addr;
  unsigned char bytes[16];
  int valid;
};
prologue_snap g_snaps[6];

void hook_snapshot() {
  auto n0 = OBF("open");
  auto n1 = OBF("connect");
  auto n2 = OBF("read");
  auto n3 = OBF("ptrace");
  auto n4 = OBF("__system_property_get");
  auto n5 = OBF("dlsym");
  const char *names[6] = {n0.c_str(), n1.c_str(), n2.c_str(),
                          n3.c_str(), n4.c_str(), n5.c_str()};
  for (int i = 0; i < 6; i++) {
    void *a = dlsym(RTLD_DEFAULT, names[i]);
    g_snaps[i].addr = (const unsigned char *)a;
    g_snaps[i].valid = a ? 1 : 0;
    if (a) memcpy(g_snaps[i].bytes, a, 16);
  }
}

bool hook_tampered() {
  for (int i = 0; i < 6; i++) {
    if (!g_snaps[i].valid) continue;
    if (memcmp(g_snaps[i].addr, g_snaps[i].bytes, 16) != 0) return true;
  }
  return false;
}

// 扫 /proc/net/dev 的接口名找 tun/ppp/tap（VPN/抓包代理常见），弱信号。
// 用 /proc 而非 getifaddrs：后者需 API24+，本应用 minSdk 23。
bool vpn_iface_up() {
  auto t1 = OBF("tun");
  auto t2 = OBF("ppp");
  auto t3 = OBF("tap");
  const char *keys[] = {t1.c_str(), t2.c_str(), t3.c_str()};
  return file_has_any("/proc/net/dev", keys, 3);
}

}  // namespace

extern "C" {

// 启动早期(尚未被 hook)调用一次，建立内联 hook 自卫基线。
__attribute__((visibility("hidden")))
void edlsec_hook_snapshot() { hook_snapshot(); }

// 高置信硬信号：多向量 OR，任一命中即 1。这些都是 Frida/调试器很特征的痕迹，
// 误报极低，可据此拒绝/闪退。低置信的 rwx/VPN 不进这里（见 edlsec_detect_risk）。
__attribute__((visibility("hidden")))
int edlsec_detect_hard() {
  if (maps_has_frida()) return 1;       // 具名 frida/gum/linjector 模块
  if (threads_have_frida()) return 1;   // gum-js-loop/pool-frida 等线程
  if (fd_has_frida()) return 1;         // frida 相关 fd
  if (unix_socket_has_frida()) return 1; // frida 抽象套接字
  if (frida_port_open()) return 1;      // 27042/27043
  if (tracer_attached()) return 1;      // 调试器附加
  if (hook_tampered()) return 1;        // 关键 libc 被内联 hook
  return 0;
}

// 弱信号风险分：VPN 网卡 +1（抓包代理常经 VPN）。
__attribute__((visibility("hidden")))
int edlsec_detect_risk() {
  int r = 0;
  if (vpn_iface_up()) r++;
  return r;
}

}  // extern "C"
