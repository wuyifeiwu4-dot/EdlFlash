package com.edlflash.edl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * RN 桥接：把忠实移植的 FlashEngine（完整 EDL 引擎）暴露给 JS。
 * 文件/目录选择走真实 SAF；命令执行完才 resolve（使 busy/stop/结果回显正确）。
 */
class EdlFlashModule(private val reactCtx: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactCtx), EdlCallback, ActivityEventListener, LifecycleEventListener {

  // 命令与资源解包串行队列：runOp 刷写命令 + ensureAssets（解包须先于命令，保持同队 FIFO）。
  private val io = Executors.newSingleThreadExecutor()
  // 后台任务队列：root 探测、设备监视启动、SAF 文件拷贝、解密、内置 loader 枚举。
  // 与命令队列分开，避免启动期 root 授权弹窗(su 阻塞)卡死刷写命令。
  private val backgroundExecutor = Executors.newSingleThreadExecutor()
  private val engine = FlashEngine(reactCtx, this)
  private val monitoring = AtomicBoolean(false)
  // 桥线程门口占用：复刻原生"同步 CAS 拒绝"，在入队 io 之前即拒第二次派发，杜绝命令排队重复执行。
  private val dispatching = AtomicBoolean(false)
  // 监视代号：每次 start/stop 自增，旧线程下一轮见代号不符即退出，杜绝 stop→start 竞态泄漏。
  private val monitorGen = AtomicInteger(0)
  // JS 侧开关意图：onHostPause 暂停监视、onHostResume 据此恢复，对齐原生 onResume/onPause。
  private val monitorWanted = AtomicBoolean(false)
  @Volatile private var monitorThread: Thread? = null
  @Volatile private var monitorInterval = 1000L
  @Volatile private var confirmLatch: CountDownLatch? = null
  @Volatile private var confirmResult = EdlCallback.CONFIRM_CANCEL

  // SAF 选择：请求码 + 待决 promise + 类型
  @Volatile private var pickPromise: Promise? = null
  @Volatile private var pickKind = 0 // 1=file 2=files 3=dir
  private val REQ_PICK = 0xED10

  init {
    reactCtx.addActivityEventListener(this)
    reactCtx.addLifecycleEventListener(this)
  }

  override fun getName() = "EdlFlash"

  private fun emit(event: String, params: WritableMap) {
    // bridge 失活期（dev reload / catalyst 重建）getJSModule 会抛异常，先判活再发。
    if (!reactCtx.hasActiveReactInstance()) {
      return
    }
    reactCtx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(event, params)
  }

  // ---- EdlCallback 实现 ----
  override fun onLog(line: String?) {
    if (line == null) {
      emit("onLog", Arguments.createMap().apply { putBoolean("clear", true) })
      return
    }
    // 关键词集对齐原生 resolveLogColor：先判成功(绿)再判失败(红)，warn 为本端附加级置于其后。
    val lower = line.trim().lowercase()
    val level = when {
      lower.contains("...ok") || lower.endsWith(" ok") || lower == "ok" ||
        lower.contains("success") || line.contains("成功") || line.contains("完成") ||
        line.contains("已保存") || lower == "ack" -> "success"
      lower.contains("error") || lower.contains("failed") || lower.contains("nak") ||
        line.contains("失败") || line.contains("错误") -> "error"
      line.contains("警告") || lower.contains("warn") -> "warn"
      else -> "info"
    }
    val arr = Arguments.createArray().apply {
      pushMap(Arguments.createMap().apply { putString("text", line); putString("level", level) })
    }
    emit("onLog", Arguments.createMap().apply { putArray("lines", arr); putInt("session", 0) })
  }

  override fun onProgress(percent: Int, label: String?) {
    emit("onProgress", Arguments.createMap().apply {
      putInt("percent", percent)
      putBoolean("indeterminate", percent < 0)
      label?.let { putString("label", it) }
    })
  }

  override fun onDeviceStatus(status: String?) {
    status?.let { onLog(it) }
  }

