# EDL 源码集成说明

本项目不再只依赖单个不可维护的预编译 `qdl` 二进制。

## 已纳入仓库的源码

- `third_party/qdlrs`
  - 来自 `qualcomm/qdlrs`
  - 供 `rust/oplus_edl_cli` 继续演进使用
- `third_party/qdl`
  - 来自 `linux-msm/qdl`
  - 作为当前 Android OTG 主执行器源码

## 已补回的 qdl 能力

`third_party/qdl` 当前已补充并验证可构建的能力：

- `--no-sahara`
- `--sahara-only`
- `--signeddigests=<file>` 连续两次直发 `digest/sign`
- `--vip-signed`
- `--vip-chain`
- `--vip-partition`
- `QDL_USB_PATH`
- `QDL_AUTO_RESET`
- `QDL_FORCE_SAHARA`

另外，VIP 启动日志里如果目标请求 `.vip` 分区信息，`qdl` 会读取 `--vip-partition` 或 `EDL_VIP_PARTITION` 指向的文件并发送。
同时兼容当前 Android App 已有的 VIP 环境变量：

- `EDL_VIP_TRANSFER`
- `EDL_VIP_VERIFY`
- `EDL_VIP_SHA`
- `EDL_VIP_CFG`
- `EDL_VIP_PARTITION`
- `QDL_USB_PATH`
- `QDL_AUTO_RESET`

## Android 构建

手动构建：

```bash
scripts/build_android_qdl.sh --install
```

Gradle 构建时自动重建内置 `qdl`：

```bash
export EDL_BUILD_QDL=1
export JAVA_HOME=/root/tooling/jdk-17.0.10+7
./gradlew :app:assembleDebug
```

## 说明

- `app/src/main/assets/edl_bundle/bin/qdl` 现在可以由仓库内源码重建覆盖。
- `rust/oplus_edl_cli` 已改为依赖仓库内 vendored `qdlrs`，不再依赖外部绝对路径。
- `qdlrs` 的 Android USB 后端仍需单独适配，因此当前 APK 主链路仍以源码版 C `qdl` 为主。
- App 当前主链路已经统一为 `qdl`：
  - 新端口进入时走 `Sahara -> loader -> Firehose -> VIP 授权 -> XML/刷写`
  - 同一条已授权且未断开的会话内，后续命令改为复用现有 Firehose/VIP 会话，走 `--no-sahara`，不会重复发送 `digest/sign`
  - 端口断开后会清空 VIP 会话状态，避免把旧授权误用到新的 9008 会话
