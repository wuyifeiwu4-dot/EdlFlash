package com.edlflash.edl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * libedlsec 的 Kotlin 入口。敏感判定全在 native：
 *  - init: 把 APK 路径 + PackageManager 取到的签名证书 SHA-256 交给 native 做双路交叉校验，
 *    并由证书 hash 经 KDF 解出内嵌的 license secret（签名不符则解出错误 secret）。
 *  - verifyLicenseResponse: native 用 secret 重算 HMAC 验后台响应，命中即铸造授权会话。
 *  - gate: EDL 真实入口放行判定（实时复检签名/会话/Frida/调试）。
 *
 * native 方法名故意取 a/b/c/d（由 JNI_OnLoad 动态注册，逆向难以从符号还原语义）。
 */
object SecurityCore {

  @Volatile private var initialized = false

  init {
    runCatching { System.loadLibrary("edlsec") }
  }

  private external fun a(apkPath: String, pmCertHex: String): Int
  private external fun b(card: String, markcode: String, serverTime: Long, num: String, sign: String): Int
  private external fun c(): Long
  private external fun d(): Int
  private external fun e(plaintext: String): String?
  private external fun f(blob: String): String?
  private external fun g(): Long

  /**
   * DEX 字符串混淆密钥。解密下沉 native：StringVault 运行期取此 key 还原被 ASM 加密的
   * DEX 字符串；MT 管理器的 dex 字符串解密器无法执行 .so → 拿不到 key → 无法批量还原。
   * 访问本方法会触发 SecurityCore 类加载(init{} 内 loadLibrary→JNI_OnLoad 注册 g)，
   * 故 native 在此调用时必已就绪。
   */
  @JvmStatic
  fun stringKey(): Long = runCatching { g() }.getOrDefault(0L)

  /** 应用启动调用一次：建立签名绑定与 secret 解密。 */
  fun init(ctx: Context) {
    if (initialized) return
    val apk = runCatching { ctx.applicationInfo.sourceDir }.getOrNull() ?: ""
    val pmHex = pmCertSha256(ctx) ?: ""
    runCatching { a(apk, pmHex) }
    initialized = true
  }

  /** 后台卡密响应验签 + 铸造会话。命中返回 true。 */
  fun verifyLicenseResponse(card: String, markcode: String, serverTime: Long, num: String, sign: String): Boolean =
    runCatching { b(card, markcode, serverTime, num, sign) == 1 }.getOrDefault(false)

  /** EDL 操作放行：拿到非零 cookie 才允许执行刷机/读表等真实操作。 */
  fun gate(): Boolean = runCatching { c() != 0L }.getOrDefault(false)

  /** 抓包等弱信号风险分（仅提示，不硬封禁）。 */
  fun risk(): Int = runCatching { d() }.getOrDefault(0)

  /** 自定义流密码加密明文 → hex 包（抓包只见密文）。失败返回 null。 */
  fun pack(plaintext: String): String? = runCatching { e(plaintext) }.getOrNull()

  /** 解密 hex 包 → 明文。MAC 不符/环境异常返回 null。 */
  fun unpack(blob: String): String? = runCatching { f(blob) }.getOrNull()

  // PackageManager 取当前签名证书的 SHA-256(hex)。与 native 解析 APK 签名块、keytool 口径一致。
  private fun pmCertSha256(ctx: Context): String? = runCatching {
    val pm = ctx.packageManager
    val pkg = ctx.packageName
    val der: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
      info.signingInfo.apkContentsSigners[0].toByteArray()
    } else {
      @Suppress("DEPRECATION", "PackageManagerGetSignatures")
      val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
      @Suppress("DEPRECATION")
      info.signatures[0].toByteArray()
    }
    MessageDigest.getInstance("SHA-256").digest(der)
      .joinToString("") { "%02x".format(it) }
  }.getOrNull()
}
