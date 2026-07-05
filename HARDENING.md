# EdlFlash 加固说明

多层防护，从内到外：**native 强校验 → 编译期混淆 → DEX 整壳**。绝对不可破解不存在，
目标是把破解成本拉到远高于收益。

## 产物（dist/）
| APK | 说明 | 风险 |
|---|---|---|
| `edlflash-hardened.apk` | 字符串加密 + 控制流混淆 + native 签名强校验/反调试/Frida/抓包检测 + 篡改闪退 | 已验证，可直接发布 |
| `edlflash-shelled.apk` | 在上面基础上再用 dpt-shell 整壳（DEX 方法体抽取加密） | **须真机自测启动**，dpt 作者声明未经大量测试 |

两包均用 `android/app/debug.keystore` 签名（证书 SHA-256 `fac61745...`）。
**换签名证书会被 native 判定为重打包 → 闪退**（见下）。

## 一、native 安全核心 `android/app/src/main/cpp/`（libedlsec.so）
- **签名"非布尔"强校验**：构建期 Gradle 任务读签名证书 SHA-256，经 4096 轮 KDF 派生密钥
  把 license secret 加密内嵌；运行期用**实际**证书 hash（APK 签名块独立解析 + PackageManager
  双路严格一致）解密。签名变→解出错误 secret→后台卡密验签必败，无原私钥不可修复。
- **篡改即闪退**：positively 解析出的证书 != 期望（=确定重打包）→ 延迟、多点、native 写非法
  地址触发 SIGSEGV（非 abort 字样，难定位/patch）。**解析失败不崩**（避免误杀异常环境）。
- **授权下沉**：卡密验签 + 会话铸造在 native；EDL 真实入口(runOp/decryptOfp/Ops)前经 gate
  实时复检签名/会话/Frida/调试，mini-VM 出 cookie 才放行。JS hook verify 无法凭空铸造会话。
- **检测**：Frida(maps/线程名/端口)、反调试(TracerPid)、抓包(VPN 网卡)；强信号硬拒、弱信号风险分。
- 字符串编译期 OBF 加密；仅导出 JNI_OnLoad，其余符号隐藏 + strip。

## 二、编译期混淆 `android/buildSrc/`（ASM 插桩，R8 之前）
对 `com.edlflash.edl.**`（不碰 RN 框架）：
- **字符串加密**：String 常量替换为密文 + 运行期 `StringVault.d` 解密（最终 DEX 实测 2685 处，
  后台 URL/密钥/错误串/env 名全部从 DEX 消失）。
- **控制流混淆**：每个非构造方法插入 `(x*x-x)%2` 不透明谓词守护的 bogus throw 死分支。
- R8 全模式混淆业务类名。

## 三、DEX 整壳 dpt-shell（已魔改，可选最外层）
`edlflash-shelled.apk` 把我方业务类方法指令抽空加密进 assets、运行期填回，静态 jadx 只见空方法体。
排除 RN 框架 + 启动关键类（见 `scripts/dpt-exclude.rules`）以保启动。

**已对开源 dpt-shell 大幅魔改**（避免被通用脱壳器/懂 dpt 的人直接破）：
- **insns 加密算法**：原版是可识别的 4 字节重复 XOR（`enc[i]^((key>>((i&3)<<3))&0xff)`），
  已换成**位置相关非线性 LCG keystream**（内嵌私有常量 `0x7A1C9E37`），打包端
  `dpt/.../DexUtils.java` 与壳端 `shell/.../dpt_hook.cpp` 逐字节互逆（已 Java/C 交叉验证一致）。
  通用 dpt 脱壳器按 XOR 解必得乱码。
- **固定容器名**：`i11111i111.zip`/`d_shell_data_001`/`OoooooOooo`/`vwwwwwvwww`
  → `r9k2m7.zip`/`c4v8q1d3`/`m6w0z3a7`/`t3n7h5b9`（打包/壳native/壳Java 三方同步），
  破坏基于固定名的 dpt 识别与定位。
- 魔改后须重建 `dpt.jar` + 壳 `.so`（两端配对，缺一端会还原失败）。源码在 `/root/dpt-shell`。

### 重新打壳（任何 release 重建后）
```bash
cd android && ./gradlew :app:assembleRelease   # 产 edlflash-hardened 内容
cd .. && bash scripts/repack-shell.sh          # 打壳 + debug.keystore 重签 → dist/edlflash-shelled.apk
```
dpt-shell 首次需构建（已在 /root/dpt-shell）：见其 README，注意把 `cmakeVersion` 与
`cmake_minimum_required` 降到 SDK 自带的 3.22.1。

## 四、数据包应用层加密（自写 ARX 流密码，反抓包）
`/api/card/verify` 的请求体与响应体用**自写流密码**加密（非 AES/标准算法）：
- `cpp/edlcipher.c`（native）与 `backend/edlcipher.js`（后台）**逐字节一致**的 ARX 流密码
  （自定义常量 `edlf/lash/v2x0`、自定义轮转 15/11/8/7、20 轮）+ 自定义 keyed MAC（乘-转-异或）。
- 密钥 CK/MK 由签名绑定的 license secret 派生（`edl_kdf`）。抓包(Charles/Fiddler)只见 hex 密文，
  改包 → MAC 失败被拒，重放 → 时间戳超差被拒。内层仍保留 HMAC-SHA256 签名（纵深）。
- 包格式：`hex( nonce(12) || tag(8) || ciphertext )`。native 经 JNI `e`/`f`(pack/unpack)，
  Kotlin `SecurityCore.pack/unpack`，`LicenseModule.verify` 用之。
- 已端到端验证：native C ↔ 后台 JS 密钥流/密文/tag 逐字节一致；真实 server.js 加密往返通过。
- **部署须同步**：`backend/server.js` + `backend/edlcipher.js` 重新部署，且 `LICENSE_SECRET`
  与 App 一致（默认 `edlflash-license-7f3a9c21d8e64b05`，构建 `-PedlsecLicenseSecret=` 可改）。
  新 App 与旧后台不兼容，必须同时上线。

## 后台密钥
卡密响应签名密钥默认 `edlflash-license-7f3a9c21d8e64b05`（须与 backend `LICENSE_SECRET` 一致）。
改动时构建传 `-PedlsecLicenseSecret=新值`（≤120 字节），Gradle 会重新加密内嵌。

## 残余风险（务必知悉）
- `gate()` 仍是判 cookie 非零；进一步可让 EDL 引擎消费 cookie 派生值（需改 FlashEngine，回归风险高）。
- 后台 HTTP 无 pinning；建议改 HTTPS + SPKI pinning。
- release 仍用 debug.keystore；换生产 keystore 后系统自动重新绑定（重建即可）。
- **壳化包必须真机验证可正常启动/登录/刷机**；若异常，回退 `edlflash-hardened.apk`。
