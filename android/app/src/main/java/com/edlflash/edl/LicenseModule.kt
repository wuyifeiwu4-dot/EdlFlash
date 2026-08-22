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
 * 自建卡密/公告对接(替代微验)。
 * 修改说明：已去除卡密验证（verify 直接返回成功），不再联网校验。
 */
class LicenseModule(private val ctx: ReactApplicationContext) :
  ReactContextBaseJavaModule(ctx) {

  private val net = Executors.newSingleThreadExecutor()

  override fun getName() = "License"

  // 后台地址保留但不再使用
  private val backendUrl = "http://202.189.5.184:8787"
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

  // ---- HTTP（已不再调用，但保留以防其他地方使用） ----
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
    // ========== 修改：直接返回空公告，不联网拉取 ==========
    val res = Arguments.createMap()
    res.putBoolean("ok", true)
    res.putString("title", "")
    res.putString("message", "")
    promise.resolve(res)
    // ========== 修改结束 ==========
    // 原代码已注释：
    /*
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
    */
  }

  @ReactMethod
  fun verify(cardInput: String, promise: Promise) {
    // ========== 修改：直接返回成功，跳过所有验证 ==========
    net.execute {
      try {
        val card = cardInput.trim().uppercase()
        if (card.isEmpty()) {
          promise.resolve(fail("卡密不能为空"))
          return@execute
        }
        // 直接构造成功结果，不联网、不验签
        val res = Arguments.createMap()
        res.putBoolean("ok", true)
        res.putString("card", card)
        res.putString("mode", "time")      // 假装是时长卡
        res.putDouble("expiry", 9999999999.0) // 超长有效期
        // 顺便把卡密存下来（界面显示用）
        saveCard(card)
        promise.resolve(res)
      } catch (e: Exception) {
        promise.resolve(fail("内部错误: ${e.message}"))
      }
    }
    // ========== 修改结束 ==========
    // 原代码已注释（全部删除）
  }
}