  override fun onPartitions(list: MutableList<PartitionOption>?) {
    val arr = Arguments.createArray()
    list?.forEach { p ->
      arr.pushMap(Arguments.createMap().apply {
        putString("name", p.name); putString("lun", p.lun)
        p.startSector?.let { putString("startSector", it) }
        p.numSectors?.let { putString("numSectors", it) }
        p.sectorSize?.let { putString("sectorSize", it) }
      })
    }
    emit("onPartitions", Arguments.createMap().apply { putArray("entries", arr) })
  }

  override fun onToast(msg: String?) {
    msg?.let { onLog(it) }
  }

  override fun onConfirmPersist(warning: String?): Int {
    val latch = CountDownLatch(1)
    confirmLatch = latch
    // 默认取消：超时/桥停/中断等未明确确认场景一律中止，对齐原生 promptPersistDecision（无应答=不刷）。
    confirmResult = EdlCallback.CONFIRM_CANCEL
    emit("onConfirmPersist", Arguments.createMap().apply { putString("warning", warning ?: "") })
    try {
      // 超时不再按 SKIP 放行；超时即落 CANCEL 整体放弃。
      if (!latch.await(180, TimeUnit.SECONDS)) {
        return EdlCallback.CONFIRM_CANCEL
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return EdlCallback.CONFIRM_CANCEL
    }
    return confirmResult
  }

  @ReactMethod fun addListener(eventName: String) {}
  @ReactMethod fun removeListeners(count: Int) {}

  @ReactMethod
  fun resolveConfirm(decision: Int, promise: Promise) {
    confirmResult = decision
    confirmLatch?.countDown()
    promise.resolve(null)
  }

  // ---- SAF 文件/目录选择 ----
  @ReactMethod
  fun pickFile(promise: Promise) = launchPick(1, promise)

  @ReactMethod
  fun pickMultiple(promise: Promise) = launchPick(2, promise)

  @ReactMethod
  fun pickDirectory(promise: Promise) = launchPick(3, promise)

  private fun launchPick(kind: Int, promise: Promise) {
    val act = currentActivity
    if (act == null) {
      promise.reject("no_activity", "界面未就绪")
      return
    }
    if (pickPromise != null) {
      promise.reject("busy", "已有选择进行中")
      return
    }
    pickPromise = promise
    pickKind = kind
    val intent = if (kind == 3) {
      Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    } else {
      Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (kind == 2) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      }
    }
    try {
      act.startActivityForResult(intent, REQ_PICK)
    } catch (e: Exception) {
      pickPromise = null
      promise.reject("launch", e.message, e)
    }
  }

