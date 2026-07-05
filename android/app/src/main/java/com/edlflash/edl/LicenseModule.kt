package com.edlflash.edl

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 自建卡密/公告对接(替代微验)。与统一后台 backend/server.js 通信：
 *   POST {BACKEND}/api/card/verify  {card,markcode,t} → 卡密验证(设备绑定/时卡/次卡/到期)
 *   GET  {BACKEND}/api/announcement                    → 公告
 * 成功响应用 HMAC-SHA256(LICENSE_SECRET) 验签 + 时间偏差校验，防伪造放行。
 */
class LicenseModule(private val ctx: ReactApplicationContext) :
  ReactContextBaseJavaModule(ctx) {

  private val net = Executors.newSingleThreadExecutor()

  override fun getName() = "License"

  // 部署后台后填入其地址(不含末尾斜杠)，例如 "http://your-host:8787" 或 "https://api.example.com"。
  // 留空 → 无法验证(提示未配置)。
  private val backendUrl = "http://202.189.5.184:8787"
  // 加固：响应验签密钥不再以明文存于此（旧 licenseSecret 已下沉 native，且与签名证书绑定）。
  // 验签由 SecurityCore.verifyLicenseResponse 在 native 完成，签名被篡改即解不出正确密钥。
  private val maxSkewSec = 300L

  // ---- 设备码 / 卡密本地存储 ----
  private fun markcode(): String {
    val f = File(ctx.filesDir, ".edl_markcode")
    if (f.exists()) {
      val s = runCatching { f.readText().trim() }.getOrDefault("")
      if (s.isNotEmpty()) return s
    }
    val id = UUID.randomUUID().toString()
    runCatching { f.writeText(id) }
    return id
  }

  private fun cardFile() = File(ctx.filesDir, ".edl_card")
  private fun loadCard(): String =
    runCatching { cardFile().let { if (it.exists()) it.readText().trim() else "" } }.getOrDefault("")
  private fun saveCard(c: String) { runCatching { cardFile().writeText(c) } }

  // ---- HTTP ----
  private fun httpGet(path: String): String {
    val conn = URL(backendUrl + path).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "GET"
      conn.connectTimeout = 8000
      conn.readTimeout = 12000
      conn.useCaches = false
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      return String(stream?.readBytes() ?: ByteArray(0), Charsets.UTF_8)
    } finally {
      conn.disconnect()
    }
  }

  // 发送原始文本体（用于自定义加密协议：请求/响应都是 hex 密文包），返回原始响应文本。
  private fun httpPostRaw(path: String, body: String): String {
    val conn = URL(backendUrl + path).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 8000
      conn.readTimeout = 15000
      conn.doOutput = true
      conn.useCaches = false
      conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
      val payload = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(payload.size)
      conn.outputStream.use { it.write(payload); it.flush() }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      return String(stream?.readBytes() ?: ByteArray(0), Charsets.UTF_8)
    } finally {
      conn.disconnect()
    }
  }

  private fun fail(msg: String): WritableMap = Arguments.createMap().apply {
    putBoolean("ok", false); putString("message", msg)
  }

  // ---- RN 方法 ----
  @ReactMethod
  fun getMarkcode(promise: Promise) {
    net.execute { runCatching { promise.resolve(markcode()) }.onFailure { promise.resolve("") } }
  }

  @ReactMethod
  fun getSavedCard(promise: Promise) {
    net.execute { runCatching { promise.resolve(loadCard()) }.onFailure { promise.resolve("") } }
  }

  @ReactMethod
  fun clearSavedCard(promise: Promise) {
    net.execute { runCatching { cardFile().delete() }; promise.resolve(true) }
  }

  @ReactMethod
  fun getAnnouncement(promise: Promise) {
    net.execute {
      val res = Arguments.createMap()
      try {
        if (backendUrl.isEmpty()) {
          res.putBoolean("ok", false); res.putString("title", ""); res.putString("message", "")
          promise.resolve(res); return@execute
        }
        val obj = JSONObject(httpGet("/api/announcement"))
        val enabled = obj.optBoolean("enabled", false)
        val content = obj.optString("content", "")
        res.putBoolean("ok", true)
        res.putString("title", if (enabled) obj.optString("title", "") else "")
        res.putString("message", if (enabled) content else "")
      } catch (e: Exception) {
        res.putBoolean("ok", false); res.putString("title", ""); res.putString("message", "")
      }
      promise.resolve(res)
    }
  }

  @ReactMethod
  fun verify(cardInput: String, promise: Promise) {
    net.execute {
      try {
        if (backendUrl.isEmpty()) { promise.resolve(fail("后台地址未配置，请联系作者")); return@execute }
        val card = cardInput.trim().uppercase()
        if (card.isEmpty()) { promise.resolve(fail("卡密不能为空")); return@execute }
        val mark = markcode()
        val t = System.currentTimeMillis() / 1000L
        val reqBody = JSONObject().apply {
          put("card", card); put("markcode", mark); put("t", t)
        }.toString()
        // 加固：请求体用自定义流密码加密（抓包只见密文），密钥由签名绑定 secret 派生。
        val packed = SecurityCore.pack(reqBody)
          ?: run { promise.resolve(fail("环境异常，无法建立安全通道")); return@execute }
        val respRaw = httpPostRaw("/api/card/verify", packed)
        // 响应同为密文包，解密 + MAC 校验；不符即拒（防伪造/改包/中间人）。
        val respPlain = SecurityCore.unpack(respRaw)
          ?: run { promise.resolve(fail("响应解密失败(可能被篡改或抓包代理)")); return@execute }
        val obj = JSONObject(respPlain)

        if (!obj.optBoolean("ok", false) || obj.optInt("code", 0) != 1) {
          promise.resolve(fail(obj.optString("message", "卡密验证失败")))
          return@execute
        }
        val serverTime = obj.optLong("serverTime", 0L)
        if (kotlin.math.abs(serverTime - t) > maxSkewSec) {
          promise.resolve(fail("设备时间不准，请校正系统时间")); return@execute
        }
        val mode = obj.optString("mode", "")
        val num: String = if (mode == "time") obj.optLong("expiry", 0L).toString()
        else obj.optInt("remaining", 0).toString()
        // 加固：验签 + 铸造授权会话在 native 完成。密钥与签名证书绑定，重打包/被注入即不通过。
        if (!SecurityCore.verifyLicenseResponse(card, mark, serverTime, num, obj.optString("sign", ""))) {
          promise.resolve(fail("响应校验失败(签名不符或环境异常)")); return@execute
        }

        saveCard(card)
        val res = Arguments.createMap()
        res.putBoolean("ok", true)
        res.putString("card", card)
        res.putString("mode", mode)
        if (mode == "time") {
          res.putDouble("expiry", obj.optLong("expiry", 0L).toDouble())
        } else {
          res.putString("remaining", obj.optInt("remaining", 0).toString())
        }
        promise.resolve(res)
      } catch (e: Exception) {
        promise.resolve(fail("网络异常或响应无效: ${e.message ?: "未知错误"}"))
      }
    }
  }
}
