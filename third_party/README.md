# qdl 刷机核心（vendored）

EdlFlash 底层依赖的高通 EDL 刷写引擎源码。发布 APK 时由
[`scripts/build_android_qdl.sh`](../scripts/build_android_qdl.sh) 交叉编译成 aarch64 的
`qdl` 二进制，打进 app 的 `edl_bundle` 资产。放在这里是为了让刷机核心可随项目一起恢复。

## 构成

- `qdl/` — 引擎源码（Sahara/Firehose 协议、USB 9008、GPT、sparse、厂商授权），约 2.6 万行 C。
  - 主体源自 mainline [qdl](https://github.com/linux-msm/qdl)（Linaro/Linux Foundation）。
  - `oplus_token.c` / `oplus_vip.c` / `xiaomi_auth.h` 的一加/小米授权逻辑移植自
    [bkerler/edl](https://github.com/bkerler/edl) 的 `oneplus.py` / `xiaomi.py`。
  - 授权用的是逆向得到的**通用签名 blob（公开解锁签名）**，不含任何私钥。

## 许可

- `qdl/` 绝大多数文件为 **BSD-3-Clause**（见各文件 SPDX 头），`sparse.h` 为 Apache-2.0。
- 移植自 bkerler/edl 的授权部分源自 **GPLv3** 项目。若将来要公开分发，需按 GPLv3 处理这部分。

## 构建

```bash
NDK=/path/to/android-ndk scripts/build_android_qdl.sh --install
```

依赖 app `edl_bundle` 资产里现成的 libxml2 / libusb / libsodium / liblzma（aarch64 头文件与动态库）；
这些第三方库体积大且非本引擎源码，未纳入本仓库。

## 说明

高通官方 `fh_loader.c`（Copyright Qualcomm, All Rights Reserved）仅在开发时作协议对照参考、
不参与编译，因版权原因未纳入本仓库。