  override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != REQ_PICK) return
    val promise = pickPromise ?: return
    pickPromise = null
    if (resultCode != Activity.RESULT_OK || data == null) {
      promise.resolve(null) // 用户取消 → null，UI 保持“未选择”
      return
    }
    backgroundExecutor.execute {
      try {
        when (pickKind) {
          2 -> {
            val arr = Arguments.createArray()
            val clip = data.clipData
            val uris = when {
              clip != null -> (0 until clip.itemCount).map { clip.getItemAt(it).uri }
              data.data != null -> listOf(data.data!!)
              else -> emptyList()
            }
            uris.forEach { u ->
              try {
                val f = resolvePicked(u, false)
                arr.pushMap(Arguments.createMap().apply { putString("path", f.first); putString("name", f.second) })
              } catch (e: Exception) {
                // 单项无法定位真实路径只跳过该项，不连累已成功解析的其余文件
                onLog("跳过无法定位的文件: ${e.message}")
              }
            }
            promise.resolve(arr)
          }
          3 -> {
            val f = resolvePicked(data.data!!, true)
            promise.resolve(Arguments.createMap().apply { putString("path", f.first); putString("name", f.second) })
          }
          else -> {
            val f = resolvePicked(data.data!!, false)
            promise.resolve(Arguments.createMap().apply { putString("path", f.first); putString("name", f.second) })
          }
        }
      } catch (e: Exception) {
        // 无法定位真实路径等错误：记日志并按“未选择”返回，避免未处理的 promise rejection。
        onLog("选择失败: ${e.message}")
        promise.resolve(null)
      }
    }
  }

  override fun onNewIntent(intent: Intent?) {}

  // ---- LifecycleEventListener：对齐原生 onResume/onPause/onDestroy 的确定性轮询启停 ----
  override fun onHostResume() {
    if (monitorWanted.get()) {
      launchMonitor()
    }
  }

  override fun onHostPause() {
    // 切后台即停轮询（保留 JS 意图），避免后台持续 root sysfs 探测耗电。
    haltMonitor()
  }

  override fun onHostDestroy() {
    monitorWanted.set(false)
    haltMonitor()
  }

  override fun invalidate() {
    monitorWanted.set(false)
    haltMonitor()
    // 对齐原生 onDestroy：先放行可能卡在确认握手上的 io 线程，再 engine.shutdown 全量回收
    // （取消在途 qdl 释放 USB + 停引擎内部执行器 + 关常驻 root shell），最后停桥接两条队列。
    confirmResult = EdlCallback.CONFIRM_CANCEL
    confirmLatch?.countDown()
    try { engine.shutdown() } catch (t: Throwable) {}
    io.shutdownNow()
    backgroundExecutor.shutdownNow()
    super.invalidate()
  }

  /**
   * 解析所选项的真实文件系统路径。刷写全程以 root 执行，可直读任意真实路径，
   * 因此一律不复制到私有目录（避免把数 GB 固件搬进 cache）。
   * 顺序：document-id 还原(externalstorage/raw) → 文件再查 _data 列 → 仍无则报错引导用户。
   */
  private fun resolvePicked(uri: Uri, isTree: Boolean): Pair<String, String> {
    var real = resolveRealPath(uri, isTree)
    if (real.isNullOrEmpty() && !isTree) {
      // externalstorage/raw 之外的 provider(Downloads/Media 等)再查 _data 列拿真实路径
      real = queryDataColumn(uri)
    }
    if (real.isNullOrEmpty()) {
      throw IllegalStateException("无法定位所选项的真实路径，请用文件管理器选择实际文件/目录(勿用最近/下载等虚拟来源)")
    }
    return real to File(real).name
  }

  // 查询 content URI 的 _data 列(真实路径)，供 root 直读；取不到返回 null。
  private fun queryDataColumn(uri: Uri): String? {
    return try {
      reactCtx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
        val idx = c.getColumnIndex("_data")
        if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.ifEmpty { null } else null
      }
    } catch (e: Exception) {
      null
    }
  }

  // 移植自原版 resolvePathFromUri/resolveDocumentPath：把 SAF URI 还原成本机真实路径
  private fun resolveRealPath(uri: Uri, isTree: Boolean): String? {
    if ("file".equals(uri.scheme, true)) {
      return uri.path
    }
    return try {
      if (DocumentsContract.isDocumentUri(reactCtx, uri)) {
        val docId = if (isTree) DocumentsContract.getTreeDocumentId(uri)
        else DocumentsContract.getDocumentId(uri)
        resolveDocumentPath(uri.authority, docId)
      } else if (Build.VERSION.SDK_INT >= 24 && DocumentsContract.isTreeUri(uri)) {
        resolveDocumentPath(uri.authority, DocumentsContract.getTreeDocumentId(uri))
      } else {
        null
      }
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  private fun resolveDocumentPath(authority: String?, docId: String?): String? {
    if (docId.isNullOrEmpty()) return null
    if (docId.startsWith("raw:")) return docId.substring(4)
    if (authority != "com.android.externalstorage.documents") return null
    val split = docId.indexOf(':')
    if (split <= 0) return null
    val volume = docId.substring(0, split)
    val relative = docId.substring(split + 1)
    val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath
    else "/storage/$volume"
    return if (relative.isEmpty()) base else "$base/$relative"
  }

  // ---- 选项持久化（SharedPreferences；瞬态项由 JS 侧不传即可）----
  @ReactMethod
  fun saveOptions(json: String, promise: Promise) {
    reactCtx.getSharedPreferences("edl_ui", Context.MODE_PRIVATE).edit().putString("options", json).apply()
    promise.resolve(null)
  }

  @ReactMethod
  fun loadOptions(promise: Promise) {
    val json = reactCtx.getSharedPreferences("edl_ui", Context.MODE_PRIVATE).getString("options", "{}")
    promise.resolve(json)
  }

  // ---- 初始化 / 状态 ----
  @ReactMethod
  fun ensureAssetsReady(promise: Promise) {
    io.execute {
      try {
        engine.ensureAssets()
        promise.resolve(Arguments.createMap().apply {
          putString("version", engine.assetVersion()); putBoolean("ready", true)
        })
      } catch (e: Exception) {
        promise.reject("assets", e.message, e)
      }
    }
  }

  @ReactMethod
  fun getRootStatus(promise: Promise) {
    backgroundExecutor.execute {
      engine.refreshRootStatus()
      val ok = engine.isRootAvailable()
      promise.resolve(Arguments.createMap().apply {
        putBoolean("available", ok); putString("status", if (ok) "granted" else "denied")
      })
    }
  }

  @ReactMethod
  fun startDeviceMonitor(intervalMs: Double, promise: Promise) {
    monitorInterval = intervalMs.toLong().coerceAtLeast(800)
    monitorWanted.set(true)
    launchMonitor()
    promise.resolve(null)
  }

  @ReactMethod
  fun stopDeviceMonitor(promise: Promise) {
    monitorWanted.set(false)
    haltMonitor()
    promise.resolve(null)
  }

  // 起单实例监视线程：带代号守卫，任一时刻仅最后一次启动的线程存活；循环体整体兜底，
  // 单轮探测/派发异常不终止线程，避免监视永久死亡且 monitoring 卡 true 无法重启。
  private fun launchMonitor() {
    if (!monitoring.compareAndSet(false, true)) {
      return
    }
    val gen = monitorGen.incrementAndGet()
    backgroundExecutor.execute { engine.refreshRootStatus() }
    monitorThread = Thread {
      try {
        while (monitoring.get() && gen == monitorGen.get()) {
          try {
            val (connected, usbPath, vidPid) = scanDevice()
            emit("onDeviceStatus", Arguments.createMap().apply {
              putBoolean("connected", connected)
              usbPath?.let { putString("usbPath", it) }
              vidPid?.let { putString("vidPid", it) }
              putBoolean("rootAvailable", engine.isRootAvailable())
              putBoolean("vip", engine.isVipAuthorized())
            })
          } catch (t: Throwable) {
            // 单轮探测/派发失败不终止监视，下一轮继续。
          }
          try {
            Thread.sleep(monitorInterval)
          } catch (e: InterruptedException) {
            break
          }
        }
      } finally {
        // 仅当自己仍是当前代(无更新线程接管)时才释放占用：
        // 否则 stop→start 竞态下被 interrupt 的旧线程会把新线程刚 CAS 占用的 monitoring 改回 false，致新监视线程自杀、轮询永停。
        if (gen == monitorGen.get()) {
          monitoring.set(false)
        }
      }
    }.apply { isDaemon = true }.also { it.start() }
  }

  // 停监视：置标志、bump 代号并唤醒沉睡的线程，确保旧线程立即退出不残留。
  private fun haltMonitor() {
    monitoring.set(false)
    monitorGen.incrementAndGet()
    monitorThread?.interrupt()
    monitorThread = null
  }

  private fun scanDevice(): Triple<Boolean, String?, String?> {
    // 委托引擎 headless 4 级检测链（UsbManager→sysfs→root sysfs→debug，含 9008→900e 回退）；
    // vid/pid 取用户已持久化的 options，留空则引擎回退默认 05c6/9008。
    val (vid, pid) = savedPortId()
    val probe = engine.pollEdlDevice(vid, pid)
    return Triple(probe.connected, probe.usbPath, probe.vidPid)
  }

  // 读取 saveOptions 写入的 options JSON，取出 vid/pid（与 buildInput 的 options 同源）。
  private fun savedPortId(): Pair<String?, String?> {
    val json = reactCtx.getSharedPreferences("edl_ui", Context.MODE_PRIVATE).getString("options", null)
      ?: return null to null
    return try {
      val o = JSONObject(json)
      o.optString("vid").ifEmpty { null } to o.optString("pid").ifEmpty { null }
    } catch (e: Exception) {
      null to null
    }
  }

  // ---- 刷写命令：执行完才 resolve；busy 全程维持，stop 可中断 ----
  private fun runOp(spec: ReadableMap, promise: Promise, op: (FlashEngine) -> Boolean) {
    // 桥线程同步占用判定：已有命令在跑/在队即拒，绝不二次入队（对齐原生门口 CAS），杜绝破坏性命令背靠背重刷。
    if (!dispatching.compareAndSet(false, true)) {
      promise.resolve(Arguments.createMap().apply {
        putBoolean("ok", false)
        putBoolean("started", false)
        putBoolean("vipAuthorized", engine.isVipAuthorized())
      })
      return
    }
    try {
      io.execute {
        try {
          // 加固：真实 EDL 操作必须经 native 能力门控（会话由签名绑定的卡密校验铸造，
          // JS 层 hook 无法凭空伪造）。未授权/被注入/被调试时拒绝执行（dispatching 由 finally 复位）。
          if (!SecurityCore.gate()) {
            promise.resolve(Arguments.createMap().apply {
              putBoolean("ok", false)
              putBoolean("started", false)
              putBoolean("vipAuthorized", engine.isVipAuthorized())
            })
            return@execute
          }
          engine.applyInput(buildInput(spec))
          val started = op(engine)
          promise.resolve(Arguments.createMap().apply {
            putBoolean("ok", started && engine.lastCommandSucceeded())
            putBoolean("started", started)
            putBoolean("vipAuthorized", engine.isVipAuthorized())
          })
        } catch (e: Exception) {
          onLog("执行失败: ${e.message}")
          promise.reject("run", e.message, e)
        } finally {
          dispatching.set(false)
        }
      }
    } catch (e: RejectedExecutionException) {
      // io 已关停（invalidate 销毁中）：复位门口锁并按与 CAS 拒绝相同形状 resolve，
      // 避免 dispatching 永久锁死 + JS await 悬挂导致 busy 卡死。
      dispatching.set(false)
      promise.resolve(Arguments.createMap().apply {
        putBoolean("ok", false)
        putBoolean("started", false)
        putBoolean("vipAuthorized", engine.isVipAuthorized())
      })
    }
  }

  @ReactMethod fun run(spec: ReadableMap, promise: Promise) = runOp(spec, promise) { it.runCommand() }
  @ReactMethod fun readPartitionTable(spec: ReadableMap, promise: Promise) = runOp(spec, promise) { it.readPartitionTable() }
  @ReactMethod fun vipAuth(spec: ReadableMap, promise: Promise) = runOp(spec, promise) { it.vipAuth() }
  @ReactMethod fun sign(spec: ReadableMap, promise: Promise) = runOp(spec, promise) { it.sign() }
  @ReactMethod fun multiRead(spec: ReadableMap, promise: Promise) = runOp(spec, promise) { it.runMultiRead() }

  // QFIL 待刷分区预览：解析 rawprogram*.xml 返回结构化分区 JSON({xmls:[{name,partitions:[...]}]})，
  // 供前端列出已解析 XML 与各自待刷分区并勾选裁剪。只读解析，忙时返回空让前端回退自动识别。
  @ReactMethod
  fun parseQfilInputs(spec: ReadableMap, promise: Promise) {
    if (!dispatching.compareAndSet(false, true)) {
      promise.resolve("{\"xmls\":[]}")
      return
    }
    try {
      io.execute {
        try {
          engine.applyInput(buildInput(spec))
          promise.resolve(engine.parseQfilPreviewJson())
        } catch (e: Exception) {
          promise.reject("qfil_parse", e.message, e)
        } finally {
          dispatching.set(false)
        }
      }
    } catch (e: RejectedExecutionException) {
      dispatching.set(false)
      promise.resolve("{\"xmls\":[]}")
    }
  }

  @ReactMethod
  fun stop(promise: Promise) {
    // 若命令正卡在 persist 确认握手上：先按取消放行 confirmLatch，使 io 线程立即解阻，Stop 才能即时落地。
    confirmResult = EdlCallback.CONFIRM_CANCEL
    confirmLatch?.countDown()
    engine.cancel() // 直接在桥线程同步取消，勿排进 io（否则饿死在运行命令之后）
    promise.resolve(null)
  }

  // ---- JS run-spec → EdlInput ----
  private fun buildInput(spec: ReadableMap): EdlInput {
    val input = EdlInput()
    input.command = sStr(spec, "command", "gpt")
    spec.mapOrNull("args")?.let {
      input.arg1 = sStr(it, "arg1"); input.arg2 = sStr(it, "arg2"); input.arg3 = sStr(it, "arg3")
      input.arg1Path = sStr(it, "arg1Path").ifEmpty { null }
      input.arg2Path = sStr(it, "arg2Path").ifEmpty { null }
      input.arg3Path = sStr(it, "arg3Path").ifEmpty { null }
      input.arg1Paths = strList(it.arrOrNull("arg1Paths"))
      input.arg2Paths = strList(it.arrOrNull("arg2Paths"))
    }
    input.outputName = sStr(spec, "outputName")
    input.edlPackagePath = sStr(spec, "edlPackagePath").ifEmpty { null }

    spec.mapOrNull("loader")?.let { loader ->
      loader.mapOrNull("builtin")?.let { b ->
        input.builtinSelected = true
        input.builtinVendorDir = sStr(b, "vendorDir").ifEmpty { null }
        input.builtinChip = sStr(b, "chip").ifEmpty { null }
        // 空串归一为 null：仅选品牌+芯片未手选引导文件时，引擎走 pickDevprgFile 自动优选首选 loader，
        // 不再把目录路径当已选 asset 导致刷写期 IOException 被静默吞。
        input.builtinDevprgFileName = sStr(b, "devprgAsset").ifEmpty { null }
      }
      if (loader.hasKey("path")) input.loaderPath = sStr(loader, "path").ifEmpty { null }
    }
    spec.mapOrNull("vip")?.let {
      input.digestPath = sStr(it, "digestPath").ifEmpty { null }
      input.signPath = sStr(it, "signPath").ifEmpty { null }
    }
    spec.mapOrNull("partitions")?.let {
      input.selectedPartition = sStr(it, "selected").ifEmpty { null }
      if (it.hasKey("lun") && sStr(it, "lun").isNotEmpty()) input.lun = sStr(it, "lun")
      input.multiReadPartitions = strList(it.arrOrNull("multiRead"))
    }

    spec.mapOrNull("options")?.let { o ->
      input.memory = sStr(o, "memory", input.memory)
      // 分区选择优先：选中分区时其 lun 已写入 input.lun，选项页『默认 LUN』仅在未选分区时生效，
      // 避免多 LUN 设备上残留的默认 LUN 覆盖所选分区、按名兜底命中错误物理分区。
      if (input.selectedPartition.isNullOrEmpty() && sStr(o, "lun").isNotEmpty()) input.lun = sStr(o, "lun")
      input.maxPayload = sStr(o, "maxPayload")
      input.sectorSize = sStr(o, "sectorSize")
      input.vid = sStr(o, "vid")
      input.pid = sStr(o, "pid")
      input.port = sStr(o, "port")
      input.suCommand = sStr(o, "suCommand")
      input.deviceModel = sStr(o, "deviceModel")
      input.oplusTokenAuth = sBool(o, "oplusTokenAuth")
      input.oplusSerial = sStr(o, "oplusSerial")
      input.tcpPort = sStr(o, "tcpPort")
      input.rawXml = sStr(o, "rawXml")
      input.resetMode = sStr(o, "resetMode")
      input.partitions = sStr(o, "partitions")
      input.skipPartitions = sStr(o, "skip")
      input.signPartitions = sStr(o, "signPartitions")
      input.signInputDir = sStr(o, "signDir").ifEmpty { null }
      input.signKeyPath = sStr(o, "signKey").ifEmpty { null }
      input.signOutputDir = sStr(o, "signOutputDir").ifEmpty { null }
      input.fastMode = sBool(o, "fastMode")
      input.autoReboot = sBool(o, "autoReboot")
      input.skipStorageInit = sBool(o, "skipStorageInit")
      input.signChain = sBool(o, "signChain")
      input.signVerify = sBool(o, "signVerify")
      input.signRegenSalt = sBool(o, "signRegenSalt")
      input.qfilSplit = sBool(o, "qfilSplit")
      input.protectLun5 = sBool(o, "protectLun5")
      // 缺键默认开，保持『缺 super.img 时自动合并』的原行为
      input.mergeSuper = if (o.hasKey("mergeSuper")) sBool(o, "mergeSuper") else true
      input.dryRun = sBool(o, "skipWrite")
    }
    input.qfilSkip = strList(spec.arrOrNull("qfilSkip"))
    return input
  }

  private fun ReadableMap.mapOrNull(k: String): ReadableMap? =
    if (hasKey(k) && !isNull(k)) getMap(k) else null
  private fun ReadableMap.arrOrNull(k: String): ReadableArray? =
    if (hasKey(k) && !isNull(k)) getArray(k) else null
  private fun sStr(m: ReadableMap, k: String, def: String = ""): String =
    if (m.hasKey(k) && !m.isNull(k)) (m.getString(k) ?: def) else def
  private fun sBool(m: ReadableMap, k: String): Boolean =
    m.hasKey(k) && !m.isNull(k) && m.getBoolean(k)
  private fun strList(a: ReadableArray?): MutableList<String>? {
    if (a == null) return null
    val out = ArrayList<String>()
    for (i in 0 until a.size()) a.getString(i)?.let { out.add(it) }
    return out
  }

  // ---- 解密 ----
  @ReactMethod
  fun decryptOfp(srcPath: String, outDir: String, promise: Promise) {
    backgroundExecutor.execute {
      try {
        if (!SecurityCore.gate()) { promise.reject("decrypt_ofp", "未授权或环境异常"); return@execute }
        val out = OfpDecryptor(OfpDecryptor.Logger { onLog(it) }).decrypt(srcPath, File(outDir).apply { mkdirs() }.absolutePath)
        promise.resolve(Arguments.createMap().apply { putString("outDir", out) })
      } catch (e: Exception) { promise.reject("decrypt_ofp", e.message, e) }
    }
  }

  @ReactMethod
  fun decryptOps(srcPath: String, outDir: String, promise: Promise) {
    backgroundExecutor.execute {
      try {
        if (!SecurityCore.gate()) { promise.reject("decrypt_ops", "未授权或环境异常"); return@execute }
        val out = OpsDecryptor(OpsDecryptor.Logger { onLog(it) }).decrypt(srcPath, File(outDir).apply { mkdirs() }.absolutePath)
        promise.resolve(Arguments.createMap().apply { putString("outDir", out) })
      } catch (e: Exception) { promise.reject("decrypt_ops", e.message, e) }
    }
  }

  // ---- 内置 loader 枚举（委托引擎，规则与刷写一致）----
  @ReactMethod
  fun listBuiltinVendors(promise: Promise) {
    backgroundExecutor.execute {
      val arr = Arguments.createArray()
      engine.listBuiltinVendorDirs().forEach { dir ->
        arr.pushMap(Arguments.createMap().apply {
          putString("label", if (dir.equals("oplus", true)) "欧加 (OPlus)" else dir)
          putString("dir", dir)
        })
      }
      promise.resolve(arr)
    }
  }

  @ReactMethod
  fun listBuiltinChips(vendorDir: String, promise: Promise) {
    backgroundExecutor.execute {
      val arr = Arguments.createArray()
      engine.listBuiltinChipDirs(vendorDir).forEach { arr.pushString(it) }
      promise.resolve(arr)
    }
  }

  @ReactMethod
  fun listBuiltinLoaders(vendorDir: String, chip: String, promise: Promise) {
    backgroundExecutor.execute {
      val arr = Arguments.createArray()
      // 与原生『选芯片自动预选首选引导文件』对齐：标注 preferred 供 TS 默认选中
      val preferred = engine.pickBuiltinDevprgFile(vendorDir, chip)
      engine.listBuiltinDevprgFiles(vendorDir, chip).forEach {
        arr.pushMap(Arguments.createMap().apply {
          putString("devprg", it)
          putBoolean("preferred", it == preferred)
        })
      }
      promise.resolve(arr)
    }
  }
}
