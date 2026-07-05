# EdlFlash

高通 EDL（Emergency Download / 9008）模式刷机工具，Android 端。React Native 界面 +
原生 qdl 刷写引擎，无需 PC 与 QFIL，手机对手机即可完成救砖、刷机、切换分区。

## 架构

三层，职责自上而下：

```
React Native (src/)         界面、交互、流程编排、日志展示
        │  bridge
原生 Kotlin/Java (android/app/src/main/java/com/edlflash/)
        │  解析刷机包、构建 firehose XML、调度 qdl、授权/卡密/加固
原生 qdl 引擎 (外部资产 /root/edl_flash_app)
           Sahara / Firehose 协议、USB 9008 通信、厂商授权
```

- **界面层** `src/`：屏幕（Loader / Flash / Options / Tools / Settings）与组件，纯 TS。
- **桥接层** `android/app/src/main/java/com/edlflash/edl/`：
  - `FlashEngine.java` 刷写调度核心（包解析、GPT 处理、分区路由、授权触发）
  - `OpsDecryptor` / `OfpDecryptor` 一加 `.ops` / `.ofp` 官方包解密解包
  - `QualcommTables` 机型/projid 表，`LicenseModule` 卡密校验
- **引擎层** qdl（C）：本仓库不含其源码，构建期由 gradle 从外部资产目录打包进 APK。

## 支持的刷机包

QFIL（`rawprogram*.xml` + 镜像）、一加 `.ops` / `.ofp`、`settings.xml` 官方售后包。
含一加动态 token 授权（demacia / setprojmodel）与小米 EDL sig 鉴权。

## 构建

```bash
npm install
# 调试包
npm run android
# 发布 APK（需 android/app/debug.keystore，换证书会被 native 判定重打包而闪退）
cd android && ./gradlew assembleRelease
```

加固与整壳流程见 [HARDENING.md](HARDENING.md)。

## 目录

| 路径 | 说明 |
|---|---|
| `src/` | React Native 界面与状态 |
| `android/app/src/main/java/com/edlflash/edl/` | 刷机桥接层 |
| `android/app/src/main/cpp/` | native 加固核心 libedlsec（字符串加密/反调试/签名校验） |
| `android/buildSrc/` | 编译期 DEX 控制流混淆插件 |
| `backend/` | 卡密验证 + 公告后台（Node，零依赖）与 AstrBot 发卡插件 |

## 免责声明

刷机存在变砖风险，尤其是分区表、bootloader、授权相关操作。请确认机型与刷机包匹配，
了解 EDL 救砖手段后再操作。本工具仅供学习与授权范围内使用，使用者自负后果。
