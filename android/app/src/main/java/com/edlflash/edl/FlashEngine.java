package com.edlflash.edl;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;

import com.edlflash.R;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class FlashEngine {
    private static final int MODE_PRIVATE = Context.MODE_PRIVATE;

    private final Context ctx;
    private EdlInput input;
    private final EdlCallback cb;

    public FlashEngine(Context ctx, EdlCallback cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    // ---- Context 转发垫片：让原 Activity 继承来的 API 在普通类里以相同调用形态可用，
    //      使 getString/getFilesDir/getAssets/getSharedPreferences 等数百处调用一行不动。----
    private String getString(int resId) {
        return ctx.getString(resId);
    }

    private String getString(int resId, Object... args) {
        return ctx.getString(resId, args);
    }

    private File getFilesDir() {
        return ctx.getFilesDir();
    }

    private android.content.res.AssetManager getAssets() {
        return ctx.getAssets();
    }

    private SharedPreferences getSharedPreferences(String name, int mode) {
        return ctx.getSharedPreferences(name, mode);
    }

    private ApplicationInfo getApplicationInfo() {
        return ctx.getApplicationInfo();
    }

    private Object getSystemService(String name) {
        return ctx.getSystemService(name);
    }

    private String getPackageName() {
        return ctx.getPackageName();
    }

    // ---- 无 UI 引擎用的输入垫片：保留原 View 字段名与读取语义(getText/isChecked/
    //      getSelectedItemPosition)，使 180+ 业务方法的"读 View"调用逐字不动。----
    private static final class TextField {
        private String value;

        TextField(String value) {
            this.value = value == null ? "" : value;
        }

        CharSequence getText() {
            return value;
        }

        void setText(CharSequence text) {
            value = text == null ? "" : text.toString();
        }

        void addTextChangedListener(Object watcher) {
            // headless: 无监听
        }
    }

    private static final class Toggle {
        private boolean checked;

        Toggle(boolean checked) {
            this.checked = checked;
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean value) {
            checked = value;
        }

        void setOnCheckedChangeListener(Object listener) {
            // headless: 无监听
        }
    }

    private static final class Choice {
        private int selection;

        Choice(int selection) {
            this.selection = Math.max(selection, 0);
        }

        int getSelectedItemPosition() {
            return selection;
        }

        void setSelection(int position) {
            selection = Math.max(position, 0);
        }

        void setSelection(int position, boolean animate) {
            selection = Math.max(position, 0);
        }

        void setAdapter(Object adapter) {
            // headless: 无适配器
        }

        void setEnabled(boolean enabled) {
            // headless: 无视图
        }

        void setOnItemSelectedListener(Object listener) {
            // headless: 无监听
        }
    }

    private final class Display {
        void setText(CharSequence text) {
            // headless: 状态文本由对应 sink(onDeviceStatus/onLog) 转发，纯展示文本忽略
        }

        void append(CharSequence text) {
            // headless: 无视图
        }

        void setVisibility(int visibility) {
            // headless: 无视图
        }

        void post(Runnable action) {
            if (action != null) {
                action.run();
            }
        }
    }

    private static final class Btn {
        void setOnClickListener(Object listener) {
            // headless: 入口改由 public 方法触发
        }

        void setEnabled(boolean enabled) {
            // headless: 无视图
        }

        void setVisibility(int visibility) {
            // headless: 无视图
        }

        int getVisibility() {
            return 0; // View.VISIBLE
        }
    }

    private static final class ProgressShim {
        void setIndeterminate(boolean indeterminate) {
        }

        void setProgress(int progress) {
        }

        void setProgressCompat(int progress, boolean animated) {
        }
    }

    private static final class ViewShim {
        void setVisibility(int visibility) {
        }

        int getVisibility() {
            return 0; // View.VISIBLE
        }

        void setLayoutManager(Object manager) {
        }

        void setAdapter(Object adapter) {
        }

        void scheduleLayoutAnimation() {
        }

        void setLayoutAnimation(Object animation) {
        }
    }

    // 文件路径垫片：取代 android.net.Uri，包装由外层解析好的真实路径，
    //   使 resolveUriToFile/resolveArg/copyImagesFromTree 等接收 Uri 的方法签名一行不动。
    private static final class Uri {
        final String path;

        Uri(String path) {
            this.path = path;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    private static final String ASSET_EDL_DIR = "edl_bundle";
    private static final String PREFS = "settings";
    private static final String PREF_SU_CMD = "su_command";
    private static final String PREF_ASSET_VERSION = "asset_version";
    private static final String PREF_VIP_AUTH = "vip_auth";
    private static final String PREF_VIP_AUTH_KEY = "vip_auth_key";
    private static final String PREF_FAST_MODE = "fast_mode";
    private static final String PREF_AUTO_REBOOT = "auto_reboot";
    private static final String PREF_BLOCK_SIZE = "block_size";
    private static final String PREF_MAX_PAYLOAD = "max_payload";
    private static final String PREF_TOTAL_BLOCKS = "total_blocks";
    // qdl 升级到 toggle 版（open 补 SET_INTERFACE 复位 data-toggle，修小米 SDM845 收不到 HELLO）；
    // bump 此版本号强制设备重新解包随包二进制与内置 loader，避免继续跑缓存的旧 replug 版。
    private static final String ASSET_VERSION = "2026-06-10-02";
    private static final String ROOT_EDL_SUBDIR = "edl";
    private static final String DEFAULT_SU_CMD = "su -c";
    private static final String DEFAULT_USB_VID = "05c6";
    private static final String DEFAULT_USB_PID = "9008";
    private static final String DEFAULT_USB_PID_ALT = "900e";
    // EDL 变体 PID，与 qdl usb.c 的 9008/900e/901d 白名单保持一致
    private static final String DEFAULT_USB_PID_ALT2 = "901d";
    private static final String DEFAULT_DOWNLOAD_DIR = "/storage/emulated/0/Download/edl";
    private static final int FAST_MAX_PAYLOAD_DEFAULT = 1048576;
    private static final int FAST_MAX_PAYLOAD_CAP = 1048576;
    private static final int FAST_MAX_PAYLOAD_MIN = 65536;
    private static final int FAST_PAYLOAD_MULTIPLIER = 256;
    // USB bulk 传输的单次大小。qdl 上游默认 1MB(usb.c DEFAULT_OUT_CHUNK_SIZE)，libusb 会自动按
    // URB 分包，故大值安全且大幅减少系统调用；原 16384 过小会把 1MB firehose payload 切成 64 次
    // USB 传输、严重拖慢刷写吞吐。提升到 1MB 对齐 qdl/参考默认值。
    private static final long QDL_OUT_CHUNK_DEFAULT = 1048576L;
    private static final long QDL_VIP_AUTH_COMMAND_TIMEOUT_MS = 120000L;
    // qdl 看门狗轮询间隔：每隔这么久 join 一次进程退出并检查取消/阶段，
    // 不是对 USB 设备轮询（等设备由 qdl 自身的 usb_open 250ms 扫描承担）。
    private static final long COMMAND_WATCHDOG_INTERVAL_MS = 500L;
    private static final String TOOL_QDL = "qdl";
    private static final String XML_TRANSFERCFG = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
            + "<data>\n"
            + "<transfercfg reboot_type=\"off\" timeout_in_sec=\"90\" />\n"
            + "</data>\n";
    private static final String XML_VERIFY = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
            + "<data>\n"
            + "<verify value=\"ping\" EnableVip=\"1\"/>\n"
            + "</data>\n";
    private static final String XML_SHA256INIT = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
            + "<data>\n"
            + "<sha256init Verbose=\"1\"/>\n"
            + "</data>\n";
    private static final int PERSIST_DECISION_CONTINUE = 1;
    private static final int PERSIST_DECISION_SKIP = 2;
    private static final int PERSIST_DECISION_CANCEL = 3;
    private static final Pattern VID_PID_PATTERN =
            Pattern.compile("(?i)^(?:vid=)?([0-9a-f]{1,4})(?:[:\\s,]+(?:pid=)?([0-9a-f]{1,4}))?$");
    private static final Pattern DEBUG_T_PATTERN =
            Pattern.compile(".*Bus=([0-9]+).*Dev#=\\s*([0-9]+).*");
    private static final Pattern DEBUG_P_PATTERN =
            Pattern.compile(".*Vendor=([0-9a-fA-F]{1,4}).*ProdID=([0-9a-fA-F]{1,4}).*");
    private static final Pattern USB_BUSDEV_PATTERN =
            Pattern.compile("^/dev/bus/usb/([0-9]+)/([0-9]+)$");

    private static final String[] COMMANDS = new String[]{
            "server", "memorydump", "printgpt", "gpt", "r", "rl", "rf", "rs",
            "w", "wl", "wf", "ws", "e", "es", "ep", "footer", "peek",
            "peekhex", "peekdword", "peekqword", "memtbl", "poke", "pokehex",
            "pokedword", "pokeqword", "memcpy", "secureboot", "pbl", "qfp",
            "getstorageinfo", "setbootablestoragedrive", "getactiveslot",
            "setactiveslot", "send", "xml", "rawxml", "reset", "nop",
            "modules", "provision", "qfil", "sign"
    };

    private static final Map<String, CommandSpec> COMMAND_SPECS = new HashMap<>();

    static {
        COMMAND_SPECS.put("gpt", new CommandSpec("输出目录名", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("r", new CommandSpec("分区名", ArgType.TEXT, "输出镜像文件名", ArgType.OUTPUT, "", ArgType.NONE, 2, 2));
        COMMAND_SPECS.put("rl", new CommandSpec("输出目录名", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("rf", new CommandSpec("输出镜像文件名", ArgType.OUTPUT, "", ArgType.NONE, "", ArgType.NONE, 1, 1));
        COMMAND_SPECS.put("rs", new CommandSpec("起始扇区", ArgType.TEXT, "扇区数量", ArgType.TEXT, "输出镜像文件名", ArgType.OUTPUT, 3, 3));
        COMMAND_SPECS.put("w", new CommandSpec("分区名", ArgType.TEXT, "输入镜像文件（.img）", ArgType.FILE, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("wl", new CommandSpec("分区目录", ArgType.DIR, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("wf", new CommandSpec("全盘镜像文件（单个 .img/.bin）", ArgType.FILE, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("ws", new CommandSpec("起始扇区", ArgType.TEXT, "输入镜像文件（.img）", ArgType.FILE, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("e", new CommandSpec("分区名", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("es", new CommandSpec("起始扇区", ArgType.TEXT, "扇区数量", ArgType.TEXT, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("ep", new CommandSpec("分区名", ArgType.TEXT, "扇区数量", ArgType.TEXT, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("footer", new CommandSpec("输出文件名", ArgType.OUTPUT, "", ArgType.NONE, "", ArgType.NONE, 1, 1));
        COMMAND_SPECS.put("peek", new CommandSpec("偏移", ArgType.TEXT, "长度", ArgType.TEXT, "输出文件名", ArgType.OUTPUT, 3, 3));
        COMMAND_SPECS.put("peekhex", new CommandSpec("偏移", ArgType.TEXT, "长度", ArgType.TEXT, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("peekdword", new CommandSpec("偏移", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("peekqword", new CommandSpec("偏移", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("memtbl", new CommandSpec("输出文件名", ArgType.OUTPUT, "", ArgType.NONE, "", ArgType.NONE, 1, 1));
        COMMAND_SPECS.put("poke", new CommandSpec("偏移", ArgType.TEXT, "输入镜像文件（.img）", ArgType.FILE, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("pokehex", new CommandSpec("偏移", ArgType.TEXT, "HEX 数据", ArgType.TEXT, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("pokedword", new CommandSpec("偏移", ArgType.TEXT, "DWORD 数据", ArgType.TEXT, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("pokeqword", new CommandSpec("偏移", ArgType.TEXT, "QWORD 数据", ArgType.TEXT, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("memcpy", new CommandSpec("源偏移", ArgType.TEXT, "长度", ArgType.TEXT, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("pbl", new CommandSpec("输出文件名", ArgType.OUTPUT, "", ArgType.NONE, "", ArgType.NONE, 1, 1));
        COMMAND_SPECS.put("qfp", new CommandSpec("输出文件名", ArgType.OUTPUT, "", ArgType.NONE, "", ArgType.NONE, 1, 1));
        COMMAND_SPECS.put("secureboot", new CommandSpec("偏移地址（可选）", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 0, 0));
        COMMAND_SPECS.put("memorydump", new CommandSpec("分区列表（可选）", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 0, 0));
        COMMAND_SPECS.put("reset", new CommandSpec("重启模式（可选）", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 0, 0));
        COMMAND_SPECS.put("setbootablestoragedrive", new CommandSpec("LUN", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("setactiveslot", new CommandSpec("槽位（a/b）", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("send", new CommandSpec("Firehose 命令", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("xml", new CommandSpec("XML 文件", ArgType.FILE, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("rawxml", new CommandSpec("XML 内容", ArgType.TEXT, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("modules", new CommandSpec("子命令", ArgType.TEXT, "子命令参数", ArgType.TEXT, "", ArgType.NONE, 2, 0));
        COMMAND_SPECS.put("provision", new CommandSpec("XML 文件", ArgType.FILE, "", ArgType.NONE, "", ArgType.NONE, 1, 0));
        COMMAND_SPECS.put("qfil", new CommandSpec("rawprogram.xml（可多选）", ArgType.FILE_MULTI, "patch.xml（可多选）", ArgType.FILE_MULTI, "镜像目录", ArgType.DIR, 3, 0));
        COMMAND_SPECS.put("sign", new CommandSpec("", ArgType.NONE, "", ArgType.NONE, "", ArgType.NONE, 0, 0));
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor();
    // 设备状态轮询用独立后台线程：每秒一次的 USB root/sysfs 探测放后台，避免主线程 IO 卡顿(ANR)，
    // 也不挤占刷写用的 executor。
    private final ExecutorService edlStatusExecutor = Executors.newSingleThreadExecutor();
    private ScheduledFuture<?> progressFuture;
    private final AtomicInteger progressSeq = new AtomicInteger(0);

    // 一次只跑一个用户操作：CAS 占用，避免后续点击在单线程 executor 上排队饿死。
    private final AtomicBoolean commandRunning = new AtomicBoolean(false);
    // 取消信号，由停止按钮置位、qdl 看门狗读取。
    private final AtomicBoolean commandCanceled = new AtomicBoolean(false);
    // 最近一次命令的最终成败：命令开始时复位 false，由 finishProgress 落值，供桥接回传 JS。
    private volatile boolean lastCommandSuccess = false;
    // 当前正在跑的 qdl 进程句柄，供停止按钮 destroy（本地 dd 等命令不暴露，避免误杀产生半成品镜像）。
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();
    // qdl 输出阶段：等设备(WAITING)永不计业务超时，抓到设备握手(CONNECTED)后才起算。
    private final AtomicReference<QdlPhase> qdlPhase = new AtomicReference<>(QdlPhase.IDLE);
    // 仅在 qdl 命令期间解析阶段，防止本地 dd 等命令的输出污染状态机。
    private final AtomicBoolean watchingQdlOutput = new AtomicBoolean(false);

    private enum QdlPhase { IDLE, WAITING, CONNECTED }

    // 用户取消用非受检异常，穿透刷写流程里大量 catch(IOException|InterruptedException)，
    // 确保取消第一段后不会被吞掉后又启动下一段 qdl 重新等设备。
    private static final class CommandCanceledException extends RuntimeException {
        CommandCanceledException(String message) {
            super(message);
        }
    }

    private Display rootStatusView;
    private Display edlStatusView;
    private Display loaderPathView;
    private Display digestPathView;
    private Display signPathView;
    private ViewShim loaderDevprgSection;
    private ViewShim loaderDigestSection;
    private ViewShim loaderSignSection;
    private ViewShim vipAuthSection;
    // volatile：命令线程 applyInput 重赋值对监视线程(root 探测读 getText)立即可见。
    private volatile TextField suCommandInput;
    private Choice commandSpinner;
    private TextField arg1Input;
    private TextField arg2Input;
    private TextField arg3Input;
    private TextField outputNameInput;
    private Display outputNameLabel;
    private Btn runButton;
    private Btn gptButton;
    private Btn stopButton;
    private Choice languageSpinner;
    private ProgressShim progressBar;

    private Runnable progressRunnable;
    private volatile boolean progressRunning = false;
    private boolean progressHasValue = false;
    private File progressLogFile;
    private String progressLabel;
    private String progressSpeed;
    private final AtomicInteger logSession = new AtomicInteger(0);
    private Display progressText;
    private Display logView;
    private ViewShim contentRoot;
    private String lastFirehoseStep;
    private int logLineCount = 0;
    // 日志攒批：高频行先入缓冲，60ms 合并成一次 append，减少 UI 调度与整树 relayout。
    private final java.util.ArrayList<CharSequence> pendingLogBatch = new java.util.ArrayList<>();
    private int pendingLogSession = -1;
    private boolean firehoseStepLogged = false;
    private boolean configureStepLogged = false;
    // QFIL 拆分刷写期间置位：各分区/补丁的独立 qdl 调用一律不自动复位，
    // 改由 qfil 流程末尾统一发一次 reset，避免逐分区重启中断整轮刷写
    private boolean suppressAutoReset = false;
    private boolean vipDigestStepLogged = false;
    private boolean vipSignStepLogged = false;
    private boolean expectFirehoseStep = false;
    private boolean expectVipSteps = false;
    private boolean vipDigestStarted = false;
    private boolean vipSignStarted = false;
    private boolean sawProbeReadFailure = false;
    private boolean summaryOnlyLog = false;
    private String lastErrorReason;
    private Display arg1Label;
    private Display arg2Label;
    private Display arg3Label;
    private ViewShim arg1Section;
    private ViewShim arg2Section;
    private ViewShim arg3Section;
    private Btn arg1PickButton;
    private Btn arg2PickButton;
    private Btn arg3PickButton;
    private ViewShim edlPackageSection;
    private Display edlPackagePath;
    private Display edlPackageInfo;
    private Btn selectEdlPackageButton;
    private Toggle edlPackageSplitCheck;
    private Toggle edlPackageProtectLun5Check;
    private Toggle edlPackageMergeSuperCheck;
    private File usbTraceFile;
    private Btn selectLoaderButton;
    private Btn selectDigestButton;
    private Btn selectSignButton;
    private Btn vipAuthButton;
    private ViewShim loaderCard;
    private ViewShim optionsCard;
    private ViewShim signCard;
    private Display partitionListLabel;
    private ViewShim partitionRecyclerView;
    private int selectedPartitionIndex = -1;
    private Btn partitionApplyButton;
    private Btn partitionWriteButton;
    private Btn partitionMultiReadButton;
    private volatile List<PartitionOption> pendingMultiRead;
    private Choice builtinVendorSpinner;
    private Choice builtinChipSpinner;
    private Display builtinLoaderLabel;
    private Choice builtinLoaderSpinner;
    // 多镜像引导（红魔/联想等新机型）的 Sahara 配置文件名：内含 image_id->文件 映射，
    // qdl 会解析它并按 ID 上传整组镜像（xbl_sc/multi_image/prog_firehose 等）。
    private static final String SAHARA_CONFIG_NAME = "qsahara_device_programmer.xml";

    private String builtinVendorDir;
    private String builtinChip;
    private String builtinDevprgFileName;
    private String builtinDevprgAssetPath;
    private String builtinDigestAssetPath;
    private String builtinSignAssetPath;
    private Display signInputDirPath;
    private Display signKeyPath;
    private Display signOutputDirPath;
    private TextField signPartitionsInput;
    private Toggle signChainCheck;
    private Toggle signVerifyCheck;
    private Toggle signRegenSaltCheck;
    private Btn selectSignDirButton;
    private Btn selectSignKeyButton;
    private Btn selectSignOutputDirButton;

    // 同时支持单/双引号属性，且为旧双引号模式的严格超集：用反向引用保证开闭引号一致，
    // 值用 group(3)（值内可含另一种引号，如 filename="foo's.img" 不被截断）
    private static final Pattern RAWPROGRAM_ATTR_PATTERN =
            Pattern.compile("([A-Za-z0-9_]+)\\s*=\\s*([\"'])(.*?)\\2");
    private static final Pattern RAWPROGRAM_TAG_PATTERN =
            Pattern.compile("<program\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    // 同时匹配 rawprogram(N).xml 与 rawprogram_unsparse(N).xml（后者为 raw/未 sparse 变体）。
    // (?:_unsparse)? 为非捕获组，故 group(1) 仍是 LUN 索引数字，parseXmlIndex/filterLun5 不受影响。
    private static final Pattern RAWPROGRAM_FILE_PATTERN =
            Pattern.compile("^rawprogram(?:_unsparse)?(\\d*)\\.xml$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATCH_FILE_PATTERN =
            Pattern.compile("^patch(\\d*)\\.xml$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPER_SEGMENT_PATTERN =
            Pattern.compile("^super\\.(\\d+)(?:\\.[a-fA-F0-9]+)?\\.img$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> QFIL_SKIP_PARTITIONS = new LinkedHashSet<>(Arrays.asList(
            "super",
            "ocdt",
            "persist",
            "secdata",
            "oplusdycnvbk",
            "oplusstanvbk_a"
    ));
    private static final int SPARSE_HEADER_MAGIC = 0xed26ff3a;
    private static final int SPARSE_CHUNK_TYPE_RAW = 0xCAC1;
    private static final int SPARSE_CHUNK_TYPE_FILL = 0xCAC2;
    private static final int SPARSE_CHUNK_TYPE_DONT_CARE = 0xCAC3;
    private static final int SPARSE_CHUNK_TYPE_CRC32 = 0xCAC4;
    // 原生 qdl 的速度汇总只打印 kB/s（firehose.c "successfully at %lukB/s"），故同时兼容 kB/MB
    private static final Pattern SPEED_PATTERN =
            Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(kB|MB)(?:/s|ps)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_SIZE_PATTERN =
            Pattern.compile(
                    "Block Size in Bytes:\\s*0x([0-9a-fA-F]+)"
                            + "|Block Size in Bytes:\\s*(\\d+)"
                            + "|SECTOR_SIZE_IN_BYTES\\s*[=:\" ]+(\\d+)",
                    Pattern.CASE_INSENSITIVE);
    // 设备 getstorageinfo 多以 JSON 上报（键带引号，如 "total_blocks":123），故容忍可选引号与 := 分隔
    private static final Pattern STORAGE_TOTAL_BLOCKS_PATTERN =
            Pattern.compile("\"?total_blocks\"?\\s*[:=]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORAGE_BLOCK_SIZE_PATTERN =
            Pattern.compile("\"?block_size\"?\\s*[:=]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORAGE_NUM_PHYSICAL_PATTERN =
            Pattern.compile(
                    "\"?num_physical(?:_partitions)?\"?\\s*[:=]?\\s*(\\d+)"
                            + "|bnumberlu\\s*[=:\" ]+([0-9a-fA-Fx]+)"
                            + "|ufs total active lu\\s*:?\\s*(0x[0-9a-fA-F]+|\\d+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern STORAGE_LUN_MASK_PATTERN =
            Pattern.compile("ufs lun enable bitmask\\s*:?\\s*(0x[0-9a-fA-F]+|\\d+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MAX_PAYLOAD_PATTERN =
            Pattern.compile("MaxPayloadSizeToTargetInBytes(?:Supported)?=\\\"(\\d+)\\\"");
    // OPlus loader 对 <getddrtype/> 的回送：ddr_type="1"=Normal DDR(LPDDR4X)，"2"=LPDDR5。
    // 容忍 = 与引号间空格、单双引号；调用方先把 &quot; 还原成 " 再匹配，并取最后一次（命令后新增段）。
    private static final Pattern DDR_TYPE_PATTERN =
            Pattern.compile("ddr[_-]?type\\s*[:=]\\s*[\"']?([12])", Pattern.CASE_INSENSITIVE);

    private List<PartitionOption> partitionOptions = new ArrayList<>();

    private TextField vidInput;
    private TextField pidInput;
    private TextField portInput;
    private TextField serialNumberInput;
    private Toggle serialCheck;
    private Toggle debugCheck;
    private Toggle skipStorageInitCheck;
    private Toggle skipResponseCheck;
    private Toggle skipWriteCheck;
    private Toggle fastModeCheck;
    private Toggle autoRebootCheck;
    private TextField memoryInput;
    private TextField lunInput;
    private TextField maxPayloadInput;
    private TextField sectorSizeInput;
    private TextField gptNumInput;
    private TextField gptSizeInput;
    private TextField gptStartInput;
    private TextField deviceModelInput;
    private Toggle oplusTokenAuthCheck;
    private TextField oplusSerialInput;
    private TextField tcpPortInput;
    private TextField slotInput;
    private TextField resetModeInput;
    private TextField partitionFilenameInput;
    private TextField partitionsInput;
    private TextField skipPartitionsInput;
    private Toggle genXmlCheck;
    private TextField rawXmlInput;
    private TextField extraArgsInput;

    private Uri loaderUri;
    private Uri digestUri;
    private Uri signUri;
    private Uri arg1Uri;
    private Uri arg2Uri;
    private Uri arg3Uri;
    private Uri edlPackageUri;
    private Uri signInputDirUri;
    private Uri signOutputDirUri;
    private Uri signKeyUri;
    private volatile boolean rootAvailable = false;
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%");
    private static final Pattern SECTOR_PROGRESS_PATTERN =
            Pattern.compile("Sector\\s+0x([0-9a-fA-F]+)\\s+of\\s+0x([0-9a-fA-F]+)");
    // 跨线程共享(命令线程写、设备监视守护线程读/写)，须 volatile，与 rootAvailable 一致。
    private volatile boolean vipAuthorized = false;
    // 仅当 VIP 授权干净完成、且 Firehose 会话未被污染时为真。USB 仍在线但会话被污染
    // (如上次授权遗留 rawmode)时 lastEdlConnected 不会反映，靠此标志拦住错误复用。不持久化。
    private volatile boolean vipSessionHealthy = false;
    private File activeVipDigestFile;
    private File activeVipSignFile;
    private volatile boolean lastEdlConnected = false;
    private int cachedBlockSize = -1;
    private int cachedMaxPayload = -1;
    private long cachedTotalBlocks = -1L;
    private int cachedNumPhysical = -1;
    private long cachedLunEnableMask = -1L;
    private final List<GptEntry> gptEntries = new ArrayList<>();
    private long progressTotalBytes = -1L;
    private long progressLastBytes = 0L;
    private long progressLastTimeMs = 0L;
    private EdlPackageInfo edlPackageInfoData;

    private ArgType arg1Type = ArgType.NONE;
    private ArgType arg2Type = ArgType.NONE;
    private ArgType arg3Type = ArgType.NONE;
    private List<Uri> arg1UriList;
    private List<Uri> arg2UriList;

    private enum PickTarget {
        NONE,
        LOADER,
        DIGEST,
        SIGNATURE,
        ARG1,
        ARG2,
        ARG3,
        ARG1_MULTI,
        ARG2_MULTI,
        EDL_PACKAGE_DIR,
        EDL_PACKAGE_FILE,
        SIGN_INPUT_DIR,
        SIGN_OUTPUT_DIR,
        SIGN_KEY
    }

    private enum ArgType {
        NONE,
        TEXT,
        FILE,
        FILE_MULTI,
        DIR,
        OUT_DIR,
        OUTPUT
    }

    // 用输入快照回填全部垫片字段，使业务方法的"读 View"调用(getText/isChecked/
    // getSelectedItemPosition)返回这次运行的快照值，逻辑层一行不改。
    public void applyInput(EdlInput in) {
        this.input = in;

        int commandIndex = findCommandIndex(in.command);
        if (commandIndex < 0) {
            commandIndex = 0;
        }
        commandSpinner = new Choice(commandIndex);
        languageSpinner = new Choice(0);

        suCommandInput = new TextField(in.suCommand);
        arg1Input = new TextField(in.arg1);
        arg2Input = new TextField(in.arg2);
        arg3Input = new TextField(in.arg3);
        outputNameInput = new TextField(in.outputName);
        deviceModelInput = new TextField(in.deviceModel);
        oplusSerialInput = new TextField(in.oplusSerial);
        lunInput = new TextField(in.lun);
        skipPartitionsInput = new TextField(in.skipPartitions);
        maxPayloadInput = new TextField(in.maxPayload);
        partitionsInput = new TextField(in.partitions);
        tcpPortInput = new TextField(in.tcpPort);
        rawXmlInput = new TextField(in.rawXml);
        resetModeInput = new TextField(in.resetMode);
        sectorSizeInput = new TextField(in.sectorSize);
        memoryInput = new TextField(in.memory);
        signPartitionsInput = new TextField(in.signPartitions);
        vidInput = new TextField(in.vid);
        pidInput = new TextField(in.pid);
        portInput = new TextField(in.port);
        serialNumberInput = new TextField("");
        gptNumInput = new TextField("");
        gptSizeInput = new TextField("");
        gptStartInput = new TextField("");
        slotInput = new TextField("");
        partitionFilenameInput = new TextField("");
        extraArgsInput = new TextField("");

        skipStorageInitCheck = new Toggle(in.skipStorageInit);
        signRegenSaltCheck = new Toggle(in.signRegenSalt);
        signVerifyCheck = new Toggle(in.signVerify);
        signChainCheck = new Toggle(in.signChain);
        fastModeCheck = new Toggle(in.fastMode);
        autoRebootCheck = new Toggle(in.autoReboot);
        edlPackageSplitCheck = new Toggle(in.qfilSplit);
        edlPackageProtectLun5Check = new Toggle(in.protectLun5);
        edlPackageMergeSuperCheck = new Toggle(in.mergeSuper);
        oplusTokenAuthCheck = new Toggle(in.oplusTokenAuth);
        serialCheck = new Toggle(false);
        debugCheck = new Toggle(false);
        skipResponseCheck = new Toggle(false);
        skipWriteCheck = new Toggle(in.dryRun);   // 演练开关：值取自输入，命令组装处据此加 --dry-run
        genXmlCheck = new Toggle(false);

        // 展示/容器/按钮/进度全部为无副作用垫片。
        rootStatusView = new Display();
        edlStatusView = new Display();
        loaderPathView = new Display();
        digestPathView = new Display();
        signPathView = new Display();
        outputNameLabel = new Display();
        progressText = new Display();
        logView = new Display();
        arg1Label = new Display();
        arg2Label = new Display();
        arg3Label = new Display();
        edlPackagePath = new Display();
        edlPackageInfo = new Display();
        partitionListLabel = new Display();
        builtinLoaderLabel = new Display();
        signInputDirPath = new Display();
        signKeyPath = new Display();
        signOutputDirPath = new Display();
        progressBar = new ProgressShim();
        contentRoot = new ViewShim();
        loaderDevprgSection = new ViewShim();
        loaderDigestSection = new ViewShim();
        loaderSignSection = new ViewShim();
        vipAuthSection = new ViewShim();
        arg1Section = new ViewShim();
        arg2Section = new ViewShim();
        arg3Section = new ViewShim();
        edlPackageSection = new ViewShim();
        loaderCard = new ViewShim();
        optionsCard = new ViewShim();
        signCard = new ViewShim();
        partitionRecyclerView = new ViewShim();
        runButton = new Btn();
        gptButton = new Btn();
        stopButton = new Btn();
        selectEdlPackageButton = new Btn();
        selectLoaderButton = new Btn();
        selectDigestButton = new Btn();
        selectSignButton = new Btn();
        vipAuthButton = new Btn();
        partitionApplyButton = new Btn();
        partitionWriteButton = new Btn();
        partitionMultiReadButton = new Btn();
        arg1PickButton = new Btn();
        arg2PickButton = new Btn();
        arg3PickButton = new Btn();
        selectSignDirButton = new Btn();
        selectSignKeyButton = new Btn();
        selectSignOutputDirButton = new Btn();
        builtinVendorSpinner = new Choice(0);
        builtinChipSpinner = new Choice(0);
        builtinLoaderSpinner = new Choice(0);

        // 参数类型按命令规格设定（原由 updateArgSection 负责，headless 直接据 spec 给定），
        // resolveArg 据此决定 FILE/DIR 参数是否解析为路径。
        CommandSpec spec = COMMAND_SPECS.get(in.command);
        if (spec != null) {
            arg1Type = spec.arg1Type;
            arg2Type = spec.arg2Type;
            arg3Type = spec.arg3Type;
        } else {
            arg1Type = ArgType.NONE;
            arg2Type = ArgType.NONE;
            arg3Type = ArgType.NONE;
        }

        // 文件路径：外层已把 SAF 解析为真实路径，包成 Uri 垫片供下游 resolveUriToFile 等使用。
        loaderUri = toUri(in.loaderPath);
        digestUri = toUri(in.digestPath);
        signUri = toUri(in.signPath);
        arg1Uri = toUri(in.arg1Path);
        arg2Uri = toUri(in.arg2Path);
        arg3Uri = toUri(in.arg3Path);
        edlPackageUri = toUri(in.edlPackagePath);
        // 换包即作废上次解析/解包结果（对齐原生 updateEdlPackageSelection 的 edlPackageUri/edlPackageInfoData 配对重置），
        // 否则单例引擎跨次刷写复用旧 EdlPackageInfo，连刷两个不同包会沿用上一包的 rawprogram/镜像导致刷错固件。
        edlPackageInfoData = null;
        signInputDirUri = toUri(in.signInputDir);
        signOutputDirUri = toUri(in.signOutputDir);
        signKeyUri = toUri(in.signKeyPath);
        arg1UriList = toUriList(in.arg1Paths);
        arg2UriList = toUriList(in.arg2Paths);

        // 内置 loader：headless 直接给定已选定的 asset 路径，gate 仍走原逻辑的
        // builtinVendorDir != null 分支。
        if (in.builtinSelected) {
            builtinVendorDir = in.builtinVendorDir;
            builtinChip = in.builtinChip;
            // 空/空白文件名归一为 null，交由 findBuiltinAuthFiles 自动优选（对齐原版未手选时的默认 loader 行为）
            builtinDevprgFileName = (in.builtinDevprgFileName == null || in.builtinDevprgFileName.trim().isEmpty())
                    ? null : in.builtinDevprgFileName;
            // 用引擎自身的派生逻辑（等价原 refreshBuiltinAuthFiles）得到 devprg/digest/sign 完整 asset 路径
            BuiltinAuthFiles af = findBuiltinAuthFiles(builtinVendorDir, builtinChip, builtinDevprgFileName);
            builtinDevprgAssetPath = af.devprg;
            builtinDigestAssetPath = af.digest;
            builtinSignAssetPath = af.signature;
        } else {
            builtinVendorDir = null;
            builtinDevprgFileName = null;
            builtinDevprgAssetPath = null;
            builtinDigestAssetPath = null;
            builtinSignAssetPath = null;
        }

        // 分区批量读取集合（取代行内多选框），由 startSelectedMultiRead 消费。
        multiReadSelection = in.multiReadPartitions;

        // 分区单选兜底：r/w/e 命令且 arg1 未显式给出时，用选中分区名填 arg1。
        if (in.selectedPartition != null && !in.selectedPartition.isEmpty()
                && in.arg1 != null && in.arg1.isEmpty()) {
            String cmd = in.command == null ? "" : in.command;
            if ("r".equals(cmd) || "w".equals(cmd) || "e".equals(cmd)) {
                arg1Input.setText(in.selectedPartition);
            }
        }
    }

    // 分区批量读取选择（取代 PartitionAdapter 的勾选集合）。
    private List<String> multiReadSelection;

    private Uri toUri(String path) {
        return (path == null || path.isEmpty()) ? null : new Uri(path);
    }

    private List<Uri> toUriList(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        List<Uri> list = new ArrayList<>();
        for (String p : paths) {
            if (p != null && !p.isEmpty()) {
                list.add(new Uri(p));
            }
        }
        return list;
    }

    // ---- 供桥接调用的公开入口：同步执行(由桥接的 io 线程驱动)，内联命令生命周期，
    //      使 cancel() 能 destroy 进程、busy 全程维持、执行完才返回。----
    public boolean runCommand() {
        return runGuardedPublic(this::runSelectedCommand);
    }

    public boolean readPartitionTable() {
        return runGuardedPublic(this::runGptList);
    }

    public boolean vipAuth() {
        return runGuardedPublic(this::runVipAuthOnly);
    }

    public boolean sign() {
        return runGuardedPublic(this::runSignTool);
    }

    public boolean runMultiRead() {
        return runGuardedPublic(this::startSelectedMultiRead);
    }

    public boolean isVipAuthorized() {
        return vipAuthorized;
    }

    public boolean lastCommandSucceeded() {
        return lastCommandSuccess;
    }

    /** 同步版命令守卫：置 commandRunning 占用→跑→finally 复位，不另起线程。 */
    private boolean runGuardedPublic(Runnable task) {
        if (!commandRunning.compareAndSet(false, true)) {
            return false;
        }
        commandCanceled.set(false);
        lastCommandSuccess = false;
        activeProcess.set(null);
        qdlPhase.set(QdlPhase.IDLE);
        watchingQdlOutput.set(false);
        try {
            runGuarded(task);
        } finally {
            activeProcess.set(null);
            watchingQdlOutput.set(false);
            qdlPhase.set(QdlPhase.IDLE);
            commandRunning.set(false);
        }
        return true;
    }

    // ---- 供桥接调用的资产/状态公开包装（转调既有 private 方法，逻辑不动）----
    public String assetVersion() {
        return ASSET_VERSION;
    }

    public void ensureAssets() {
        ensureEdlExtracted();
    }

    public void refreshRootStatus() {
        requestRoot();
    }

    public boolean isRootAvailable() {
        return rootAvailable;
    }

    public void pollDeviceStatus() {
        updateEdlStatus();
    }

    /** headless 设备探测：复用 resolveEdlUsbPathInfo 的 4 级检测链
     *  （UsbManager→/sys/bus/usb→root sysfs→debug，含 9008→900e 回退），不触碰任何 view 垫片。
     *  空闲时顺带维护 VIP 会话状态，供桥接的设备监视线程调用。 */
    public DeviceProbe pollEdlDevice(String vid, String pid) {
        String targetVid = normalizeHexId(vid);
        if (targetVid == null) {
            targetVid = DEFAULT_USB_VID;
        }
        String targetPid = normalizeHexId(pid);
        if (targetPid == null) {
            targetPid = DEFAULT_USB_PID;
        }
        UsbPathInfo info = resolveEdlUsbPathInfo(new PortId(targetVid, targetPid));
        // 仅在无命令运行时同步 VIP 会话状态，避免与命令线程争写 vipAuthorized。
        if (!commandRunning.get()) {
            syncVipAuthState(info.usbPath, info.vidPid);
        }
        boolean connected = info.usbPath != null && !info.usbPath.trim().isEmpty();
        return new DeviceProbe(connected, info.usbPath, info.vidPid);
    }

    public static final class DeviceProbe {
        public final boolean connected;
        public final String usbPath;
        public final String vidPid;

        DeviceProbe(boolean connected, String usbPath, String vidPid) {
            this.connected = connected;
            this.usbPath = usbPath;
            this.vidPid = vidPid;
        }
    }

    public void cancel() {
        cancelCurrentCommand();
    }

    // 引擎级回收：复刻原生 onDestroy 收尾顺序——先取消并 destroy 在跑的 qdl(避免孤儿占住 USB)，
    // 再停进度监视与三套执行器，最后关常驻 su shell。供桥接在 context 销毁时调用。
    public void shutdown() {
        commandCanceled.set(true);
        Process process = activeProcess.getAndSet(null);
        if (process != null) {
            process.destroy();
        }
        stopLogProgressMonitor();
        executor.shutdownNow();
        progressExecutor.shutdownNow();
        edlStatusExecutor.shutdownNow();
        rootShell.close();
    }

    // ---- 内置 loader 枚举（复用引擎语义，避免与桥接两套规则漂移）----
    public List<String> listBuiltinVendorDirs() {
        List<String> out = new ArrayList<>();
        try {
            String[] vendors = ctx.getAssets().list("loader_builtin");
            if (vendors == null) {
                return out;
            }
            for (String v : vendors) {
                String[] chips = ctx.getAssets().list("loader_builtin/" + v);
                if (chips == null || chips.length == 0) {
                    continue; // 非目录(如顶层 txt)→跳过
                }
                boolean hasChild = false;
                for (String ch : chips) {
                    String[] files = ctx.getAssets().list("loader_builtin/" + v + "/" + ch);
                    if (files != null && files.length > 0) {
                        hasChild = true;
                        break;
                    }
                }
                if (hasChild) {
                    out.add(v);
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public List<String> listBuiltinChipDirs(String vendor) {
        List<String> out = new ArrayList<>();
        try {
            String[] chips = ctx.getAssets().list("loader_builtin/" + vendor);
            if (chips == null) {
                return out;
            }
            for (String ch : chips) {
                String[] files = ctx.getAssets().list("loader_builtin/" + vendor + "/" + ch);
                if (files != null && files.length > 0) {
                    out.add(ch);
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public List<String> listBuiltinDevprgFiles(String vendor, String chip) {
        try {
            String[] files = ctx.getAssets().list("loader_builtin/" + vendor + "/" + chip);
            if (files == null) {
                return new ArrayList<>();
            }
            return listDevprgCandidates(files); // sahara XML 单项 / isLoaderFile / 排除 digest&sign
        } catch (IOException ignored) {
            return new ArrayList<>();
        }
    }

    // 与原生 loadBuiltinLoaderChoices 一致：在候选中挑出默认首选引导文件名，供 UI 自动预选。
    public String pickBuiltinDevprgFile(String vendor, String chip) {
        return choosePreferredLoaderCandidate(listBuiltinDevprgFiles(vendor, chip));
    }

    private void resetVipAuthState() {
        vipAuthorized = false;
        vipSessionHealthy = false;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit()
                .putBoolean(PREF_VIP_AUTH, false)
                .remove(PREF_VIP_AUTH_KEY)
                .apply();
    }

    private boolean hasAuthDigest() {
        return digestUri != null
                || (builtinDigestAssetPath != null && !builtinDigestAssetPath.trim().isEmpty());
    }

    private boolean hasAuthSign() {
        return signUri != null
                || (builtinSignAssetPath != null && !builtinSignAssetPath.trim().isEmpty());
    }

    private boolean hasAuthFilesConfigured() {
        return hasAuthDigest() && hasAuthSign();
    }

    private boolean hasAuthFilesMismatch() {
        return hasAuthDigest() ^ hasAuthSign();
    }

    private boolean isVipMode() {
        return hasAuthFilesConfigured();
    }

    private void setActiveVipFiles(File digestFile, File signFile) {
        activeVipDigestFile = digestFile;
        activeVipSignFile = signFile;
    }

    private boolean hasActiveVipFiles() {
        return activeVipDigestFile != null && activeVipSignFile != null;
    }

    private boolean canReuseVipSession() {
        return vipAuthorized && vipSessionHealthy && lastEdlConnected;
    }

    private File resolveVipPartitionFile(File runDir, File loaderFile) {
        if (loaderFile != null) {
            String path = loaderFile.getAbsolutePath();
            if (path != null && !path.isEmpty()) {
                int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(File.separatorChar));
                int dot = path.lastIndexOf('.');
                String base;
                if (dot > lastSlash) {
                    base = path.substring(0, dot);
                } else {
                    base = path;
                }
                File candidate = new File(base + ".vip");
                if (candidate.exists()) {
                    return candidate;
                }
            }
        }

        // VIP partition info 必须是 loader 配套的签名 .vip blob。绝不能用 rawprogram0.xml 顶替：
        // 它是刷写命令清单(含 <program>)，发给设备会进入等待 raw 数据的 rawmode 却无后续 payload，
        // 污染整个 Firehose 会话(后续 configure 被当 raw 数据 → 死循环)。无 .vip 时返回 null 跳过该步。
        return null;
    }

    private String buildVipPartitionEnvPrefix(File runDir, File loaderFile) {
        File vipFile = resolveVipPartitionFile(runDir, loaderFile);
        if (vipFile == null) {
            return "";
        }
        return "EDL_VIP_PARTITION=" + shQuote(vipFile.getAbsolutePath()) + " ";
    }

    private int findCommandIndex(String command) {
        if (command == null) {
            return -1;
        }
        for (int i = 0; i < COMMANDS.length; i++) {
            if (command.equals(COMMANDS[i])) {
                return i;
            }
        }
        return -1;
    }

    // 批量读取：直接取分区列表里勾选的行（行内多选框），不再弹对话框。
    private void startSelectedMultiRead() {
        // 批量读取选择来自输入快照(取代行内多选框)：按名称在当前分区列表中匹配出选项。
        List<PartitionOption> picked = new ArrayList<>();
        if (multiReadSelection != null) {
            for (String sel : multiReadSelection) {
                if (sel == null) {
                    continue;
                }
                // 选择项格式 "lun:name"（带 LUN 精确匹配）；兼容旧的纯 name
                int colon = sel.indexOf(':');
                String selLun = colon > 0 ? sel.substring(0, colon) : null;
                String selName = colon >= 0 ? sel.substring(colon + 1) : sel;
                for (PartitionOption option : partitionOptions) {
                    if (option.name.equals(selName)
                            && (selLun == null || selLun.equals(option.lun))) {
                        picked.add(option);
                        break;
                    }
                }
            }
        }
        if (picked.isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return;
        }
        startMultiRead(picked);
    }

    // Drive the batch read through the standard run pipeline (loader/root/work
    // dir prep are all handled there); the "r" branch consumes pendingMultiRead.
    private void startMultiRead(List<PartitionOption> picked) {
        pendingMultiRead = picked;
        List<String> names = new ArrayList<>();
        for (PartitionOption option : picked) {
            names.add(option.name);
        }
        int rIndex = findCommandIndex("r");
        if (rIndex >= 0) {
            commandSpinner.setSelection(rIndex);
        }
        arg1Input.setText(TextUtils.join(",", names));
        outputNameInput.setText("");
        // 此处已在 runGuardedPublic 的命令临界区内（commandRunning 已占用），
        // 直接同步执行；若再套一层异步命令守卫会二次 CAS 失败而空跑。
        runSelectedCommand();
    }

    private EdlPackageInfo parseEdlPackageInfo(String basePath) {
        if (basePath == null || basePath.trim().isEmpty()) {
            return null;
        }
        File baseDir = new File(basePath.trim());
        if (!rootExists(baseDir.getAbsolutePath(), true)) {
            return null;
        }
        File imagesDir = resolveEdlImagesDir(baseDir);
        List<File> rawprograms = listXmlFiles(imagesDir, RAWPROGRAM_FILE_PATTERN);
        List<File> patches = listXmlFiles(imagesDir, PATCH_FILE_PATTERN);
        rawprograms = dedupeRawprogramVariants(filterLun5XmlFiles(rawprograms, RAWPROGRAM_FILE_PATTERN));
        patches = filterLun5XmlFiles(patches, PATCH_FILE_PATTERN);
        if (imagesDir != null && imagesDir != baseDir) {
            if (rawprograms.isEmpty()) {
                rawprograms = listXmlFiles(baseDir, RAWPROGRAM_FILE_PATTERN);
                rawprograms = dedupeRawprogramVariants(filterLun5XmlFiles(rawprograms, RAWPROGRAM_FILE_PATTERN));
            }
            if (patches.isEmpty()) {
                patches = listXmlFiles(baseDir, PATCH_FILE_PATTERN);
                patches = filterLun5XmlFiles(patches, PATCH_FILE_PATTERN);
            }
        }
        // OPPO/一加 .ops/.ofp 解包出的是 OPlus 工程配置(根<Setting>，OpsDecryptor 命名为
        // settings.xml)，不匹配 rawprogram*.xml 命名而被漏掉 → "未发现 rawprogram" 静默失败。
        // 按内容(根<Setting>)回退识别，当作 rawprogram(后续 convertOplusSettingXml 转成标准格式)。
        if (rawprograms.isEmpty()) {
            rawprograms = findOplusSettingXmls(imagesDir);
            if (rawprograms.isEmpty() && imagesDir != baseDir) {
                rawprograms = findOplusSettingXmls(baseDir);
            }
        }
        // 仍未发现：rawprogram/settings 可能在异名或嵌套子目录里(或用户选了包父目录)。有界递归(≤2 层)
        // 在子目录中查含 rawprogram*.xml/settings.xml 的目录，命中后以它作镜像目录。只在前面都落空时兜底。
        if (rawprograms.isEmpty()) {
            File pkgDir = findPackageXmlDir(baseDir, 2);
            if (pkgDir != null) {
                imagesDir = pkgDir;
                rawprograms = dedupeRawprogramVariants(filterLun5XmlFiles(
                        listXmlFiles(pkgDir, RAWPROGRAM_FILE_PATTERN), RAWPROGRAM_FILE_PATTERN));
                if (rawprograms.isEmpty()) {
                    rawprograms = findOplusSettingXmls(pkgDir);
                }
                patches = filterLun5XmlFiles(
                        listXmlFiles(pkgDir, PATCH_FILE_PATTERN), PATCH_FILE_PATTERN);
            }
        }
        return new EdlPackageInfo(baseDir, imagesDir, rawprograms, patches);
    }

    // 在 dir 及其子目录(有界深度)里找含 rawprogram*.xml 或 settings.xml 的目录，兼容厂商把刷写 XML
    // 放在异名/嵌套子目录、或用户选了包父目录的情形。返回首个命中的目录，找不到返回 null。
    private File findPackageXmlDir(File dir, int maxDepth) {
        if (dir == null || !rootExists(dir.getAbsolutePath(), true)) {
            return null;
        }
        if (!listXmlFiles(dir, RAWPROGRAM_FILE_PATTERN).isEmpty()
                || !findOplusSettingXmls(dir).isEmpty()) {
            return dir;
        }
        if (maxDepth <= 0) {
            return null;
        }
        for (String name : rootListNames(dir.getAbsolutePath())) {
            if (name == null) {
                continue;
            }
            File sub = new File(dir, name);
            if (rootExists(sub.getAbsolutePath(), true)) {
                File found = findPackageXmlDir(sub, maxDepth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // 扫描目录里根元素为 <Setting> 的 XML(OPlus 官方工程配置/settings.xml)，用于 .ops/.ofp
    // 解包后识别出工程文件——它不叫 rawprogram*.xml，标准命名匹配会漏掉。
    private List<File> findOplusSettingXmls(File dir) {
        List<File> result = new ArrayList<>();
        if (dir == null || !rootExists(dir.getAbsolutePath(), true)) {
            return result;
        }
        for (String name : rootListNames(dir.getAbsolutePath())) {
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".xml")) {
                continue;
            }
            File f = new File(dir, name);
            if (isOplusSettingXml(f)) {
                result.add(f);
            }
        }
        return result;
    }

    private boolean isOplusSettingXml(File f) {
        if (f == null || !rootExists(f.getAbsolutePath(), false)) {
            return false;
        }
        byte[] data = rootReadBytes(f.getAbsolutePath());
        if (data == null || data.length == 0) {
            return false;
        }
        String head = new String(data, 0, Math.min(data.length, 1024), StandardCharsets.UTF_8);
        return head.contains("<Setting");
    }

    private boolean isOplusPackageFile(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.trim().toLowerCase(Locale.US);
        return lower.endsWith(".ofp") || lower.endsWith(".ops");
    }

    private File resolveEdlImagesDir(File baseDir) {
        if (baseDir == null) {
            return null;
        }
        // 兼容各 OEM 的镜像子目录命名：IMAGES/images(高通标准)、image(单数，联想等)、Image。
        for (String sub : new String[]{"IMAGES", "images", "image", "Image"}) {
            File candidate = new File(baseDir, sub);
            if (rootExists(candidate.getAbsolutePath(), true)) {
                return candidate;
            }
        }
        return baseDir;
    }

    private List<File> listXmlFiles(File dir, Pattern pattern) {
        List<File> result = new ArrayList<>();
        if (dir == null || !rootExists(dir.getAbsolutePath(), true)) {
            return result;
        }
        // root 列目录（scoped storage 下 File.listFiles 会被拦）
        for (String name : rootListNames(dir.getAbsolutePath())) {
            if (name != null && pattern.matcher(name).matches()) {
                result.add(new File(dir, name));
            }
        }
        Collections.sort(result, (a, b) -> {
            int idxA = parseXmlIndex(a.getName(), pattern);
            int idxB = parseXmlIndex(b.getName(), pattern);
            if (idxA != idxB) {
                return Integer.compare(idxA, idxB);
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return result;
    }

    private List<File> filterLun5XmlFiles(List<File> files, Pattern pattern) {
        if (!isProtectLun5Enabled() || files == null || files.isEmpty()) {
            return files;
        }
        List<File> filtered = new ArrayList<>();
        for (File file : files) {
            if (file == null) {
                continue;
            }
            int index = parseXmlIndex(file.getName(), pattern);
            if (index == 5) {
                continue;
            }
            filtered.add(file);
        }
        return filtered;
    }

    // 同一 LUN 索引若同时存在 rawprogramN.xml 与 rawprogram_unsparseN.xml，二者是同一刷写的
    // sparse/raw 两种表示，刷两遍会重复写入(尤其 super 巨大)，必须只留一个。优先镜像缺失最少的变体
    // (覆盖"常规缺图、unsparse 有图"的包)，打平再优先常规变体(向后兼容)。镜像按各 XML 所在目录解析；
    // 解析不到时两变体同样计为全缺→平局→回退优先常规，故不会因目录差异引入回归。
    private List<File> dedupeRawprogramVariants(List<File> files) {
        if (files == null || files.size() < 2) {
            return files;
        }
        Map<Integer, List<File>> byIndex = new HashMap<>();
        for (File f : files) {
            if (f == null) {
                continue;
            }
            int idx = parseXmlIndex(f.getName(), RAWPROGRAM_FILE_PATTERN);
            List<File> group = byIndex.get(idx);
            if (group == null) {
                group = new ArrayList<>();
                byIndex.put(idx, group);
            }
            group.add(f);
        }
        List<File> out = new ArrayList<>();
        for (List<File> group : byIndex.values()) {
            if (group.size() == 1) {
                out.add(group.get(0));
                continue;
            }
            File best = null;
            int bestMissing = Integer.MAX_VALUE;
            boolean bestRegular = false;
            for (File f : group) {
                int missing = countMissingFlashableImages(f);
                boolean regular = !isUnsparseRawprogram(f.getName());
                if (best == null || missing < bestMissing
                        || (missing == bestMissing && regular && !bestRegular)) {
                    best = f;
                    bestMissing = missing;
                    bestRegular = regular;
                }
            }
            out.add(best);
        }
        return out;
    }

    // 统计该 rawprogram XML 里"可刷分区"中镜像缺失的数量(按 XML 所在目录解析，兼容 .img/.bin)
    private int countMissingFlashableImages(File xml) {
        if (xml == null) {
            return Integer.MAX_VALUE;
        }
        File dir = xml.getParentFile();
        int flashable = 0;
        int missing = 0;
        for (ProgramEntry e : parseRawprogramPrograms(xml)) {
            if (!isFlashableProgramEntry(e)) {
                continue;
            }
            flashable++;
            File img = resolveProgramImageFile(e.filename, null, dir);
            if (img == null || !rootExists(img.getAbsolutePath(), false)) {
                missing++;
            }
        }
        // 解析失败/空内容/全 GPT 元数据的变体可刷项为 0，不能记为"0 缺图"而击败有效变体，
        // 否则去重会优选损坏变体导致该 LUN 整体不刷(对齐 dedupeRawprogramVariants 取最少缺图语义)。
        if (flashable == 0) {
            return Integer.MAX_VALUE;
        }
        return missing;
    }

    private boolean isUnsparseRawprogram(String name) {
        return name != null && name.toLowerCase(Locale.US).contains("_unsparse");
    }

    private int parseXmlIndex(String name, Pattern pattern) {
        if (name == null) {
            return Integer.MAX_VALUE;
        }
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches()) {
            return Integer.MAX_VALUE;
        }
        String num = matcher.group(1);
        if (num == null || num.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    // 首屏卡片错峰浮现：给内容容器挂上 layoutAnimation 并调度一次

    private BuiltinAuthFiles findBuiltinAuthFiles(String vendorDir, String chip, String devprgChoice) {
        if (vendorDir == null || chip == null) {
            return new BuiltinAuthFiles(null, null, null);
        }
        String base = "loader_builtin/" + vendorDir + "/" + chip;
        try {
            String[] files = getAssets().list(base);
            if (files == null) {
                return new BuiltinAuthFiles(null, null, null);
            }
            String chipKey = getChipKey(chip);
            String devprg = (devprgChoice != null && !devprgChoice.trim().isEmpty())
                    ? devprgChoice : pickDevprgFile(files, chipKey);
            String digest = null;
            String signature = null;
            // 多镜像引导(qsahara XML)整组镜像本就不含 VIP digest/sign。其中
            // signed_firmware_soc_view.elf 文件名含 "sign" 会被 pickSignatureFile 误判为
            // 签名文件，进而触发"digest/sign 不匹配"把刷写流程前置拦截。检测到 Sahara
            // 配置时直接不带 digest/sign，走普通 Sahara 上传。
            if (findSaharaConfig(files) == null) {
                String loaderKey = stripExtension(devprg);
                digest = pickDigestFile(files, chipKey, loaderKey);
                signature = pickSignatureFile(files, chipKey, loaderKey);
            }
            if (devprg != null) {
                devprg = base + "/" + devprg;
            }
            if (digest != null) {
                digest = base + "/" + digest;
            }
            if (signature != null) {
                signature = base + "/" + signature;
            }
            return new BuiltinAuthFiles(devprg, digest, signature);
        } catch (IOException e) {
            return new BuiltinAuthFiles(null, null, null);
        }
    }

    private String getChipKey(String chip) {
        if (chip == null) {
            return "";
        }
        String[] parts = chip.trim().split("_");
        if (parts.length == 0) {
            return chip.trim();
        }
        return parts[0].trim();
    }

    private String pickDevprgFile(String[] files, String chipKey) {
        List<String> candidates = new ArrayList<>();
        for (String file : files) {
            String lower = file.toLowerCase(Locale.US);
            if (!isLoaderFile(lower)) {
                continue;
            }
            if (lower.contains("digest") || lower.contains("sign")) {
                continue;
            }
            candidates.add(file);
        }
        // 用数字版本序(与 UI 默认 choosePreferredLoaderCandidate 一致)而非字母序，
        // 避免多 devprg 目录下自动选择与界面显示的 preferred 程序器漂移
        String chosen = pickByContainsPreferred(candidates, "devprg");
        if (chosen == null) {
            chosen = pickByContainsPreferred(candidates, "prog_firehose");
        }
        if (chosen == null) {
            chosen = pickByContainsPreferred(candidates, "firehose");
        }
        if (chosen == null && chipKey != null && !chipKey.isEmpty()) {
            chosen = pickByContainsPreferred(candidates, chipKey);
        }
        if (chosen == null) {
            chosen = choosePreferredLoaderCandidate(candidates);
        }
        return chosen;
    }

    private String pickByContainsPreferred(List<String> candidates, String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        List<String> matches = new ArrayList<>();
        for (String file : candidates) {
            if (containsIgnoreCase(file, token)) {
                matches.add(file);
            }
        }
        return choosePreferredLoaderCandidate(matches);
    }

    private String pickDigestFile(String[] files, String chipKey, String loaderKey) {
        List<String> candidates = new ArrayList<>();
        for (String file : files) {
            String lower = file.toLowerCase(Locale.US);
            if (!lower.contains("digest") || lower.endsWith(".txt")) {
                continue;
            }
            candidates.add(file);
        }
        String chosen = null;
        if (loaderKey != null && !loaderKey.isEmpty()) {
            chosen = pickByContains(candidates, loaderKey);
        }
        if (chosen == null && chipKey != null && !chipKey.isEmpty()) {
            chosen = pickByContains(candidates, chipKey);
        }
        if (chosen == null) {
            chosen = pickExactBaseName(candidates, "digest");
        }
        if (chosen == null) {
            chosen = choosePreferred(candidates);
        }
        return chosen;
    }

    private String pickSignatureFile(String[] files, String chipKey, String loaderKey) {
        List<String> candidates = new ArrayList<>();
        for (String file : files) {
            String lower = file.toLowerCase(Locale.US);
            if (!lower.contains("sign") || lower.contains("digest") || lower.endsWith(".txt")) {
                continue;
            }
            candidates.add(file);
        }
        String chosen = null;
        if (loaderKey != null && !loaderKey.isEmpty()) {
            chosen = pickByContains(candidates, loaderKey);
        }
        if (chosen == null && chipKey != null && !chipKey.isEmpty()) {
            chosen = pickByContains(candidates, chipKey);
        }
        if (chosen == null) {
            chosen = pickExactBaseName(candidates, "sign");
        }
        if (chosen == null) {
            chosen = choosePreferred(candidates);
        }
        return chosen;
    }

    private boolean isLoaderFile(String lower) {
        if (lower.endsWith(".melf") || lower.endsWith(".elf") || lower.endsWith(".mbn")) {
            return true;
        }
        return lower.contains("devprg") || lower.contains("prog_firehose") || lower.contains("firehose");
    }

    private List<String> listDevprgCandidates(String[] files) {
        // 多镜像引导：整组镜像由 qsahara_device_programmer.xml 描述，qdl 解析它后按
        // image_id 上传全部镜像。此时只把这个 XML 作为唯一引导项，避免把 xbl_sc.elf /
        // multi_image.mbn / prog_firehose_ddr.elf 等当成可单独发送的引导误导用户。
        String saharaConfig = findSaharaConfig(files);
        if (saharaConfig != null) {
            List<String> single = new ArrayList<>();
            single.add(saharaConfig);
            return single;
        }
        List<String> candidates = new ArrayList<>();
        for (String file : files) {
            String lower = file.toLowerCase(Locale.US);
            if (!isLoaderFile(lower)) {
                continue;
            }
            if (lower.contains("digest") || lower.contains("sign")) {
                continue;
            }
            candidates.add(file);
        }
        candidates.sort(String.CASE_INSENSITIVE_ORDER);
        return candidates;
    }

    private String findSaharaConfig(String[] files) {
        if (files == null) {
            return null;
        }
        for (String file : files) {
            if (SAHARA_CONFIG_NAME.equalsIgnoreCase(file)) {
                return file;
            }
        }
        return null;
    }

    private String stripExtension(String name) {
        if (name == null) {
            return null;
        }
        int idx = name.lastIndexOf('.');
        if (idx <= 0) {
            return name;
        }
        return name.substring(0, idx);
    }

    private String pickByContains(List<String> candidates, String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        List<String> matches = new ArrayList<>();
        for (String file : candidates) {
            if (containsIgnoreCase(file, token)) {
                matches.add(file);
            }
        }
        return choosePreferred(matches);
    }

    private String pickExactBaseName(List<String> candidates, String baseName) {
        if (candidates == null || candidates.isEmpty() || baseName == null || baseName.isEmpty()) {
            return null;
        }
        for (String file : candidates) {
            String base = stripExtension(file);
            if (baseName.equalsIgnoreCase(base)) {
                return file;
            }
        }
        return null;
    }

    private boolean containsIgnoreCase(String file, String token) {
        return file.toLowerCase(Locale.US).contains(token.toLowerCase(Locale.US));
    }

    private String choosePreferred(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(candidates);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted.get(sorted.size() - 1);
    }

    private String choosePreferredLoaderCandidate(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(candidates);
        sorted.sort((left, right) -> {
            int leftVersion = extractTrailingNumber(stripExtension(left));
            int rightVersion = extractTrailingNumber(stripExtension(right));
            if (leftVersion != rightVersion) {
                return Integer.compare(rightVersion, leftVersion);
            }
            return right.compareToIgnoreCase(left);
        });
        return sorted.get(0);
    }

    private int extractTrailingNumber(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        int end = value.length() - 1;
        while (end >= 0 && Character.isDigit(value.charAt(end))) {
            end--;
        }
        if (end == value.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(value.substring(end + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02X", b));
        }
        return sb.toString();
    }

    private void logFileInfo(File runDir, String label, File file) {
        if (file == null || runDir == null) {
            return;
        }
        long size = file.length();
        String sha256 = null;
        try {
            sha256 = sha256File(file);
        } catch (IOException e) {
            appendWorkLog(runDir, label + " SHA256 失败: " + e.getMessage());
        }
        if (sha256 != null && !sha256.isEmpty()) {
            appendWorkLog(runDir,
                    label + ": " + file.getAbsolutePath() + " (" + size + " bytes, sha256=" + sha256 + ")");
        } else {
            appendWorkLog(runDir, label + ": " + file.getAbsolutePath() + " (" + size + " bytes)");
        }
    }

    private String sha256File(File file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
        }
        return bytesToHex(md.digest());
    }

    // Run a background task without ever letting an unexpected Throwable
    // (e.g. a LinkageError on an older Android build) crash the whole app.
    private void runGuarded(Runnable task) {
        try {
            task.run();
        } catch (CommandCanceledException ce) {
            // 用户主动取消属正常路径：保留 qdl 已落的真实原因（如"设备未检测到"），
            // 没有则记为"已取消"，不要覆盖成"执行异常"误导用户。
            if (lastErrorReason == null || lastErrorReason.trim().isEmpty()) {
                recordErrorReason("已取消");
            }
            finishProgress(false);
        } catch (Throwable t) {
            // "权限不足" 只是这里对任意未捕获异常的兜底提示，常掩盖真实根因。把异常类型/消息/完整
            // 堆栈写进日志(UI + 摘要日志)，真机测试时可据此定位实际抛错点，而非误以为是权限问题。
            String detail = t.getClass().getName() + ": " + t.getMessage();
            recordErrorReason("执行异常: " + detail);
            String trace = Log.getStackTraceString(t);
            cb.onLog("执行异常(显示为权限不足实为此异常): " + detail);
            cb.onLog("异常堆栈:\n" + trace);
            appendSummaryLog("执行异常(误报权限不足): " + detail + "\n" + trace);
            showToast(getString(R.string.error_permission));
            finishProgress(false);
        }
    }

    // 停止当前操作：置取消位并立即 destroy 正在跑的 qdl 进程（看门狗下一轮也会兜底）。
    private void cancelCurrentCommand() {
        if (!commandRunning.get()) {
            return;
        }
        commandCanceled.set(true);
        Process process = activeProcess.get();
        if (process != null) {
            process.destroy();
        }
        showToast(getString(R.string.toast_command_canceling));
        // headless: 停止按钮状态由外层维护，引擎不持有视图
    }

    // 运行期禁用三入口、显示停止按钮，杜绝并发点击。
    private void setOperationButtonsRunning(boolean running) {
        // headless: 按钮启用/可见性由外层依据运行状态维护，引擎不持有视图
    }

    // 在每段 qdl 启动前检查取消，覆盖"取消落在非 qdl 准备阶段"的情形。
    private void throwIfCommandCanceled() {
        if (commandCanceled.get()) {
            throw new CommandCanceledException("用户已取消");
        }
    }

    private synchronized void requestRoot() {
        if (rootAvailable) {
            return;   // 已持有 root：避免命令线程与后台探测线程并发重入、互相 close 常驻 su shell
        }
        setRootStatus(getString(R.string.root_status_requesting));
        try {
            String binDir = new File(getFilesDir(), "bin").getAbsolutePath();
            // 启动时开一个常驻 root shell（只在此处申请一次 su），后续命令全部复用
            rootShell.open();
            CommandResult result = runCommandWithRoot(null, "id", false, binDir);
            if (result.exitCode == 0) {
                // Prepare the edl bin dir BEFORE marking root available, so a
                // command started right after the grant doesn't race an
                // unfinished prepare on the single-thread executor.
                prepareRootEdl();
                rootAvailable = true;
                grantStorageAccess();
                setRootStatus(getString(R.string.root_status_granted));
            } else {
                rootAvailable = false;
                setRootStatus(getString(R.string.root_status_denied));
            }
        } catch (IOException | InterruptedException e) {
            rootAvailable = false;
            setRootStatus(getString(R.string.root_status_unavailable));
        } catch (Throwable t) {
            // Never let an unexpected failure (e.g. a LinkageError on an older
            // platform) crash the app while probing for root.
            rootAvailable = false;
            setRootStatus(getString(R.string.root_status_unavailable));
        }
    }

    private void setRootStatus(String status) {
        cb.onDeviceStatus(status);
    }

    // root 自授“所有文件访问”，使应用进程(OFP/OPS 解密器等)直读真实外部路径，
    // 无需把固件复制到私有目录。失败不致命：从内部存储真实路径选取在多数设备仍可用。
    private void grantStorageAccess() {
        String pkg = getPackageName();
        String binDir = new File(getFilesDir(), "bin").getAbsolutePath();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                runCommandWithRoot(null, "appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow", false, binDir);
            } else {
                runCommandWithRoot(null, "pm grant " + pkg + " android.permission.READ_EXTERNAL_STORAGE", false, binDir);
            }
        } catch (Exception e) {
            // 忽略：自授失败仅影响从外部存储直读，root 刷写本身不受影响
        }
    }

    private File getRootEdlDir() {
        return new File(getFilesDir(), ROOT_EDL_SUBDIR);
    }

    private String getRootEdlBinDir() {
        return new File(getRootEdlDir(), "bin").getAbsolutePath();
    }

    private String getRootEdlLibDir() {
        return new File(getRootEdlDir(), "lib").getAbsolutePath();
    }

    private void ensureEdlExtracted() {
        File edlDir = getRootEdlDir();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String installed = prefs.getString(PREF_ASSET_VERSION, "");
        boolean forceOverwrite = !ASSET_VERSION.equals(installed);
        if (forceOverwrite) {
            deleteRecursive(edlDir);
        }
        if (!edlDir.exists() && !edlDir.mkdirs()) {
            return;
        }
        try {
            extractAssetDir(ASSET_EDL_DIR, edlDir, forceOverwrite);
            refreshEdlBinary(new File(edlDir, "bin"), TOOL_QDL);
            prefs.edit().putString(PREF_ASSET_VERSION, ASSET_VERSION).apply();
            if (rootAvailable) {
                prepareRootEdl();
            }
        } catch (IOException ignored) {
        }
    }

    private void extractAssetDir(String assetDir, File outDir, boolean overwrite) throws IOException {
        String[] entries = getAssets().list(assetDir);
        if (entries == null) {
            return;
        }
        for (String entry : entries) {
            String assetPath = assetDir + "/" + entry;
            String[] children = getAssets().list(assetPath);
            File outFile = new File(outDir, entry);
            if (children != null && children.length > 0) {
                if (!outFile.exists() && !outFile.mkdirs()) {
                    throw new IOException(getString(R.string.error_create_dir) + outFile.getAbsolutePath());
                }
                extractAssetDir(assetPath, outFile, overwrite);
            } else {
                if (!overwrite && outFile.exists()) {
                    chmodFile(outFile);
                    continue;
                }
                try (InputStream in = getAssets().open(assetPath);
                     OutputStream out = new FileOutputStream(outFile)) {
                    copyStream(in, out);
                }
                chmodFile(outFile);
            }
        }
    }

    private void refreshEdlBinary(File binDir, String toolName) throws IOException {
        if (binDir == null || toolName == null || toolName.trim().isEmpty()) {
            return;
        }
        if (!binDir.exists() && !binDir.mkdirs()) {
            return;
        }
        String assetPath = ASSET_EDL_DIR + "/bin/" + toolName;
        File outFile = new File(binDir, toolName);
        try (InputStream in = getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(outFile)) {
            copyStream(in, out);
        }
        chmodFile(outFile);
    }

    private void chmodFile(File file) {
        try {
            Os.chmod(file.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            file.setReadable(true, false);
            file.setExecutable(true, false);
        }
    }

    private void prepareRootEdl() {
        File edlDir = getRootEdlDir();
        String cmd = "if [ -d " + shQuote(edlDir.getAbsolutePath()) + " ]; then "
                + "chmod -R 755 " + shQuote(new File(edlDir, "bin").getAbsolutePath()) + " ; "
                + "fi";
        try {
            runCommandWithRoot(null, cmd, false, edlDir.getAbsolutePath());
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private void runSelectedCommand() {
        int selectedIndex = commandSpinner.getSelectedItemPosition();
        String command = selectedIndex >= 0 && selectedIndex < COMMANDS.length
                ? COMMANDS[selectedIndex] : null;
        if ("sign".equals(command)) {
            runSignTool();
        } else {
            runEdl();
        }
    }

    // 演练(--dry-run)仅适用于写/擦命令：qdl -n 把整机切到 SIM 后端，
    // 用于读命令会产出全零垃圾镜像，故按当前选中命令类型门控。
    private boolean dryRunApplies() {
        if (!skipWriteCheck.isChecked()) {
            return false;
        }
        int idx = commandSpinner.getSelectedItemPosition();
        String cmd = idx >= 0 && idx < COMMANDS.length ? COMMANDS[idx] : "";
        switch (cmd) {
            case "w": case "wl": case "wf": case "ws": case "qfil":
            case "e": case "es": case "ep":
            // provision 会重分区/调整 UFS LUN，破坏性强于 erase，演练模式必须也注入 --dry-run(走 SIM 后端)
            case "provision":
            case "poke": case "pokehex": case "pokedword": case "pokeqword": case "memcpy":
                return true;
            default:
                return false;
        }
    }

    // 本次下发的 XML 是否真含写/擦操作。dryRunApplies() 按选中命令快速门控，但写/擦流程
    // 内部会复用 runQdlXmlCommand 做隐式 GPT/storageinfo 读取(纯 <read>/<getstorageinfo>)，
    // 那些读取不能注入 --dry-run，否则 qdl -n 切 SIM 读回全零，演练在预检阶段误判失败。
    private boolean xmlPayloadIsDestructive(List<File> xmlFiles) {
        if (xmlFiles == null || xmlFiles.isEmpty()) {
            return false;
        }
        for (File xmlFile : xmlFiles) {
            if (xmlFile == null) {
                continue;
            }
            // 用户选中的写入载荷常落在 /storage/emulated/0/... 外部路径，分区存储下
            // 非 root 的 readFileText 读不到。优先 app 直读(内部读 XML 落在私有 runDir)，
            // 读不到再走 root，与全局"真实路径供 root 直读"一致。
            String xml = readFileText(xmlFile);
            if (xml == null) {
                byte[] bytes = rootReadBytes(xmlFile.getAbsolutePath());
                if (bytes != null) {
                    xml = new String(bytes, StandardCharsets.UTF_8);
                }
            }
            if (xml == null) {
                // 文件存在(root 视角)却无法读取内容：无法证明它是纯内部读，按破坏性兜底，
                // 宁可对潜在写载荷注入 --dry-run(误判读 XML 只产生垃圾数据，绝不写盘)，
                // 也不能漏判真实 program/erase 而把演练降级成真刷。
                if (rootExists(xmlFile.getAbsolutePath(), false)) {
                    return true;
                }
                continue;
            }
            String lower = xml.toLowerCase(Locale.US);
            if (lower.contains("<program")
                    || lower.contains("<patch")
                    || lower.contains("<erase")
                    || lower.contains("<ufs")
                    || lower.contains("<poke")) {
                return true;
            }
        }
        return false;
    }

    private void runEdl() {
        clearLog();
        setActiveVipFiles(null, null);
        startProgress("准备执行");
        refreshVipAuthStateForRun();
        int selectedIndex = commandSpinner.getSelectedItemPosition();
        String command = selectedIndex >= 0 && selectedIndex < COMMANDS.length
                ? COMMANDS[selectedIndex] : null;
        if (command == null || command.isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            finishProgress(false);
            return;
        }
        if (!rootAvailable) {
            requestRoot();
            if (!rootAvailable) {
                showToast(getString(R.string.error_permission));
                finishProgress(false);
                return;
            }
        }
        cleanupWorkDir();
        File runDir = new File(getFilesDir(), "work/run_" + System.currentTimeMillis());
        if (!runDir.mkdirs()) {
            finishProgress(false);
            return;
        }
        ensureDir(new File(runDir, "logs"));

        if (hasAuthFilesMismatch()) {
            showToast(getString(R.string.error_missing_digest_sign));
            finishProgress(false);
            return;
        }
        File loaderFile = null;
        File digestFile = null;
        File authLoaderFile = null;
        boolean useVipMode = hasAuthFilesConfigured();
        if (builtinVendorDir != null) {
            if (builtinDevprgAssetPath == null) {
                showToast(getString(R.string.builtin_loader_missing));
                finishProgress(false);
                return;
            }
            try {
                loaderFile = copyBuiltinLoader(runDir, builtinDevprgAssetPath);
                if (useVipMode && builtinDigestAssetPath != null) {
                    digestFile = copyBuiltinLoader(runDir, builtinDigestAssetPath);
                }
                if (useVipMode && builtinSignAssetPath != null) {
                    authLoaderFile = copyBuiltinLoader(runDir, builtinSignAssetPath);
                }
            } catch (IOException e) {
                finishProgress(false);
                return;
            }
        } else if (loaderUri != null) {
            loaderFile = resolveUriToFile(loaderUri, getString(R.string.loader_devprg_title));
            if (loaderFile == null) {
                finishProgress(false);
                return;
            }
        }
        if (builtinVendorDir == null) {
            if (useVipMode && digestUri != null) {
                digestFile = resolveUriToFile(digestUri, getString(R.string.loader_digest_title));
                if (digestFile == null) {
                    finishProgress(false);
                    return;
                }
            }
            if (useVipMode && signUri != null) {
                authLoaderFile = resolveUriToFile(signUri, getString(R.string.loader_sig_title));
                if (authLoaderFile == null) {
                    finishProgress(false);
                    return;
                }
            }
        }
        if (loaderFile == null) {
            showToast(getString(R.string.loader_devprg_none));
            finishProgress(false);
            return;
        }
        setActiveVipFiles(useVipMode ? digestFile : null, useVipMode ? authLoaderFile : null);
        String arg1 = arg1Input.getText().toString().trim();
        String arg2 = arg2Input.getText().toString().trim();
        String arg3 = arg3Input.getText().toString().trim();

        arg1 = resolveArg(arg1, arg1Uri, runDir, arg1Type);
        arg2 = resolveArg(arg2, arg2Uri, runDir, arg2Type);
        arg3 = resolveArg(arg3, arg3Uri, runDir, arg3Type);

        String outputName = outputNameInput.getText().toString().trim();
        CommandSpec spec = COMMAND_SPECS.get(command);
        if (spec == null) {
            arg1 = "";
            arg2 = "";
            arg3 = "";
        }
        QfilInputs qfilInputs = null;
        if ("qfil".equals(command)) {
            List<File> rawMulti = arg1Type == ArgType.FILE_MULTI
                    ? resolveUriListToFiles(arg1UriList, runDir) : new ArrayList<>();
            List<File> patchMulti = arg2Type == ArgType.FILE_MULTI
                    ? resolveUriListToFiles(arg2UriList, runDir) : new ArrayList<>();
            qfilInputs = resolveQfilInputs(arg1, arg2, arg3, rawMulti, patchMulti);
            qfilInputs = prepareQfilInputs(runDir, qfilInputs);
            if (qfilInputs != null) {
                maybePrepareSuperImage(runDir, qfilInputs);
                if (qfilInputs.rawprogramPath != null) {
                    arg1 = qfilInputs.rawprogramPath;
                }
                if (qfilInputs.patchPath != null) {
                    arg2 = qfilInputs.patchPath;
                }
                if (qfilInputs.imageDirPath != null) {
                    arg3 = qfilInputs.imageDirPath;
                }
                appendWorkLog(runDir, "QFIL 输入解析: rawprogram=" + arg1
                        + " | patch=" + (arg2 == null || arg2.isEmpty() ? "(无)" : arg2)
                        + " | 镜像目录=" + arg3
                        + " | 包数=" + qfilInputs.rawprogramFiles.size());
            }
        }
        boolean outputIsDir = "rl".equals(command) || "gpt".equals(command);
        boolean outputIsFile = spec != null && spec.outputArgIndex > 0 && !outputIsDir;
        if ("r".equals(command)) {
            outputName = buildDownloadImagePath(arg1);
        } else if (outputIsFile) {
            outputName = buildReadOutputPath(command, arg1, arg2, arg3, outputName);
        } else if (outputIsDir) {
            outputName = buildDownloadDirPath(command, outputName);
        } else if (outputName.isEmpty()) {
            outputName = "edl_output.img";
        } else if (!outputName.toLowerCase(Locale.US).endsWith(".img")) {
            outputName = outputName + ".img";
        }
        String outputPathForSave = null;
        if ("r".equals(command)) {
            arg2 = outputName;
            outputPathForSave = outputName;
        } else if (spec != null && spec.outputArgIndex > 0) {
            String outputPath = normalizeUserOutputPath(outputName);
            if (outputPath == null || outputPath.trim().isEmpty()) {
                outputPath = outputName;
            }
            if (spec.outputArgIndex == 1) {
                arg1 = outputPath;
                outputPathForSave = outputPath;
            } else if (spec.outputArgIndex == 2) {
                arg2 = outputPath;
                outputPathForSave = outputPath;
            } else if (spec.outputArgIndex == 3) {
                arg3 = outputPath;
                outputPathForSave = outputPath;
            }
        }
        // qfil 不走通用 requiredArgs 门：patch(arg2) 可选，validateQfilInputs 才是权威校验（只要 rawprogram+镜像目录）。
        if (spec != null && !"qfil".equals(command) && !spec.hasRequiredArgs(arg1, arg2, arg3)) {
            appendWorkLog(runDir, "缺少必填参数（命令 " + command + "）");
            showToast(getString(R.string.toast_missing_required));
            finishProgress(false);
            return;
        }
        if (outputIsFile) {
            ensureDownloadDirExists();
        } else if (outputIsDir) {
            ensureDirExists(outputName);
        }
        if ("qfil".equals(command)) {
            String error = validateQfilInputs(arg1, arg2, arg3, runDir);
            if (error != null) {
                appendWorkLog(runDir, "QFIL 校验失败: " + error);
                // qfil 失败原因同步进 UI 日志：appendWorkLog 只落磁盘，toast 转瞬即逝，否则用户只见秒退无从排查
                appendSummaryLog("QFIL 校验失败: " + error);
                showToast(error);
                finishProgress(false);
                return;
            }
        }

        boolean handled = runFhCommand(runDir, command, loaderFile, arg1, arg2, arg3, qfilInputs);
        if (!handled) {
            appendStepResult("执行", false, "命令未处理");
            finishProgress(false);
        }
    }

    private boolean runFhCommand(File runDir, String command, File loaderFile,
                                 String arg1, String arg2, String arg3, QfilInputs qfilInputs) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String cmd = command.trim().toLowerCase(Locale.US);
        boolean ok;
        switch (cmd) {
            case "server":
                ok = runFhServer(runDir, loaderFile);
                finishProgress(ok);
                return true;
            case "gpt":
            case "printgpt":
                startLogProgressMonitor(new File(runDir, "run.log"), "读取中");
                ok = runFhGptList(runDir, loaderFile);
                updateCachedStorageInfoFromLog(runDir);
                finishProgress(ok);
                return true;
            case "r":
                if (pendingMultiRead != null) {
                    List<PartitionOption> sel = pendingMultiRead;
                    pendingMultiRead = null;
                    ok = runFhReadSelected(runDir, loaderFile, sel);
                } else {
                    ok = runFhReadPartition(runDir, loaderFile, arg1, arg2);
                }
                finishProgress(ok);
                return true;
            case "rs":
                ok = runFhReadSectorsCommand(runDir, loaderFile, arg1, arg2, arg3);
                finishProgress(ok);
                return true;
            case "rf":
                ok = runFhReadFull(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "rl":
                ok = runFhReadAll(runDir, loaderFile);
                finishProgress(ok);
                return true;
            case "w":
                ok = runFhWritePartition(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "wl":
                ok = runFhWriteAll(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "wf":
                ok = runFhWriteFull(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "ws":
                ok = runFhWriteSectors(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "e":
                ok = runFhErasePartition(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "es":
                ok = runFhEraseSectors(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "ep":
                ok = runFhErasePartitionSectors(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "footer":
                ok = runFhFooter(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "peek":
                ok = runFhPeek(runDir, loaderFile, arg1, arg2, arg3);
                finishProgress(ok);
                return true;
            case "peekhex":
                ok = runFhPeekHex(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "peekdword":
                ok = runFhPeekDword(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "peekqword":
                ok = runFhPeekQword(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "memtbl":
                ok = runFhDumpMemTable(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "poke":
                ok = runFhPokeFile(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "pokehex":
                ok = runFhPokeHex(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "pokedword":
                ok = runFhPokeDword(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "pokeqword":
                ok = runFhPokeQword(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "memcpy":
                ok = runFhMemcpy(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "secureboot":
                ok = runFhSecureboot(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "pbl":
                ok = runFhDumpPbl(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "qfp":
                ok = runFhDumpQfprom(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "memorydump":
                ok = runFhMemoryDump(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "provision":
                ok = runFhProvision(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "modules":
                ok = runFhModules(runDir, loaderFile, arg1, arg2);
                finishProgress(ok);
                return true;
            case "getstorageinfo":
                ok = runFhGetStorageInfo(runDir, loaderFile);
                finishProgress(ok);
                return true;
            case "setbootablestoragedrive":
                ok = runFhSetBootableStorageDrive(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "setactiveslot":
                ok = runFhSetActiveSlot(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "getactiveslot":
                ok = runFhGetActiveSlot(runDir, loaderFile);
                finishProgress(ok);
                return true;
            case "send":
                ok = runFhSendCommand(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "xml":
                ok = runFhSendXmlFile(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "rawxml":
                ok = runFhSendRawXml(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "reset":
                ok = runFhResetCommand(runDir, loaderFile, arg1);
                finishProgress(ok);
                return true;
            case "nop":
                ok = runFhNopCommand(runDir, loaderFile);
                finishProgress(ok);
                return true;
            case "qfil":
                ok = runFhQfilSplit(runDir, loaderFile, qfilInputs);
                if (ok && isAutoRebootEnabled()) {
                    appendWorkLog(runDir, "刷写完成，发送重启指令...");
                    if (!runFhResetCommand(runDir, loaderFile, "reset")) {
                        appendWorkLog(runDir, "重启指令发送失败");
                    }
                }
                finishProgress(ok);
                return true;
            default:
                appendStepResult("执行", false, "命令不支持");
                finishProgress(false);
                return true;
        }
    }

    private FhContext buildFhContext(File runDir, File loaderFile, boolean needRwMode) {
        if (runDir == null) {
            return null;
        }
        if (loaderFile == null) {
            String reason = getString(R.string.loader_devprg_none);
            appendWorkLog(runDir, reason);
            recordErrorReason(reason);
            return null;
        }
        if (!isLikelyFirehose(loaderFile)) {
            String reason = "Firehose 文件格式不正确";
            appendWorkLog(runDir, reason);
            recordErrorReason(reason);
            return null;
        }
        resolveQdlPortArg();
        String memory = resolveQdlMemoryName();
        int sectorSize = resolveFhSectorSize();
        return new FhContext(loaderFile, "", memory, sectorSize, "");
    }

    private String resolveTargetName(File loaderFile) {
        if (loaderFile == null) {
            return null;
        }
        String model = deviceModelInput == null ? "" : deviceModelInput.getText().toString().trim();
        if (!model.isEmpty()) {
            return model;
        }
        String path = loaderFile.getAbsolutePath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        String[] tokens = path.split("[/\\\\\\s_\\-]+");
        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            String upper = token.toUpperCase(Locale.US);
            if (!upper.matches(".*\\d.*")) {
                continue;
            }
            if (upper.startsWith("SM") || upper.startsWith("MSM") || upper.startsWith("SDM")
                    || upper.startsWith("APQ") || upper.startsWith("MDM") || upper.startsWith("QCA")
                    || upper.startsWith("QDF") || upper.startsWith("IPQ") || upper.startsWith("QCS")
                    || upper.startsWith("SA") || upper.startsWith("SC") || upper.startsWith("WCN")) {
                return token;
            }
        }
        return null;
    }

    private boolean isLikelyFirehose(File file) {
        return file != null && file.exists();
    }

    private boolean runFhGptList(File runDir, File loaderFile) {
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return finishStep("读取分区表", false);
        }
        // 非 OPlus 设备 bulk 双向静默排查：把目标设备的接口/端点布局写入运行日志，
        // 便于对照 qdl 选中的接口/端点（定位"选错接口/端点导致 bulk 静默"）
        if (!shouldUseVipSpoof(ctx)) {
            logUsbDescriptorToWork(runDir);
        }
        // 非 OPlus 设备：跳过单独的 getstorageinfo 探测，避免其独立 qdl 进程抢掉
        // 后续读取 GPT 所需的那次 Sahara HELLO（部分 boot ROM 的 HELLO 只发一次）。
        // OPlus 走 --no-sahara 复用会话、不消费 HELLO，保留原探测。
        if (shouldUseVipSpoof(ctx)) {
            refreshCachedStorageInfo(runDir, ctx);
            // 读分区表时附带显示 DDR 类型（OPlus loader 的 <getddrtype/>）。仅 OPlus 调用：
            // 与 storageinfo 探测同位置同语义，走 --no-sahara 复用会话、不抢 Sahara HELLO。
            runFhProbeDdrType(runDir, ctx);
        }
        List<GptEntry> entries = readGptEntriesFromDeviceFh(runDir, ctx);
        if (entries == null || entries.isEmpty()) {
            appendWorkLog(runDir, "常规 GPT 读取为空，尝试主表读取");
            entries = readGptEntriesFromGptMainFiles(runDir, ctx);
        }
        if (entries == null || entries.isEmpty()) {
            appendWorkLog(runDir, "分区表读取失败");
            recordErrorReason("分区表读取失败");
            return finishStep("读取分区表", false);
        }
        boolean parsed = buildGptOutputs(runDir, entries);
        if (!parsed) {
            appendWorkLog(runDir, "分区表解析失败");
            recordErrorReason("分区表解析失败");
            return finishStep("读取分区表", false);
        }
        File zipFile = null;
        try {
            zipFile = zipGptOutputs(runDir);
        } catch (IOException e) {
            appendWorkLog(runDir, "分区表压缩失败: " + e.getMessage());
        }
        copyGptOutputsToDownload(runDir, zipFile);
        return finishStep("读取分区表", true);
    }

    private List<GptEntry> readGptEntriesFromDeviceFh(File runDir, FhContext ctx) {
        // 所有厂商（含 OPlus VIP）统一优先走单会话：一个 qdl 进程内一次读完各 LUN 的
        // GPT 区（头 + 表项区，探测窗口按最坏情况预留），对齐 bkerler/edl、linux-msm/qdl 的单连接读法，
        // 避免逐 LUN / 逐扇区重起 qdl 进程——后者是刷机读分区表卡顿的主因。
        // OPlus 的会话复用与 VIP 授权由 runQdlXmlCommand 按当前状态自动决定：已授权时
        // 加 --no-sahara 复用会话，未授权时带 loader + --signeddigests 现场建会话。
        // 单会话读空再回退逐 LUN 的 legacy，由其按候选扇区大小兜住非标准布局，无回归。
        List<GptEntry> single = readGptEntriesFromDeviceFhSingleSession(runDir, ctx);
        if (single != null && !single.isEmpty()) {
            return single;
        }
        appendWorkLog(runDir, "单会话 GPT 读取为空，回退常规读取");
        return readGptEntriesFromDeviceFhLegacy(runDir, ctx);
    }

    // 单会话读取设备 GPT：把所有 LUN 的 primary GPT 区（覆盖 GPT 头 + 表项）的读取
    // 合并到一个 qdl 进程/一次 Sahara 会话，捕获那唯一一次 HELLO 后连续读完所有 LUN。
    private List<GptEntry> readGptEntriesFromDeviceFhSingleSession(File runDir, FhContext ctx) {
        List<GptEntry> entries = new ArrayList<>();
        if (runDir == null || ctx == null || ctx.loaderFile == null) {
            return entries;
        }
        int sectorSize = ctx.sectorSize > 0 ? ctx.sectorSize : resolveGptSectorSize();
        long gptSectors = estimateGptMainProbeSectors(sectorSize);
        List<File> xmls = new ArrayList<>();
        List<File> dumps = new ArrayList<>();
        List<Integer> dumpLuns = new ArrayList<>();
        for (int lun : resolveGptLuns()) {
            File gptFile = new File(runDir, "gpt_main" + lun + ".bin");
            if (gptFile.exists()) {
                gptFile.delete();
            }
            File xmlFile = writeQdlReadXml(runDir, gptFile, lun, 0, gptSectors, sectorSize, "PrimaryGPT", true);
            if (xmlFile == null) {
                continue;
            }
            xmls.add(xmlFile);
            dumps.add(gptFile);
            dumpLuns.add(lun);
        }
        if (xmls.isEmpty()) {
            return entries;
        }
        appendWorkLog(runDir, "单会话读取设备 GPT（" + xmls.size() + " 个 LUN）");
        try {
            CommandResult result = runQdlXmlCommand(runDir, ctx.loaderFile, xmls, null, false);
            updateCachedStorageInfoFromLog(runDir);
            if (!isCommandSuccess(result)) {
                appendWorkLog(runDir, "单会话 GPT 读取未完全成功，尝试解析已落盘数据");
            }
        } catch (IOException | InterruptedException e) {
            appendWorkLog(runDir, "单会话 GPT 读取失败: " + e.getMessage());
            return entries;
        }
        boolean cachedSector = false;
        int lunsWithData = 0;
        for (int i = 0; i < dumps.size(); i++) {
            File gptFile = dumps.get(i);
            if (gptFile == null || rootFileSize(gptFile.getAbsolutePath()) <= 0) {
                continue;
            }
            lunsWithData++;
            List<GptEntry> parsed = parseGptMainFileWithCandidates(gptFile, sectorSize, dumpLuns.get(i));
            if (parsed.isEmpty()) {
                continue;
            }
            // 探测量不足以覆盖该 LUN 头声明的整个表项区：放弃单会话结果，回退 legacy 按头精确重读，
            // 避免静默漏掉探测窗口外的分区（写错位置的隐患）。
            if (!isGptMainDumpComplete(gptFile, sectorSize)) {
                appendWorkLog(runDir, "LUN" + dumpLuns.get(i) + " GPT 表项区超出单会话探测窗口，回退精确重读");
                return new ArrayList<>();
            }
            if (!cachedSector) {
                int parsedSector = parseIntSafe(parsed.get(0).sectorSize, sectorSize);
                if (parsedSector > 0) {
                    cacheBlockSize(parsedSector);
                    cachedSector = true;
                }
            }
            for (GptEntry entry : parsed) {
                if (!isGptMetaEntry(entry.name)) {
                    entries.add(entry);
                }
            }
        }
        // 对齐 md.7z(info.bat 读 LUN 到失败即停)：落盘有数据的 LUN 从 0 连续到 K，更高位 LUN 读不到=
        // 该 LUN 不存在(设备回 "Failed to open device ... lun")。统计从 0 起连续有数据的 LUN 数。
        int contiguousLuns = 0;
        for (File f : dumps) {
            if (f != null && rootFileSize(f.getAbsolutePath()) > 0) {
                contiguousLuns++;
            } else {
                break;
            }
        }
        if (lunsWithData > 0 && lunsWithData == contiguousLuns) {
            // 有数据的 LUN 全部从 0 连续，缺的都是高位【不存在】的 LUN→完整读取(非失败，qdl 因读不存在
            // 的 LUN 退非零属预期)。缓存真实 LUN 数(此前未知=-1)，后续读/GPT 操作不再枚举不存在的高位
            // LUN，消除 "Failed to open" 噪声与空跑——这正是 md.7z "读到失败即停"的等价效果。
            if (cachedNumPhysical <= 0) {
                cachedNumPhysical = lunsWithData;
                appendWorkLog(runDir, "探测到设备 LUN 数: " + lunsWithData + "(高位 LUN 不存在，已缓存)");
            }
            appendWorkLog(runDir, "单会话 GPT：完整读取 " + lunsWithData + " 个 LUN");
        } else {
            // 中间 LUN 缺数据=真实部分失败(非"高位不存在")，保留原诊断信息便于定位。
            appendWorkLog(runDir, "单会话 GPT：" + lunsWithData + "/" + dumps.size()
                    + " 个 LUN 落盘有数据(部分 LUN 缺失)");
        }
        return entries;
    }

    // 覆盖 PMBR + 主 GPT 头 + 分区表项区所需扇区数。按最坏情况预留(表项起始 LBA 最多 6、最多
    // 256 个 128B 表项 = 6*4096 + 256*128 = 57344 字节)，足以覆盖含 super/各 vendor 分区的大分区表。
    // 早期固定按 128 项(24576 字节)探测，对声明 >128 项的设备会静默漏掉后续分区——刷机写错位置的隐患。
    // 仍按 header 声明的 entryCount 精确解析；若极端设备超出此探测量，由完整性检测回退 legacy 精确重读。
    private long estimateGptMainProbeSectors(int sectorSize) {
        int size = sectorSize > 0 ? sectorSize : 4096;
        // 标准 GPT 保留区 = PMBR(1) + 主头(1) + 128 个 128B 表项 = first_usable_lba(4096→6, 512→34)。
        // 这恰是设备 PrimaryGPT 标签注册的可读范围；多读会被按 label 范围的 svip 权限拒绝(真机:
        // "read on PrimaryGPT:0:14 not allowed on external network")，致单会话/槽位/全盘读全部失败。
        // 声明 >128 项的非标准设备由 isGptMainDumpComplete 检出不完整后回退按头精确重读，不丢分区。
        long entrySectors = (128L * 128L + size - 1L) / size;
        return 2L + entrySectors;
    }

    // parseGptMainFile 只会从默认扇区大小回退到 512；这里按候选扇区大小逐个尝试，
    // 覆盖 512/4096 两种设备，避免漏解析
    private List<GptEntry> parseGptMainFileWithCandidates(File gptFile, int preferredSectorSize, int lun) {
        for (int candidate : buildGptSectorCandidates(preferredSectorSize)) {
            List<GptEntry> parsed = parseGptMainFile(gptFile, candidate, lun);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return new ArrayList<>();
    }

    // 校验 dump 是否完整覆盖 GPT 头声明的整个表项区(entryLba*ss + entryCount*entrySize)。不完整
    // 说明探测扇区数不足，需回退按头精确重读，否则 parseGptMainFile 会在缓冲区末尾静默截断分区。
    private boolean isGptMainDumpComplete(File gptFile, int preferredSectorSize) {
        byte[] data = gptFile == null ? null : rootReadBytes(gptFile.getAbsolutePath());
        if (data == null) {
            return false;
        }
        for (int ss : buildGptSectorCandidates(preferredSectorSize)) {
            int headerOffset = ss;
            if (data.length < headerOffset + 92) {
                continue;
            }
            if (!"EFI PART".equals(new String(data, headerOffset, 8, StandardCharsets.US_ASCII))) {
                continue;
            }
            long entryLba = readUInt64LE(data, headerOffset + 72);
            long entryCount = readUInt32LE(data, headerOffset + 80);
            int entrySize = (int) readUInt32LE(data, headerOffset + 84);
            if (entryLba <= 0 || entryCount <= 0 || entrySize <= 0) {
                continue;
            }
            return entryLba * (long) ss + entryCount * (long) entrySize <= data.length;
        }
        // 未解析出有效头：非空 parsed 时不会走到这里，返回 true 不阻断（由 parsed.isEmpty 处理）。
        return true;
    }

    private List<GptEntry> readGptEntriesFromDeviceFhLegacy(File runDir, FhContext ctx) {
        List<GptEntry> entries = new ArrayList<>();
        int sectorSize = ctx.sectorSize;
        boolean loggedSector = false;
        boolean foundAny = false;
        int knownSectorSize = 0;
        List<Integer> luns = resolveGptLuns();
        for (int lun : luns) {
            File headerFile = new File(runDir, "gpt_hdr_lun" + lun + ".bin");
            GptHeader header = null;
            int headerSectorSize = sectorSize;
            // 首个 LUN 成功后已知设备真实扇区大小，后续 LUN 只用它，省去 512/4096 反复试探
            List<Integer> sectorCandidates = knownSectorSize > 0
                    ? Collections.singletonList(knownSectorSize)
                    : buildGptSectorCandidates(sectorSize);
            for (int candidate : sectorCandidates) {
                boolean headerOk = runFhReadSectors(runDir, ctx, lun, 1, 1, candidate, headerFile,
                        "PrimaryGPT");
                if (!headerOk) {
                    continue;
                }
                header = parseGptHeaderFile(headerFile);
                if (header != null) {
                    headerSectorSize = candidate;
                    break;
                }
            }
            if (header == null) {
                // UFS 的 GPT 从 LUN0 起连续排布；已读到分区后再遇到无 GPT 的 LUN，即越过最后一个
                // 有效 LUN（对照 qdl gpt_load_tables 的 eof 终止），停止枚举，不再对不存在的高位
                // LUN 空跑整轮握手——这是日志里反复扫描、刷机卡顿的另一主因。
                if (foundAny) {
                    break;
                }
                continue;
            }
            foundAny = true;
            knownSectorSize = headerSectorSize;
            if (!loggedSector && headerSectorSize > 0 && headerSectorSize != sectorSize) {
                appendWorkLog(runDir, "GPT 扇区大小自动切换为 " + headerSectorSize + " 字节");
                loggedSector = true;
            }
            if (headerSectorSize > 0) {
                cacheBlockSize(headerSectorSize);
            }
            long bytes = header.entryCount * (long) header.entrySize;
            long sectors = (bytes + headerSectorSize - 1L) / headerSectorSize;
            if (sectors <= 0) {
                continue;
            }
            File entryFile = new File(runDir, "gpt_ent_lun" + lun + ".bin");
            boolean entryOk = runFhReadSectors(runDir, ctx, lun, header.entryLba,
                    sectors, headerSectorSize, entryFile, "PrimaryGPT");
            if (!entryOk) {
                continue;
            }
            List<GptEntry> parsed = parseGptEntryFile(entryFile, header, headerSectorSize, lun);
            for (GptEntry entry : parsed) {
                if (!isGptMetaEntry(entry.name)) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private List<GptEntry> readGptEntriesFromGptMainFiles(File runDir, FhContext ctx) {
        List<GptEntry> entries = new ArrayList<>();
        if (runDir == null || ctx == null) {
            return entries;
        }
        List<Integer> luns = resolveGptLuns();
        boolean foundAny = false;
        int knownSectorSize = 0;
        for (int lun : luns) {
            File gptFile = new File(runDir, "gpt_main" + lun + ".bin");
            boolean parsedOk = false;
            List<Integer> sectorCandidates = knownSectorSize > 0
                    ? Collections.singletonList(knownSectorSize)
                    : buildGptSectorCandidates(ctx.sectorSize);
            for (int candidate : sectorCandidates) {
                long gptSectors = estimateGptMainSectors(candidate);
                boolean ok = runFhReadProgramViaConvert(runDir, ctx, lun, 0, gptSectors,
                        candidate, gptFile, "PrimaryGPT");
                if (!ok) {
                    continue;
                }
                List<GptEntry> parsed = parseGptMainFile(gptFile, candidate, lun);
                if (parsed.isEmpty()) {
                    continue;
                }
                cacheBlockSize(candidate);
                for (GptEntry entry : parsed) {
                    if (!isGptMetaEntry(entry.name)) {
                        entries.add(entry);
                    }
                }
                parsedOk = true;
                knownSectorSize = candidate;
                break;
            }
            if (!parsedOk) {
                // 已读到分区后再遇无 GPT 的 LUN 即停止枚举（同 legacy 路径），不空跑高位 LUN
                if (foundAny) {
                    break;
                }
                continue;
            }
            foundAny = true;
        }
        return entries;
    }

    private boolean runFhReadPartition(File runDir, File loaderFile, String partName, String outputPath) {
        if (partName == null || partName.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        List<String> parts = splitCommaList(partName);
        List<String> outputs = splitCommaList(outputPath);
        if (parts.size() > 1 || outputs.size() > 1) {
            if (outputs.isEmpty()) {
                outputs = new ArrayList<>();
                for (String name : parts) {
                    outputs.add(buildDownloadImagePath(name));
                }
            }
            if (outputs.size() != parts.size()) {
                appendWorkLog(runDir, "分区数量与输出文件数量不一致");
                recordErrorReason("分区数量与输出文件数量不一致");
                appendStepResult("提取分区", false);
                return false;
            }
            FhContext ctx = buildFhContext(runDir, loaderFile, true);
            if (ctx == null) {
                appendStepResult("提取分区", false);
                return false;
            }
            if (gptEntries.isEmpty()) {
                runFhGptList(runDir, loaderFile);
            }
            for (int i = 0; i < parts.size(); i++) {
                String name = parts.get(i);
                String out = outputs.get(i);
                boolean ok = runFhReadPartitionSingle(runDir, loaderFile, ctx, name, out);
                if (!ok) {
                    return false;
                }
            }
            return true;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            appendStepResult("提取分区 " + partName, false);
            return false;
        }
        return runFhReadPartitionSingle(runDir, loaderFile, ctx, partName, outputPath);
    }

    private boolean runFhReadPartitionSingle(File runDir, File loaderFile, FhContext ctx,
                                             String partName, String outputPath) {
        if (outputPath == null || outputPath.trim().isEmpty()) {
            outputPath = buildDownloadImagePath(partName);
        }
        outputPath = ensureImgExtension(outputPath);
        outputPath = normalizeUserOutputPath(outputPath);
        String lunText = lunInput.getText().toString().trim();
        GptEntry entry = findGptEntry(partName, lunText);
        if (entry == null && gptEntries.isEmpty()) {
            runFhGptList(runDir, loaderFile);
            entry = findGptEntry(partName, lunText);
        }
        if (entry == null) {
            appendWorkLog(runDir, "未找到分区信息: '" + partName + "' (lun="
                    + (lunText.isEmpty() ? "未指定" : lunText) + ", 已读分区数=" + gptEntries.size()
                    + ")。该分区名不在设备 GPT 中——A/B 设备的引导分区只有 _a/_b 形态(如 boot_a)，"
                    + "请从分区列表点选具体分区，或先读取分区表");
            recordErrorReason("未找到分区 '" + partName + "'");
            return finishStep("提取分区 " + partName, false);
        }
        int lun = parseIntSafe(entry.partition, 0);
        long start = parseLongSafe(entry.startSector, -1L);
        long num = parseLongSafe(entry.numSectors, -1L);
        if (start < 0 || num <= 0) {
            appendWorkLog(runDir, "分区信息无效: " + partName);
            recordErrorReason("分区信息无效");
            return finishStep("提取分区 " + partName, false);
        }
        int sectorSize = parseIntSafe(entry.sectorSize, ctx.sectorSize);
        File outputFile = new File(outputPath);
        ensureDirExists(outputFile.getParent());
        setProgressTotalBytes(num * (long) sectorSize);
        startLogProgressMonitor(new File(runDir, "run.log"), "提取 " + partName);
        boolean ok = runFhReadProgram(runDir, ctx, lun, start, num, sectorSize, outputFile,
                entry.name);
        updateCachedStorageInfoFromLog(runDir);
        ok = finishStep("提取分区 " + partName, ok);
        if (ok) {
            showToast("提取完成，文件已保存到 " + outputFile.getAbsolutePath());
        }
        return ok;
    }

    private boolean runFhReadAll(File runDir, File loaderFile) {
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return finishStep("提取扇区", false);
        }
        if (gptEntries.isEmpty()) {
            if (!runFhGptList(runDir, loaderFile)) {
                return false;
            }
        }
        Set<String> skip = parseSkipPartitions();
        ensureDownloadDirExists();
        // 全量转储采用 best-effort：某分区读失败时记录并继续读其余，最后汇总成功/失败数，
        // 与 runFhReadSelected 及 edl 'rl' 语义一致(不因单分区失败放弃整机转储)。
        int total = 0;
        int success = 0;
        List<String> failed = new ArrayList<>();
        for (GptEntry entry : gptEntries) {
            if (entry == null || entry.name == null || entry.name.trim().isEmpty()) {
                continue;
            }
            String name = entry.name.trim();
            if (isGptMetaEntry(name)) {
                continue;
            }
            if (matchesSkipPartition(skip, name)) {
                continue;
            }
            int lun = parseIntSafe(entry.partition, 0);
            long start = parseLongSafe(entry.startSector, -1L);
            long num = parseLongSafe(entry.numSectors, -1L);
            if (start < 0 || num <= 0) {
                continue;
            }
            int sectorSize = parseIntSafe(entry.sectorSize, ctx.sectorSize);
            // 多 LUN 同名分区(如不同 LUN 各有 modem)不能落到同一文件互相覆盖：lun>0 加 lunN_ 前缀
            // (lun0 保持原名兼容旧行为)，与 multiRead/readSelected 命名策略一致。
            String outName = (lun == 0) ? name : ("lun" + lun + "_" + name);
            File outputFile = new File(buildDownloadImagePath(outName));
            setProgressTotalBytes(num * (long) sectorSize);
            startLogProgressMonitor(new File(runDir, "run.log"), "提取 " + name);
            total++;
            boolean ok = runFhReadProgram(runDir, ctx, lun, start, num, sectorSize, outputFile,
                    entry.name);
            updateCachedStorageInfoFromLog(runDir);
            if (!ok) {
                String reason = consumeErrorReason();
                if (reason == null || reason.trim().isEmpty()) {
                    reason = "提取分区失败";
                }
                appendWorkLog(runDir, "提取分区失败: " + name + "（" + reason + "）");
                failed.add(name);
                appendStepResult("提取分区 " + name, false);
                continue;
            }
            success++;
            appendStepResult("提取分区 " + name, true);
        }
        String summary = "全量提取完成：成功 " + success + "/" + total
                + (failed.isEmpty() ? "" : "，失败: " + TextUtils.join(",", failed));
        appendWorkLog(runDir, summary);
        showToast(summary + "，文件已保存到 " + DEFAULT_DOWNLOAD_DIR);
        return failed.isEmpty();
    }

    // Batch-read the user-selected partitions, each by its exact name+LUN so
    // same-named partitions on different LUNs are not confused. Reads run
    // sequentially into the download dir as <name>.img; a per-partition
    // success/failure summary is reported rather than aborting on the first.
    private boolean runFhReadSelected(File runDir, File loaderFile, List<PartitionOption> selection) {
        if (selection == null || selection.isEmpty()) {
            return finishStep("批量提取", false);
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return finishStep("批量提取", false);
        }
        if (gptEntries.isEmpty()) {
            runFhGptList(runDir, loaderFile);
        }
        ensureDownloadDirExists();
        int total = selection.size();
        int success = 0;
        List<String> failed = new ArrayList<>();
        for (PartitionOption option : selection) {
            GptEntry entry = findGptEntry(option.name, option.lun);
            if (entry == null) {
                appendWorkLog(runDir, "未找到分区: " + option.label);
                failed.add(option.name);
                continue;
            }
            int lun = parseIntSafe(entry.partition, 0);
            long start = parseLongSafe(entry.startSector, -1L);
            long num = parseLongSafe(entry.numSectors, -1L);
            if (start < 0 || num <= 0) {
                appendWorkLog(runDir, "分区信息无效: " + option.label);
                failed.add(option.name);
                continue;
            }
            int sectorSize = parseIntSafe(entry.sectorSize, ctx.sectorSize);
            // 多 LUN 同名分区用 lunN_ 前缀区分导出文件名，避免互相覆盖(lun0 保持原名)
            String outName = lun == 0 ? entry.name : ("lun" + lun + "_" + entry.name);
            File outputFile = new File(ensureImgExtension(buildDownloadImagePath(outName)));
            ensureDirExists(outputFile.getParent());
            setProgressTotalBytes(num * (long) sectorSize);
            startLogProgressMonitor(new File(runDir, "run.log"), "提取 " + entry.name);
            boolean ok = runFhReadProgram(runDir, ctx, lun, start, num, sectorSize, outputFile,
                    entry.name);
            updateCachedStorageInfoFromLog(runDir);
            if (ok) {
                success++;
                appendStepResult("提取分区 " + entry.name, true);
            } else {
                failed.add(entry.name);
                appendStepResult("提取分区 " + entry.name, false);
            }
        }
        String summary = "批量提取完成：成功 " + success + "/" + total
                + (failed.isEmpty() ? "" : "，失败: " + TextUtils.join(",", failed));
        appendWorkLog(runDir, summary);
        showToast(summary);
        return failed.isEmpty();
    }

    private boolean runFhReadSectorsCommand(File runDir, File loaderFile,
                                            String startText, String sectorsText, String outputPath) {
        long start = parseLongSafe(startText, -1L);
        long num = parseLongSafe(sectorsText, -1L);
        if (start < 0 || num <= 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            outputPath = buildReadOutputPath("rs", startText, sectorsText, "", "");
        }
        outputPath = ensureImgExtension(outputPath);
        outputPath = normalizeUserOutputPath(outputPath);
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return false;
        }
        int lun = parseIntSafe(lunInput.getText().toString().trim(), 0);
        int sectorSize = resolveFhSectorSize();
        File outputFile = new File(outputPath);
        ensureDirExists(outputFile.getParent());
        setProgressTotalBytes(num * (long) sectorSize);
        startLogProgressMonitor(new File(runDir, "run.log"), "提取 扇区");
        boolean ok = runFhReadProgram(runDir, ctx, lun, start, num, sectorSize, outputFile,
                "read");
        updateCachedStorageInfoFromLog(runDir);
        ok = finishStep("提取扇区", ok);
        if (ok) {
            showToast("提取完成，文件已保存到 " + outputFile.getAbsolutePath());
        }
        return ok;
    }

    private boolean runFhReadFull(File runDir, File loaderFile, String outputPath) {
        if (outputPath == null || outputPath.trim().isEmpty()) {
            outputPath = buildReadOutputPath("rf", "", "", "", "full_dump.img");
        }
        outputPath = ensureImgExtension(outputPath);
        outputPath = normalizeUserOutputPath(outputPath);
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return finishStep("提取全盘", false);
        }
        if (gptEntries.isEmpty()) {
            if (!runFhGptList(runDir, loaderFile)) {
                appendWorkLog(runDir, "未读取到分区表，无法提取全盘");
                recordErrorReason("未读取到分区表");
                return finishStep("提取全盘", false);
            }
        }
        ensureFhStorageInfo(runDir, ctx); // 尽力获取存储信息（每 LUN 容量仍以其 GPT 为准）
        List<Integer> luns = resolveGptLuns();
        if (luns.isEmpty()) {
            luns = Collections.singletonList(parseIntSafe(lunInput.getText().toString().trim(), 0));
        }
        int sectorSize = ctx.sectorSize > 0 ? ctx.sectorSize : resolveGptSectorSize();
        // 先探测各 LUN 真实容量，仅按"实际有数据的 LUN 数"决定是否加 _lunN 后缀：resolveGptLuns 在
        // 存储信息缺失时会枚举 0..7 候选，直接用 luns.size() 会给单 LUN 设备误加后缀(对齐 edl 用真实 LUN 数)。
        LinkedHashMap<Integer, Long> lunSectors = new LinkedHashMap<>();
        for (int lun : luns) {
            long total = resolveLunTotalSectors(runDir, ctx, lun, sectorSize);
            if (total > 0) {
                lunSectors.put(lun, total);
            } else {
                appendWorkLog(runDir, "LUN" + lun + " 容量未知，跳过");
            }
        }
        boolean multiLun = lunSectors.size() > 1;
        boolean anyDone = false;
        boolean allOk = true;
        for (Map.Entry<Integer, Long> lunEntry : lunSectors.entrySet()) {
            int lun = lunEntry.getKey();
            long totalSectors = lunEntry.getValue();
            File outputFile = new File(multiLun ? appendLunSuffix(outputPath, lun) : outputPath);
            ensureDirExists(outputFile.getParent());
            long totalBytes = totalSectors * (long) sectorSize;
            setProgressTotalBytes(totalBytes);
            startLogProgressMonitor(new File(runDir, "run.log"), "提取 LUN" + lun);
            // 单次连续读 [0,totalSectors) 完整覆盖 MBR/主 GPT/全部分区/分区间隙/备份 GPT（对齐 edl 'rf'）
            boolean ok = runFhReadProgram(runDir, ctx, lun, 0, totalSectors, sectorSize,
                    outputFile, "FullLUN" + lun);
            if (ok && outputFile.exists()) {
                anyDone = true;
                appendWorkLog(runDir, "LUN" + lun + " 已提取: " + outputFile.getAbsolutePath());
            } else {
                allOk = false;
                String reason = consumeErrorReason();
                recordErrorReason(reason == null || reason.trim().isEmpty() ? "提取失败" : reason);
                appendWorkLog(runDir, "LUN" + lun + " 提取失败");
                break;
            }
        }
        updateCachedStorageInfoFromLog(runDir);
        boolean done = anyDone && allOk;
        if (done) {
            showToast("提取完成，文件已保存");
        }
        return finishStep("提取全盘", done);
    }

    // 该 LUN 总扇区数：优先取其主 GPT 头 backupLba+1，回退 storageinfo 的 total_blocks
    private long resolveLunTotalSectors(File runDir, FhContext ctx, int lun, int sectorSize) {
        long probe = estimateGptMainProbeSectors(sectorSize);
        byte[] region = readGptRegionBytes(runDir, ctx, lun, 0, probe, sectorSize, "PrimaryGPT");
        if (region != null) {
            GptLayout g = parseGptLayout(region, sectorSize, region.length);
            if (g != null && g.backupLba > 0) {
                return g.backupLba + 1;
            }
        }
        // cachedTotalBlocks 仅反映 LUN0 容量；高位 LUN 读不到自身 GPT 时不能套用 LUN0 大小(会读错容量)，
        // 返回 -1 让上层跳过该 LUN（对齐 edl rf：各 LUN 按自身 GPT 读取）。
        return lun == 0 && cachedTotalBlocks > 0 ? cachedTotalBlocks : -1L;
    }

    private String appendLunSuffix(String path, int lun) {
        if (path == null) {
            return "full_lun" + lun + ".img";
        }
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(File.separatorChar));
        if (dot > slash) {
            return path.substring(0, dot) + "_lun" + lun + path.substring(dot);
        }
        return path + "_lun" + lun;
    }

    private boolean runFhWritePartition(File runDir, File loaderFile, String partName, String imagePath) {
        if (partName == null || partName.trim().isEmpty() || imagePath == null || imagePath.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        List<String> parts = splitCommaList(partName);
        List<String> images = splitCommaList(imagePath);
        if (parts.size() > 1 || images.size() > 1) {
            if (parts.size() != images.size()) {
                appendWorkLog(runDir, "分区数量与镜像数量不一致");
                recordErrorReason("分区数量与镜像数量不一致");
                appendStepResult("刷写分区", false);
                return false;
            }
            FhContext ctx = buildFhContext(runDir, loaderFile, true);
            if (ctx == null) {
                appendStepResult("刷写分区", false);
                return false;
            }
            if (gptEntries.isEmpty()) {
                runFhGptList(runDir, loaderFile);
            }
            for (int i = 0; i < parts.size(); i++) {
                boolean ok = runFhWritePartitionSingle(runDir, loaderFile, ctx, parts.get(i), images.get(i));
                if (!ok) {
                    return false;
                }
            }
            return true;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            appendStepResult("刷写分区 " + partName, false);
            return false;
        }
        return runFhWritePartitionSingle(runDir, loaderFile, ctx, partName, imagePath);
    }

    private boolean runFhWritePartitionSingle(File runDir, File loaderFile, FhContext ctx,
                                              String partName, String imagePath) {
        File imageFile = new File(imagePath);
        if (!rootExists(imagePath, false)) {
            appendWorkLog(runDir, "镜像文件不存在: " + imagePath);
            recordErrorReason("镜像文件不存在");
            return finishStep("刷写分区 " + partName, false);
        }
        String lunText = lunInput.getText().toString().trim();
        GptEntry entry = findGptEntry(partName, lunText);
        if (entry == null && gptEntries.isEmpty()) {
            runFhGptList(runDir, loaderFile);
            entry = findGptEntry(partName, lunText);
        }
        if (entry == null) {
            appendWorkLog(runDir, "未找到分区信息: '" + partName + "' (lun="
                    + (lunText.isEmpty() ? "未指定" : lunText) + ", 已读分区数=" + gptEntries.size()
                    + ")。该分区名不在设备 GPT 中——A/B 设备的引导分区只有 _a/_b 形态(如 boot_a)，"
                    + "请从分区列表点选具体分区，或先读取分区表");
            recordErrorReason("未找到分区 '" + partName + "'");
            return finishStep("刷写分区 " + partName, false);
        }
        int lun = parseIntSafe(entry.partition, 0);
        long start = parseLongSafe(entry.startSector, -1L);
        int sectorSize = parseIntSafe(entry.sectorSize, ctx.sectorSize);
        imageFile = ensureRawImageForFlash(runDir, imageFile);
        if (imageFile == null) {
            recordErrorReason("sparse 转换失败");
            return finishStep("刷写分区 " + partName, false);
        }
        long fileSize = rootFileSize(imageFile.getAbsolutePath());
        long numSectors = fileSize <= 0 ? 0 : (fileSize + sectorSize - 1L) / sectorSize;
        if (start < 0 || numSectors <= 0) {
            appendWorkLog(runDir, "分区信息无效: " + partName);
            recordErrorReason("分区信息无效");
            return finishStep("刷写分区 " + partName, false);
        }
        // 镜像不得超过 GPT 分区容量，否则会越界写入相邻分区导致损坏（对齐 qdl firehose_program 守卫）
        long slotSectors = parseLongSafe(entry.numSectors, -1L);
        if (slotSectors > 0 && numSectors > slotSectors) {
            appendWorkLog(runDir, "镜像超出分区容量: " + partName + " 需 " + numSectors
                    + " 扇区，分区仅 " + slotSectors + " 扇区");
            recordErrorReason("镜像超出分区容量");
            return finishStep("刷写分区 " + partName, false);
        }
        setProgressTotalBytes(fileSize);
        startLogProgressMonitor(new File(runDir, "run.log"), "刷写 " + partName);
        boolean ok = runFhWriteProgram(runDir, ctx, lun, start, numSectors, sectorSize,
                imageFile, partName);
        updateCachedStorageInfoFromLog(runDir);
        return finishStep("刷写分区 " + partName, ok);
    }

    private boolean runFhWriteSectors(File runDir, File loaderFile, String startText, String imagePath) {
        long start = parseLongSafe(startText, -1L);
        if (start < 0 || imagePath == null || imagePath.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        File imageFile = new File(imagePath);
        if (!rootExists(imagePath, false)) {
            appendWorkLog(runDir, "镜像文件不存在: " + imagePath);
            recordErrorReason("镜像文件不存在");
            return finishStep("刷写扇区", false);
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return finishStep("刷写扇区", false);
        }
        int lun = parseIntSafe(lunInput.getText().toString().trim(), 0);
        int sectorSize = resolveFhSectorSize();
        imageFile = ensureRawImageForFlash(runDir, imageFile);
        if (imageFile == null) {
            recordErrorReason("sparse 转换失败");
            return finishStep("刷写扇区", false);
        }
        long fileSize = rootFileSize(imageFile.getAbsolutePath());
        long numSectors = fileSize <= 0 ? 0 : (fileSize + sectorSize - 1L) / sectorSize;
        if (numSectors <= 0) {
            appendWorkLog(runDir, "镜像无效: " + imagePath);
            recordErrorReason("镜像无效");
            return finishStep("刷写扇区", false);
        }
        setProgressTotalBytes(fileSize);
        startLogProgressMonitor(new File(runDir, "run.log"), "刷写 扇区");
        boolean ok = runFhWriteProgram(runDir, ctx, lun, start, numSectors, sectorSize,
                imageFile, "write");
        updateCachedStorageInfoFromLog(runDir);
        return finishStep("刷写扇区", ok);
    }

    private boolean runFhWriteFull(File runDir, File loaderFile, String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            recordErrorReason("缺少镜像文件");
            return finishStep("刷写全盘", false);
        }
        File imageFile = new File(imagePath);
        if (!rootExists(imagePath, false)) {
            appendWorkLog(runDir, "镜像文件不存在: " + imagePath);
            recordErrorReason("镜像文件不存在");
            return finishStep("刷写全盘", false);
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return finishStep("刷写全盘", false);
        }
        if (!ensureFhStorageInfo(runDir, ctx)) {
            appendWorkLog(runDir, "未获取存储信息，无法刷写全盘");
            recordErrorReason("未获取存储信息");
            return finishStep("刷写全盘", false);
        }
        long totalBlocks = cachedTotalBlocks;
        if (totalBlocks <= 0) {
            appendWorkLog(runDir, "存储容量未知，无法刷写全盘");
            recordErrorReason("存储容量未知");
            return finishStep("刷写全盘", false);
        }
        int lun = parseIntSafe(lunInput.getText().toString().trim(), 0);
        imageFile = ensureRawImageForFlash(runDir, imageFile);
        if (imageFile == null) {
            recordErrorReason("sparse 转换失败");
            return finishStep("刷写全盘", false);
        }
        long fileSize = rootFileSize(imageFile.getAbsolutePath());
        long expectedBytes = totalBlocks * (long) ctx.sectorSize;
        if (expectedBytes != fileSize) {
            appendWorkLog(runDir, "全盘镜像大小不匹配: 期望 " + expectedBytes + " 字节，实际 " + fileSize + " 字节");
            recordErrorReason("全盘镜像大小不匹配");
            return finishStep("刷写全盘", false);
        }
        setProgressTotalBytes(fileSize);
        startLogProgressMonitor(new File(runDir, "run.log"), "刷写全盘");
        boolean ok = runFhWriteProgram(runDir, ctx, lun, 0, totalBlocks, ctx.sectorSize,
                imageFile, "write");
        updateCachedStorageInfoFromLog(runDir);
        return finishStep("刷写全盘", ok);
    }

    private boolean runFhWriteAll(File runDir, File loaderFile, String dirPath) {
        if (dirPath == null || dirPath.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        File dir = new File(dirPath);
        if (!rootExists(dirPath, true)) {
            appendWorkLog(runDir, "分区目录不存在: " + dirPath);
            recordErrorReason("分区目录不存在");
            appendStepResult("刷写分区", false);
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            appendStepResult("刷写分区", false);
            return false;
        }
        if (gptEntries.isEmpty()) {
            if (!runFhGptList(runDir, loaderFile)) {
                return false;
            }
        }
        if (gptEntries.isEmpty()) {
            appendWorkLog(runDir, "未读取到分区表");
            recordErrorReason("未读取到分区表");
            appendStepResult("刷写分区", false);
            return false;
        }
        Map<String, GptEntry> entryMap = new HashMap<>();
        for (GptEntry entry : gptEntries) {
            if (entry != null && entry.name != null) {
                entryMap.put(entry.name.toLowerCase(Locale.US), entry);
            }
        }
        List<String> names = rootListNames(dirPath);
        if (names.isEmpty()) {
            appendWorkLog(runDir, "分区目录为空");
            recordErrorReason("分区目录为空");
            appendStepResult("刷写分区", false);
            return false;
        }
        boolean any = false;
        for (String fileName : names) {
            File file = new File(dir, fileName);
            if (!rootExists(file.getAbsolutePath(), false)) {
                continue;
            }
            String baseName = stripExtension(fileName).toLowerCase(Locale.US);
            GptEntry entry = entryMap.get(baseName);
            if (entry == null) {
                continue;
            }
            any = true;
            int lun = parseIntSafe(entry.partition, 0);
            long start = parseLongSafe(entry.startSector, -1L);
            int sectorSize = parseIntSafe(entry.sectorSize, ctx.sectorSize);
            file = ensureRawImageForFlash(runDir, file);
            if (file == null) {
                recordErrorReason("sparse 转换失败: " + entry.name);
                return finishStep("刷写分区 " + entry.name, false);
            }
            long fileSize = rootFileSize(file.getAbsolutePath());
            long numSectors = fileSize <= 0 ? 0 : (fileSize + sectorSize - 1L) / sectorSize;
            if (start < 0 || numSectors <= 0) {
                appendWorkLog(runDir, "分区信息无效: " + entry.name);
                recordErrorReason("分区信息无效");
                return finishStep("刷写分区 " + entry.name, false);
            }
            long slotSectors = parseLongSafe(entry.numSectors, -1L);
            if (slotSectors > 0 && numSectors > slotSectors) {
                appendWorkLog(runDir, "镜像超出分区容量: " + entry.name + " 需 " + numSectors
                        + " 扇区，分区仅 " + slotSectors + " 扇区");
                recordErrorReason("镜像超出分区容量");
                return finishStep("刷写分区 " + entry.name, false);
            }
            setProgressTotalBytes(fileSize);
            startLogProgressMonitor(new File(runDir, "run.log"), "刷写 " + entry.name);
            boolean ok = runFhWriteProgram(runDir, ctx, lun, start, numSectors, sectorSize, file, entry.name);
            updateCachedStorageInfoFromLog(runDir);
            if (!ok) {
                return finishStep("刷写分区 " + entry.name, false);
            }
            appendStepResult("刷写分区 " + entry.name, true);
        }
        if (!any) {
            appendWorkLog(runDir, "未找到可刷写的镜像文件");
            recordErrorReason("未找到可刷写的镜像文件");
            appendStepResult("刷写分区", false);
            return false;
        }
        return true;
    }

    private boolean runFhQfilSplit(File runDir, File loaderFile, QfilInputs inputs) {
        // 整轮拆分刷写期间禁止内部 qdl 调用自动复位，末尾由 qfil 流程统一发一次 reset
        suppressAutoReset = true;
        try {
            return runFhQfilSplitInternal(runDir, loaderFile, inputs);
        } finally {
            suppressAutoReset = false;
        }
    }

    private boolean runFhQfilSplitInternal(File runDir, File loaderFile, QfilInputs inputs) {
        if (runDir == null || loaderFile == null) {
            return false;
        }
        if (inputs == null || inputs.rawprogramFiles.isEmpty()) {
            appendWorkLog(runDir, "未找到 rawprogram.xml");
            appendSummaryLog("QFIL 失败: 未找到 rawprogram.xml");
            return false;
        }
        File imageDir = inputs.imageDir;
        if (imageDir == null || !rootExists(imageDir.getAbsolutePath(), true)) {
            appendWorkLog(runDir, "镜像目录不存在");
            appendSummaryLog("QFIL 失败: 镜像目录不存在");
            return false;
        }
        // OPlus VIP loader 必须逐分区下发(每分区 label/filename 伪装 + 真实地址 + 6/34 边界切片，
        // 由 runFhWriteProgram→buildVipSpoofProfiles 内部完成)。整 XML 一次性 relabel 成 BackupGPT 会被
        // app 自身的 isFlashableProgramEntry(把 BackupGPT 当 GPT 元数据)反过滤致全部跳过，故 VIP 强制逐分区。
        boolean splitMode = isQfilSplitEnabled() || isOplusVipPath(loaderFile);
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return false;
        }
        // 绝不以设备【当前】GPT 当分区布局权威——这是旧版自创的错误(致联想等标准全量包不开机)。所有参考
        // 刷写器(bkerler/edl qfil、qdl/qdlrs/fh_loader、OplusEdlTool、oplus12r)刷 rawprogram 一律【原样信任
        // 包内 start_sector + 照刷包内 PrimaryGPT/BackupGPT + 应用包内 patch】,绝不把分区重映射到设备当前
        // GPT、绝不保留设备旧 GPT、绝不跳过 patch。设备当前 GPT 是"刷之前盘上的旧布局",而全量固件包的意图
        // 正是用包内新 GPT 重排全盘——拿旧地图定位新内容必然错位致不可引导。(OplusEdlTool 的"重映射"目标是
        // 【包内 gpt_main.bin 离线文件】,本质仍是包为权威。) 故 deviceGpt 恒空、全程 verbatim;设备 GPT 读取
        // 仅保留给分区表预览。
        Map<String, GptEntry> deviceGpt = new HashMap<>();
        // patch 严格按 index/LUN 匹配 rawprogram（patch0→rawprogram0、patch5→rawprogram5）。
        // 不再对单 patch 做跨 LUN 回退——那会把 LUN0 的 patch0 误应用到 LUN5 并掩盖 patch5 缺失。
        Map<Integer, File> patchMap = buildPatchIndexMap(inputs.patchFiles);
        List<File> rawprograms = new ArrayList<>(inputs.rawprogramFiles);
        Collections.sort(rawprograms, (a, b) -> {
            int idxA = parseXmlIndex(a.getName(), RAWPROGRAM_FILE_PATTERN);
            int idxB = parseXmlIndex(b.getName(), RAWPROGRAM_FILE_PATTERN);
            if (idxA != idxB) {
                return Integer.compare(idxA, idxB);
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        // OPlus/OPPO MsmDownloadTool 的工程配置(根<Setting>、program 分组在<ProgramN>下、filename
        // 在嵌套<Image>里)不是标准高通 rawprogram；检测到则先转成标准 <data><program filename=.../>，
        // 否则下游按空 filename 跳过所有分区、且 program 非<data>直接子节点会触发 removeChild 崩溃。
        // 同时把 <Setting> 内联的 <patch> 提取成标准 patch 接入 patchMap——它们修正主/备份 GPT 头的
        // LastUsableLBA、CRC、末分区 userdata 实际大小(GrowLastPartToFillDisk)。不应用→GPT 无效→不开机。
        for (int i = 0; i < rawprograms.size(); i++) {
            File original = rawprograms.get(i);
            File settingPatch = convertOplusSettingPatch(original, runDir);
            if (settingPatch != null) {
                int idx = parseXmlIndex(original.getName(), RAWPROGRAM_FILE_PATTERN);
                if (!patchMap.containsKey(idx)) {
                    patchMap.put(idx, settingPatch);
                }
            }
            rawprograms.set(i, convertOplusSettingXml(original, runDir));
        }
        boolean needsPersistWarning = false;
        boolean needsSuperImage = false;
        for (File rawprogram : rawprograms) {
            if (rawprogram == null || !rootExists(rawprogram.getAbsolutePath(), false)) {
                continue;
            }
            List<ProgramEntry> programs = parseRawprogramPrograms(rawprogram);
            for (ProgramEntry entry : programs) {
                if (entry == null) {
                    continue;
                }
                String label = resolveProgramLabel(entry);
                if (isPersistProgram(label, entry.filename)) {
                    needsPersistWarning = true;
                }
                if ("super".equalsIgnoreCase(label)) {
                    needsSuperImage = true;
                }
                if (needsPersistWarning && needsSuperImage) {
                    break;
                }
            }
            if (needsPersistWarning && needsSuperImage) {
                break;
            }
        }
        boolean skipPersist = false;
        if (needsPersistWarning) {
            int decision = promptPersistDecision();
            if (decision == PERSIST_DECISION_CANCEL) {
                appendWorkLog(runDir, "已取消刷写");
                return false;
            }
            if (decision == PERSIST_DECISION_SKIP) {
                skipPersist = true;
                appendWorkLog(runDir, "已跳过 persist 分区");
            }
        }
        if (needsSuperImage) {
            if (!ensureSuperImage(runDir, inputs.baseDir, imageDir)) {
                appendWorkLog(runDir, "未生成 super.img，将尝试继续刷写");
            }
        }
        if (!splitMode) {
            List<File> xmls = new ArrayList<>();
            Set<Integer> flashedIndexes = new LinkedHashSet<>();
            for (File rawprogram : rawprograms) {
                if (rawprogram == null || !rootExists(rawprogram.getAbsolutePath(), false)) {
                    appendWorkLog(runDir, "rawprogram 不存在: " + (rawprogram == null ? "null" : rawprogram.getAbsolutePath()));
                    return false;
                }
                File effectiveRawprogram = prepareRawprogramXml(rawprogram, imageDir, runDir, skipPersist, deviceGpt);
                if (!rawprogramHasFlashable(effectiveRawprogram)) {
                    appendWorkLog(runDir, "rawprogram 已全部跳过: " + rawprogram.getName());
                    continue;
                }
                xmls.add(effectiveRawprogram);
                flashedIndexes.add(parseXmlIndex(rawprogram.getName(), RAWPROGRAM_FILE_PATTERN));
            }
            for (File rawprogram : rawprograms) {
                int index = parseXmlIndex(rawprogram.getName(), RAWPROGRAM_FILE_PATTERN);
                // 被全跳过的 rawprogram 不应用其 patch（要修补的 GPT 已不刷）
                if (!flashedIndexes.contains(index)) {
                    continue;
                }
                File patch = patchMap.get(index);
                // 照应用包内 patch(修正主/备份 GPT 头/CRC/末分区扩容)——与 qdl/fh_loader/bkerler 标准一致。
                if (patch != null && rootExists(patch.getAbsolutePath(), false)) {
                    appendWorkLog(runDir, "应用 patch: " + patch.getName());
                    xmls.add(patch);
                }
            }
            if (xmls.isEmpty()) {
                appendWorkLog(runDir, "未找到可执行的 rawprogram/patch");
                return false;
            }
            File includeDir = imageDir;
            if (rawprogramUsesSubdir(rawprograms)) {
                includeDir = rawprograms.get(0).getParentFile();
            }
            if (includeDir == null || !rootExists(includeDir.getAbsolutePath(), true)) {
                includeDir = imageDir;
            }
            try {
                startLogProgressMonitor(new File(runDir, "run.log"), "刷写中");
                CommandResult result = runQdlXmlCommand(runDir, loaderFile, xmls, includeDir, true);
                return isCommandSuccess(result);
            } catch (IOException | InterruptedException e) {
                appendWorkLog(runDir, "QDL 执行失败: " + e.getMessage());
                return false;
            }
        }
        boolean anyFlashed = false;
        for (File rawprogram : rawprograms) {
            if (rawprogram == null || !rootExists(rawprogram.getAbsolutePath(), false)) {
                appendWorkLog(runDir, "rawprogram 不存在: " + (rawprogram == null ? "null" : rawprogram.getAbsolutePath()));
                return false;
            }
            File effectiveRawprogram = prepareRawprogramXml(rawprogram, imageDir, runDir, skipPersist, deviceGpt);
            int index = parseXmlIndex(rawprogram.getName(), RAWPROGRAM_FILE_PATTERN);
            File patch = patchMap.get(index);
            List<ProgramEntry> programs = parseRawprogramPrograms(effectiveRawprogram);
            boolean hasFlashable = false;
            for (ProgramEntry e : programs) {
                if (isFlashableProgramEntry(e)) {
                    hasFlashable = true;
                    break;
                }
            }
            // 与非拆分路径/预览一致：仅剩 GPT 项或全被跳过时不刷该 XML（GPT 不单独成刷写理由）
            if (!hasFlashable) {
                appendWorkLog(runDir, "rawprogram 已全部跳过或无可刷分区: " + rawprogram.getName());
                continue;
            }
            anyFlashed = true;
            // OplusEdlTool Step1：先照刷包内 PrimaryGPT/BackupGPT(真实 label，设备分区表更新成包内新布局)，
            // 再刷普通分区(下方循环)。VIP split 旧版漏刷包内 GPT 致 .ops 不开机。
            flashPackageGptEntries(runDir, ctx, programs, imageDir, effectiveRawprogram);
            for (ProgramEntry entry : programs) {
                if (entry == null) {
                    continue;
                }
                // 保留设备出厂 GPT：逐分区路径绝不单独刷包内 PrimaryGPT/BackupGPT(VIP 的 GPT 伪装由
                // buildVipSpoofProfiles 在写真实分区时内部完成，独立 GPT 项会覆盖设备真实分区表致不可引导)。
                if (!isFlashableProgramEntry(entry)) {
                    continue;
                }
                String label = resolveProgramLabel(entry);
                String filename = entry.filename == null ? "" : entry.filename.trim();
                if (skipPersist && isPersistProgram(label, filename)) {
                    continue;
                }
                if (filename.isEmpty()) {
                    // 无镜像的 GPT 布局项，跳过不刷（与 qdl/edl 一致）
                    continue;
                }
                if ("disk".equalsIgnoreCase(filename)) {
                    continue;
                }
                File imageFile = resolveProgramImageFile(filename, imageDir, effectiveRawprogram.getParentFile());
                if (imageFile == null || !rootExists(imageFile.getAbsolutePath(), false)) {
                    if ("super".equalsIgnoreCase(label) && ensureSuperImage(runDir, inputs.baseDir, imageDir)) {
                        imageFile = resolveProgramImageFile(filename, imageDir, effectiveRawprogram.getParentFile());
                    }
                }
                if (imageFile == null || !rootExists(imageFile.getAbsolutePath(), false)) {
                    if ("super".equalsIgnoreCase(label)) {
                        appendWorkLog(runDir, "super 镜像缺失，已跳过");
                        continue;
                    }
                    // 白名单分区（persist/ocdt 等）镜像缺失时跳过、不中止，与校验逻辑一致
                    if (QFIL_SKIP_PARTITIONS.contains(safeLower(label))) {
                        appendWorkLog(runDir, "镜像缺失已跳过: " + label);
                        continue;
                    }
                    appendWorkLog(runDir, "镜像文件缺失: " + filename);
                    return false;
                }
                // VIP 逐分区路径按字节偏移切片写入(slice 内 sparse 恒为 false)，绕过了 qdl 对 sparse
                // 镜像的原生解包；.ops 的 super/userdata 等在 settings.xml 标 sparse="true"，若不先转 raw
                // 就切片，写入的是稀疏头+chunk 元数据而非展开数据→分区损坏不开机。单分区/非拆分路径已在
                // 3448/3494/3542/3618 转 raw，此处补齐(非 sparse 镜像原样返回，零影响)。
                imageFile = ensureRawImageForFlash(runDir, imageFile);
                if (imageFile == null) {
                    appendWorkLog(runDir, "sparse 转 raw 失败: " + label);
                    recordErrorReason("sparse 转 raw 失败: " + label);
                    return false;
                }
                // 走与"单分区刷写"一致的 runFhWriteProgram：对 OPlus loader 自动套用
                // PrimaryGPT/BackupGPT 伪装回退(buildVipSpoofProfiles),绕过 loader 对 ocdt 等
                // 非 GPT 分区的写入授权限制(摘要表)。分区地址用设备 GPT 重映射后的 entry 值,
                // 写入扇区数按镜像实际大小算(与单分区路径 runFhWritePartitionSingle 一致)。
                int lun = parseIntSafe(getEntryAttr(entry, "physical_partition_number"), 0);
                long start = parseLongSafe(getEntryAttr(entry, "start_sector"), -1L);
                // start_sector 原样取包内 XML 值(verbatim)——不按设备当前 GPT 重映射,与所有参考刷写器一致。
                int sectorSize = parseIntSafe(getEntryAttr(entry, "SECTOR_SIZE_IN_BYTES"), ctx.sectorSize);
                long fileSize = rootFileSize(imageFile.getAbsolutePath());
                long numSectors = fileSize <= 0 ? 0 : (fileSize + sectorSize - 1L) / sectorSize;
                if (start < 0 || numSectors <= 0) {
                    appendWorkLog(runDir, "分区信息无效: " + label);
                    recordErrorReason("分区信息无效: " + label);
                    return false;
                }
                appendWorkLog(runDir, "刷写分区: " + label);
                setProgressTotalBytes(fileSize);
                startLogProgressMonitor(new File(runDir, "run.log"), "刷写 " + label);
                boolean ok = runFhWriteProgram(runDir, ctx, lun, start, numSectors, sectorSize,
                        imageFile, label);
                if (!ok) {
                    appendWorkLog(runDir, "刷写失败: " + label);
                    return false;
                }
            }
            // 先刷完本 rawprogram 的全部 program，再应用 patch（修正主/备份 GPT 头/CRC/末分区扩容），
            // 与 qdl 的 program→patch 顺序一致；照应用，与标准刷写器一致。
            if (patch != null && rootExists(patch.getAbsolutePath(), false)) {
                appendWorkLog(runDir, "应用 patch: " + patch.getName());
                if (!runFhXmlCommand(runDir, ctx, patch, null, "patch")) {
                    appendWorkLog(runDir, "patch 失败: " + patch.getName());
                    return false;
                }
            }
        }
        if (!anyFlashed) {
            appendWorkLog(runDir, "所有分区均被取消，无可刷写内容");
            appendSummaryLog("QFIL: 所有分区均被取消");
            return false;
        }
        // OplusEdlTool Step4：VIP split 路径逐分区下发不经 qdl_determine_bootable(!splitMode 靠
        // runQdlXmlCommand(...,true) 自动设可启动盘)，须显式设可启动存储驱动(OnePlus UFS value=1)，
        // 否则可启动 LUN 未选→不开机。best-effort，失败不影响已刷数据。
        if (isOplusVipPath(loaderFile)) {
            appendWorkLog(runDir, "设置可启动存储驱动 (UFS LUN1)");
            runFhSetBootableStorageDrive(runDir, loaderFile, "1");
        }
        return true;
    }

    private Set<String> parseSkipPartitions() {
        // 逗号+空白分隔(对齐 edl --skip 的逗号分隔，并兼容空白)，小写存储；支持 * ? glob 通配。
        Set<String> skip = new LinkedHashSet<>();
        String text = skipPartitionsInput.getText().toString().trim();
        if (text.isEmpty()) {
            return skip;
        }
        for (String part : splitCommaList(text)) {
            skip.add(part.toLowerCase(Locale.US));
        }
        return skip;
    }

    // 分区名是否命中跳过集：token 含 * 或 ? 时按 glob 整串匹配(对齐 edl fnmatch)，否则精确相等。
    // 大小写不敏感(token 已小写，name 在此小写)。
    private boolean matchesSkipPartition(Set<String> skip, String name) {
        if (skip.isEmpty() || name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.US);
        for (String token : skip) {
            if (token.indexOf('*') < 0 && token.indexOf('?') < 0) {
                if (token.equals(lower)) {
                    return true;
                }
                continue;
            }
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < token.length(); i++) {
                char ch = token.charAt(i);
                if (ch == '*') {
                    regex.append(".*");
                } else if (ch == '?') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(String.valueOf(ch)));
                }
            }
            if (lower.matches(regex.toString())) {
                return true;
            }
        }
        return false;
    }

    private int promptPersistDecision() {
        // 破坏性持久化分区前的三选一确认：阻塞当前(后台)线程交由外层回调同步返回，
        // 阻塞模型从引擎内迁到回调实现内，引擎侧调用点保持同步语义不变。
        int decision = cb.onConfirmPersist(getString(R.string.persist_warning_message));
        if (decision == EdlCallback.CONFIRM_CONTINUE) {
            return PERSIST_DECISION_CONTINUE;
        }
        if (decision == EdlCallback.CONFIRM_SKIP) {
            return PERSIST_DECISION_SKIP;
        }
        return PERSIST_DECISION_CANCEL;
    }

    private List<String> splitCommaList(String text) {
        List<String> items = new ArrayList<>();
        if (text == null) {
            return items;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return items;
        }
        String[] parts = trimmed.split("[,\\s]+");
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String value = part.trim();
            if (!value.isEmpty()) {
                items.add(value);
            }
        }
        return items;
    }

    private boolean ensureFhStorageInfo(File runDir, FhContext ctx) {
        if (cachedTotalBlocks > 0) {
            return true;
        }
        if (runDir == null || ctx == null) {
            return false;
        }
        refreshCachedStorageInfo(runDir, ctx);
        if (cachedTotalBlocks > 0) {
            return true;
        }
        File headerFile = new File(runDir, "gpt_hdr_lun0.bin");
        int preferred = ctx.sectorSize > 0 ? ctx.sectorSize : resolveFhSectorSize();
        for (int candidate : buildGptSectorCandidates(preferred)) {
            boolean headerOk = runFhReadSectors(runDir, ctx, 0, 1, 1, candidate, headerFile,
                    "PrimaryGPT");
            if (!headerOk) {
                continue;
            }
            GptHeader header = parseGptHeaderFile(headerFile);
            if (header == null || header.backupLba <= 0) {
                continue;
            }
            cachedTotalBlocks = header.backupLba + 1;
            cachedBlockSize = candidate;
            SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            editor.putLong(PREF_TOTAL_BLOCKS, cachedTotalBlocks);
            editor.putInt(PREF_BLOCK_SIZE, cachedBlockSize);
            editor.apply();
            return true;
        }
        return false;
    }

    private boolean runFhReadSectors(File runDir, FhContext ctx, int lun, long startSector,
                                     long numSectors, int sectorSize, File outputFile,
                                     String label) {
        if (shouldUseVipSliceRouting(ctx, sectorSize)) {
            return runVipReadSlices(runDir, ctx, lun, startSector, numSectors, sectorSize,
                    outputFile, label);
        }
        return runFhReadSectorsSingle(runDir, ctx, lun, startSector, numSectors, sectorSize,
                outputFile, label, null);
    }

    private boolean runFhReadSectorsSingle(File runDir, FhContext ctx, int lun, long startSector,
                                           long numSectors, int sectorSize, File outputFile,
                                           String label, String defaultFileName) {
        if (runDir == null || ctx == null || outputFile == null) {
            return false;
        }
        if (outputFile.exists()) {
            outputFile.delete();
        }
        List<VipSpoofProfile> profiles = buildVipSpoofProfiles(
                ctx, lun, startSector, numSectors, sectorSize, label,
                defaultFileName == null || defaultFileName.trim().isEmpty()
                        ? outputFile.getName() : defaultFileName);
        for (int i = 0; i < profiles.size(); i++) {
            VipSpoofProfile profile = profiles.get(i);
            String stagedName = profile.deviceFilename;
            if (stagedName == null || stagedName.trim().isEmpty()) {
                stagedName = "read_"
                        + sanitizeFileName(outputFile.getName())
                        + "_" + lun + "_" + startSector + "_" + numSectors + ".img";
            }
            File stagedOutput = new File(runDir, sanitizeFileName(stagedName));
            File xmlFile = writeQdlReadXml(runDir, stagedOutput, lun, startSector, numSectors,
                    sectorSize, profile.deviceLabel, shouldUseVipSpoof(ctx));
            if (xmlFile == null) {
                continue;
            }
            if (stagedOutput.exists()) {
                stagedOutput.delete();
            }
            if (profiles.size() > 1) {
                appendWorkLog(runDir, "读取策略 " + (i + 1) + "/" + profiles.size()
                        + ": " + profile.deviceLabel + " / " + stagedOutput.getName());
            }
            try {
                List<File> xmls = Collections.singletonList(xmlFile);
                CommandResult result = runQdlXmlCommand(runDir, ctx.loaderFile, xmls, null, false);
                boolean stagedOk = stagedOutput.exists() && stagedOutput.length() > 0;
                boolean fileOk = false;
                if (stagedOk) {
                    ensureDir(outputFile.getParentFile());
                    copyFileTo(stagedOutput, outputFile);
                    fileOk = outputFile.exists() && outputFile.length() > 0;
                }
                boolean ok = result != null && result.exitCode == 0
                        && !outputHasFailureForRead(result.output);
                if (ok && fileOk) {
                    lastErrorReason = null;
                    return true;
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                if (stagedOutput.exists()) {
                    stagedOutput.delete();
                }
            }
            if (outputFile.exists()) {
                outputFile.delete();
            }
        }
        return false;
    }

    private CommandResult runQdlXmlCommand(File runDir, File loaderFile, List<File> xmlFiles,
                                          File includeDir, boolean allowMissing)
            throws IOException, InterruptedException {
        return runQdlXmlCommand(runDir, loaderFile, xmlFiles, includeDir, allowMissing, false);
    }

    private CommandResult runQdlXmlCommand(File runDir, File loaderFile, List<File> xmlFiles,
                                          File includeDir, boolean allowMissing,
                                          boolean forceFreshSession)
            throws IOException, InterruptedException {
        throwIfCommandCanceled();
        if (loaderFile == null || xmlFiles == null || xmlFiles.isEmpty()) {
            recordErrorReason("缺少 Loader 或 XML");
            return new CommandResult(-1, "missing loader or xml");
        }
        File qdlTool = new File(getRootEdlBinDir(), TOOL_QDL);
        if (!qdlTool.exists()) {
            String reason = "缺少 qdl";
            appendWorkLog(runDir, reason);
            recordErrorReason(reason);
            return new CommandResult(-1, "missing qdl");
        }
        String memory = resolveQdlMemoryName();
        if (memory == null || memory.trim().isEmpty()) {
            memory = "ufs";
        }
        boolean configuredVip = hasActiveVipFiles();
        boolean reuseVipSession = configuredVip && !forceFreshSession && canReuseVipSession();
        boolean useVip = configuredVip && !reuseVipSession;
        File digestFile = activeVipDigestFile;
        File signFile = activeVipSignFile;
        String vipEnv = "";
        if (useVip) {
            if (digestFile == null || signFile == null || !digestFile.exists() || !signFile.exists()) {
                String reason = getString(R.string.error_missing_digest_sign);
                appendWorkLog(runDir, reason);
                recordErrorReason(reason);
                return new CommandResult(-1, "missing vip files");
            }
            vipEnv = buildVipEnvPrefix(runDir, digestFile, signFile, loaderFile, memory,
                    skipStorageInitCheck.isChecked(),
                    maxPayloadInput.getText().toString().trim());
            if (vipEnv == null || vipEnv.trim().isEmpty()) {
                String reason = consumeErrorReason();
                if (reason == null || reason.trim().isEmpty()) {
                    reason = getString(R.string.error_missing_digest_sign);
                }
                recordErrorReason(reason);
                return new CommandResult(-1, reason);
            }
        }
        if (reuseVipSession) {
            appendWorkLog(runDir, "qdl 一体流程：复用现有 Firehose/VIP 会话");
        } else {
            appendWorkLog(runDir, useVip
                    ? "qdl 一体流程：发送引导 + VIP 授权 + 刷写"
                    : "qdl 一体流程：发送引导 + 刷写");
        }
        List<String> args = new ArrayList<>();
        args.add("--debug");
        if (dryRunApplies() && xmlPayloadIsDestructive(xmlFiles)) {
            // 演练：仅当本次 XML 真含写/擦操作才注入；qdl -n 整机切 SIM，
            // 写/擦流程内部的隐式 GPT/storageinfo 读取(纯 <read>)不能被污染，否则读回全零导致演练误判失败。
            args.add("--dry-run");
        }
        args.add("--out-chunk-size=" + QDL_OUT_CHUNK_DEFAULT);
        args.add("--storage=" + memory.trim());
        String serial = resolveQdlPortArg();
        if (serial != null && !serial.isEmpty() && !"auto".equalsIgnoreCase(serial)) {
            args.add("--serial=" + serial);
        }
        if (allowMissing) {
            args.add("--allow-missing");
        }
        if (includeDir != null) {
            args.add("--include=" + includeDir.getAbsolutePath());
        }
        if (reuseVipSession) {
            args.add("--no-sahara");
        } else if (useVip) {
            args.add("--signeddigests=" + digestFile.getAbsolutePath());
            args.add("--signeddigests=" + signFile.getAbsolutePath());
        }
        if (!reuseVipSession) {
            args.add(loaderFile.getAbsolutePath());
        }
        for (File xmlFile : xmlFiles) {
            if (xmlFile != null) {
                args.add(xmlFile.getAbsolutePath());
            }
        }
        String resetEnv = buildQdlAutoResetEnvPrefix(shouldEnableQdlAutoReset(xmlFiles) && !suppressAutoReset);
        String portEnv = buildQdlUsbEnvPrefix(null);
        String vipPartEnv = buildVipPartitionEnvPrefix(runDir, loaderFile);
        String opEnv = buildOplusTokenEnvPrefix(configuredVip);
        String cmdLine = resetEnv + portEnv + vipEnv + vipPartEnv + opEnv
                + shQuote(qdlTool.getAbsolutePath()) + " " + joinArgs(args);
        boolean sessionWillReset = willResetFirehoseSession(xmlFiles);
        prepareQdlCommandState(false, useVip);
        CommandResult result;
        try {
            result = runQdlCommandWithRoot(runDir, cmdLine, 0L);
        } finally {
            clearQdlCommandState();
        }
        boolean ok = isCommandSuccess(result);
        String reason = ok ? null : summarizeQdlFailure(result.output);
        boolean vipHandshakeStarted = useVip && didStartVipHandshake(result == null ? null : result.output);
        if (!ok) {
            if (reuseVipSession && shouldResetVipSessionAfterFailure(result == null ? null : result.output)) {
                // 复用会话被污染(如残留 rawmode)：丢弃会话状态，改用全新会话重试一次。
                // 全新会话会重跑 Sahara，其软 replug 兜底能救活卡在 Firehose rawmode 的设备。
                appendWorkLog(runDir, "复用会话异常，重置并改用全新会话重试");
                resetVipAuthState();
                return runQdlXmlCommand(runDir, loaderFile, xmlFiles, includeDir, allowMissing, true);
            }
            recordErrorReason(reason);
        } else if (sessionWillReset) {
            resetVipAuthState();
            lastEdlConnected = false;
        }
        if (!firehoseStepLogged) {
            appendStepResult("发送 Firehose", ok, reason);
            firehoseStepLogged = true;
        }
        if (!configureStepLogged) {
            appendStepResult("配置设备", ok, reason);
            configureStepLogged = true;
        }
        if (useVip) {
            if (vipHandshakeStarted && !vipDigestStepLogged) {
                appendStepResult("发送 Digest", ok, reason);
                vipDigestStepLogged = true;
            }
            if (vipHandshakeStarted && !vipSignStepLogged) {
                appendStepResult("签名", ok, reason);
                vipSignStepLogged = true;
            }
            if (ok) {
                // 授权命令能成功执行本身即证明设备已连接，连接态与授权态原子一致，避免命令期
                // commandRunning 守卫跳过 syncVipAuthState 后被滞后的 lastEdlConnected=false 冲掉授权。
                // 但若本次刷写已触发会话重置(sessionWillReset，设备将重启出 EDL)，则不得覆盖上方刻意
                // 写下的 lastEdlConnected=false——那是"会话已失效、下次连接强制重新校验"的信号，
                // 否则误判 canReuseVipSession 而对已重启设备错误复用(--no-sahara、不发 loader)。
                if (!sessionWillReset) {
                    lastEdlConnected = true;
                    vipSessionHealthy = true;
                }
                vipAuthorized = true;
                persistVipAuthState();
            }
        }
        return result;
    }

    private boolean didStartVipHandshake(String output) {
        if (output == null || output.trim().isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.US);
        return lower.contains("vip step:")
                || lower.contains("signed digest table")
                || lower.contains("signed digest signature");
    }

    private boolean shouldResetVipSessionAfterFailure(String output) {
        if (output == null || output.trim().isEmpty()) {
            return true;
        }
        String lower = output.toLowerCase(Locale.US);
        if (lower.contains("input/output error")
                || lower.contains("usb write failed")
                || lower.contains("usb device disconnected")
                || lower.contains("bulk write failed")
                || lower.contains("bulk write timed out")
                || lower.contains("failed to read sahara request from device")
                || lower.contains("waiting for edl device")
                || lower.contains("firehose startup: read failed")
                || lower.contains("programmer not ready")
                || lower.contains("failed to reopen usb handle")
                || lower.contains("no startup log after usb read errors")
                // 复用会话撞上设备残留 rawmode：configure 被当 raw 数据 → 探测不到 programmer。
                // 须丢弃会话状态，让后续操作走 fresh（fresh 经 Sahara 软 replug 可救活设备）。
                || lower.contains("failed to detect firehose programmer")
                || lower.contains("read non multiple sector size value from usb")) {
            return true;
        }
        return false;
    }

    private boolean willResetFirehoseSession(List<File> xmlFiles) {
        if (xmlFiles == null || xmlFiles.isEmpty()) {
            return false;
        }
        boolean hasWriteLike = false;
        for (File xmlFile : xmlFiles) {
            if (xmlFile == null || !xmlFile.exists()) {
                continue;
            }
            String xml = readFileText(xmlFile);
            if (xml == null) {
                continue;
            }
            String lower = xml.toLowerCase(Locale.US);
            if (lower.contains("<power")) {
                return true;
            }
            // provision(<ufs>) 经 qdl firehose_provision 复位设备(由 --skip-reset 控制，EdlFlash 当前
            // 未透传故必复位)，会话必失效；其复位不受 QDL_AUTO_RESET/suppressAutoReset 影响，直接返回 true。
            if (lower.contains("<ufs")) {
                return true;
            }
            if (lower.contains("<program")
                    || lower.contains("<patch")
                    || lower.contains("<erase")
                    || lower.contains("<setbootablestoragedrive")) {
                hasWriteLike = true;
            }
        }
        // 与实际复位条件保持一致：3940 行用 shouldEnableQdlAutoReset(...) && !suppressAutoReset
        // 决定是否给 qdl 追加复位 op，写类集合也含 setbootablestoragedrive。拆分刷写期间
        // suppressAutoReset=true(设备实际不复位)，会话应继续复用，不能误判为已失效而销毁 VIP 状态。
        return hasWriteLike && isAutoRebootEnabled() && !suppressAutoReset;
    }

    private boolean runFhReadProgramViaConvert(File runDir, FhContext ctx, int lun, long startSector,
                                               long numSectors, int sectorSize, File outputFile,
                                               String label) {
        if (runDir == null || ctx == null || outputFile == null) {
            return false;
        }
        return runFhReadSectors(runDir, ctx, lun, startSector, numSectors, sectorSize, outputFile,
                label);
    }

    private boolean runFhReadProgram(File runDir, FhContext ctx, int lun, long startSector,
                                     long numSectors, int sectorSize, File outputFile,
                                     String label) {
        ensureDirExists(outputFile.getParent());
        return runFhReadSectors(runDir, ctx, lun, startSector, numSectors, sectorSize, outputFile,
                label);
    }

    private boolean runFhWriteProgram(File runDir, FhContext ctx, int lun, long startSector,
                                      long numSectors, int sectorSize, File imageFile, String label) {
        if (shouldUseVipSliceRouting(ctx, sectorSize)) {
            return runVipWriteSlices(runDir, ctx, lun, startSector, numSectors, sectorSize,
                    imageFile, label);
        }
        return runFhWriteProgramSingle(runDir, ctx, lun, startSector, numSectors, sectorSize,
                imageFile, label, null);
    }

    private boolean runFhWriteProgramSingle(File runDir, FhContext ctx, int lun, long startSector,
                                            long numSectors, int sectorSize, File imageFile,
                                            String label, String defaultFileName) {
        if (runDir == null || ctx == null || imageFile == null) {
            return false;
        }
        List<VipSpoofProfile> profiles = buildVipSpoofProfiles(
                ctx, lun, startSector, numSectors, sectorSize, label,
                defaultFileName == null || defaultFileName.trim().isEmpty()
                        ? imageFile.getName() : defaultFileName);
        for (int i = 0; i < profiles.size(); i++) {
            VipSpoofProfile profile = profiles.get(i);
            File stagedImage = prepareProgramImageForProfile(runDir, imageFile, profile.deviceFilename);
            if (stagedImage == null || !rootExists(stagedImage.getAbsolutePath(), false)) {
                continue;
            }
            File xmlFile = writeQdlProgramXml(runDir, stagedImage, profile.deviceLabel, lun,
                    startSector, numSectors, sectorSize);
            if (xmlFile == null) {
                if (!stagedImage.equals(imageFile)) {
                    stagedImage.delete();
                }
                continue;
            }
            if (profiles.size() > 1) {
                appendWorkLog(runDir, "刷写策略 " + (i + 1) + "/" + profiles.size()
                        + ": " + profile.deviceLabel + " / " + stagedImage.getName());
            }
            try {
                List<File> xmls = Collections.singletonList(xmlFile);
                File includeDir = stagedImage.getParentFile();
                CommandResult result = runQdlXmlCommand(runDir, ctx.loaderFile, xmls, includeDir, false);
                if (isCommandSuccess(result)) {
                    if (!stagedImage.equals(imageFile)) {
                        stagedImage.delete();
                    }
                    return true;
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                // stagedImage 是 runDir 内的软链接，delete() 仅删链接本身（不动目标）
                if (!stagedImage.equals(imageFile)) {
                    stagedImage.delete();
                }
            }
        }
        return false;
    }

    private boolean shouldUseVipSpoof(FhContext ctx) {
        if (ctx == null) {
            return false;
        }
        return isOplusVipPath(ctx.loaderFile)
                || isOplusVipPath(activeVipDigestFile)
                || isOplusVipPath(activeVipSignFile);
    }

    private boolean isVipGptRead(String label, long startSector, long numSectors, int sectorSize) {
        String lower = label == null ? "" : label.trim().toLowerCase(Locale.US);
        if (lower.contains("gpt")) {
            return true;
        }
        long endSector = startSector + Math.max(0L, numSectors - 1L);
        long gptLimit = sectorSize <= 512 ? 33L : 5L;
        return startSector >= 0 && endSector <= gptLimit;
    }

    private String sanitizeVipPartitionName(String name) {
        String base = name == null ? "" : name.trim().toLowerCase(Locale.US);
        if (base.isEmpty()) {
            return "rawdata";
        }
        base = base.replaceAll("[^a-z0-9._-]+", "_");
        base = base.replaceAll("_+", "_");
        if (base.startsWith("_")) {
            base = base.substring(1);
        }
        if (base.endsWith("_")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = "rawdata";
        }
        if (base.length() > 32) {
            base = base.substring(0, 32);
        }
        return base;
    }

    private void addVipSpoofProfile(List<VipSpoofProfile> profiles, Set<String> seen,
                                    String label, String filename) {
        String safeLabel = label == null ? "" : label.trim();
        String safeFile = filename == null ? "" : sanitizeFileName(new File(filename).getName());
        String key = safeLabel + "\n" + safeFile;
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        profiles.add(new VipSpoofProfile(safeLabel, safeFile));
    }

    private List<VipSpoofProfile> buildVipSpoofProfiles(FhContext ctx, int lun,
                                                        long startSector, long numSectors,
                                                        int sectorSize, String label,
                                                        String defaultFileName) {
        List<VipSpoofProfile> profiles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String baseLabel = label == null ? "" : label.trim();
        if (baseLabel.isEmpty()) {
            baseLabel = "read";
        }
        String baseFile = defaultFileName == null ? "" : new File(defaultFileName).getName();
        if (baseFile.trim().isEmpty()) {
            baseFile = sanitizeVipPartitionName(baseLabel) + ".bin";
        }
        if (!shouldUseVipSpoof(ctx)) {
            addVipSpoofProfile(profiles, seen, baseLabel, baseFile);
            return profiles;
        }

        boolean gptRead = isVipGptRead(baseLabel, startSector, numSectors, sectorSize);
        String safePartition = sanitizeVipPartitionName(baseLabel);

        // 读 GPT 只需固定 label "PrimaryGPT"（对照 linux-msm/qdl gpt.c、bkerler/edl）：
        // OPlus firehose 认这个 label 即放行。早返回，不再对 BackupGPT/gptmain/ssd/buffer 等
        // 做无谓穷举——这些变体只是本地文件名不同，设备侧 label 始终是 PrimaryGPT，多试只会
        // 在每个 LUN 上空跑整轮 Sahara+握手，是读分区表卡顿的主因。
        if (gptRead) {
            addVipSpoofProfile(profiles, seen, "PrimaryGPT", "gpt_main" + lun + ".bin");
            return profiles;
        }

        if (!isGptMetaEntry(baseLabel)) {
            addVipSpoofProfile(profiles, seen, baseLabel, baseFile);
            addVipSpoofProfile(profiles, seen, "PrimaryGPT", "gpt_main" + lun + ".bin");
            addVipSpoofProfile(profiles, seen, "PrimaryGPT", "gptmain" + lun + ".bin");
            addVipSpoofProfile(profiles, seen, "PrimaryGPT", "gpt_main0.bin");
            addVipSpoofProfile(profiles, seen, "PrimaryGPT", "gptmain0.bin");
            addVipSpoofProfile(profiles, seen, "BackupGPT", "gpt_backup" + lun + ".bin");
            addVipSpoofProfile(profiles, seen, "BackupGPT", "gptbackup" + lun + ".bin");
            addVipSpoofProfile(profiles, seen, baseLabel, "gpt_backup0.bin");
            addVipSpoofProfile(profiles, seen, baseLabel, safePartition + ".bin");
        }

        addVipSpoofProfile(profiles, seen, "ssd", "ssd");
        addVipSpoofProfile(profiles, seen, "gpt_main0.bin", "gpt_main0.bin");
        addVipSpoofProfile(profiles, seen, "gptmain0.bin", "gptmain0.bin");
        addVipSpoofProfile(profiles, seen, "buffer", "buffer.bin");
        addVipSpoofProfile(profiles, seen, baseLabel, baseFile);

        return profiles;
    }

    private File prepareProgramImageForProfile(File runDir, File imageFile, String deviceFilename) {
        if (runDir == null || imageFile == null) {
            return null;
        }
        String targetName = deviceFilename == null ? "" : deviceFilename.trim();
        if (targetName.isEmpty()) {
            return imageFile;
        }
        targetName = sanitizeFileName(new File(targetName).getName());
        if (targetName.isEmpty() || targetName.equals(imageFile.getName())) {
            return imageFile;
        }
        // 不拷贝大镜像：用 root 软链接把 spoof 文件名指向真实镜像，qdl 以 root 跟随读取。
        File staged = new File(runDir, targetName);
        String cmd = "rm -f " + shQuote(staged.getAbsolutePath())
                + "; ln -s " + shQuote(imageFile.getAbsolutePath()) + " " + shQuote(staged.getAbsolutePath());
        try {
            CommandResult r = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            if (r != null && r.exitCode == 0 && rootExists(staged.getAbsolutePath(), false)) {
                return staged;
            }
        } catch (IOException | InterruptedException e) {
            appendWorkLog(runDir, "准备刷写镜像失败: " + e.getMessage());
        }
        return null;
    }

    private boolean shouldUseVipSliceRouting(FhContext ctx, int sectorSize) {
        return shouldUseVipSpoof(ctx) && sectorSize > 0;
    }

    private long resolveVipSpecialSector(int sectorSize) {
        return sectorSize <= 512 ? 34L : 6L;
    }

    private long resolveVipFirstPartitionStart(int sectorSize) {
        // 首个真实分区从首可用 LBA 起：512 字节布局 GPT 区为 LBA 0-33(34 个扇区)，首分区在 LBA 34；
        // 4096 字节布局 GPT 区为 LBA 0-5，首分区在 LBA 6。与 resolveVipSpecialSector 一致。
        // 早期 512 返回 35 会跳过恰好起始于 LBA 34 的首分区，令 VIP 首分区读伪装定位到错误分区。
        return sectorSize <= 512 ? 34L : 6L;
    }

    private long resolveVipMaxChunkSectors(int sectorSize) {
        long size = sectorSize > 0 ? sectorSize : 4096L;
        long sectors = (64L * 1024L * 1024L) / size;
        return Math.max(1L, sectors);
    }

    private GptEntry findVipFirstPartitionEntry(int lun, int sectorSize) {
        if (gptEntries.isEmpty()) {
            return null;
        }
        long target = resolveVipFirstPartitionStart(sectorSize);
        GptEntry best = null;
        long bestStart = Long.MAX_VALUE;
        for (GptEntry entry : gptEntries) {
            if (entry == null) {
                continue;
            }
            if (parseIntSafe(entry.partition, -1) != lun) {
                continue;
            }
            long start = parseLongSafe(entry.startSector, -1L);
            if (start < target) {
                continue;
            }
            if (start < bestStart) {
                best = entry;
                bestStart = start;
            }
        }
        return best;
    }

    private String buildVipMainFileName(int lun) {
        return "gpt_main" + lun + ".bin";
    }

    private String buildVipFirstPartitionFileName(GptEntry entry) {
        if (entry == null || entry.name == null || entry.name.trim().isEmpty()) {
            return "";
        }
        return sanitizeVipPartitionName(entry.name) + ".bin";
    }

    private List<VipRangeSlice> buildVipRangeSlices(int lun, long startSector, long numSectors,
                                                    int sectorSize, String label,
                                                    String defaultFileName) {
        List<VipRangeSlice> slices = new ArrayList<>();
        if (numSectors <= 0) {
            return slices;
        }

        long specialSector = resolveVipSpecialSector(sectorSize);
        long maxChunkSectors = resolveVipMaxChunkSectors(sectorSize);
        GptEntry firstPartition = findVipFirstPartitionEntry(lun, sectorSize);
        String baseLabel = label == null ? "" : label.trim();
        if (baseLabel.isEmpty()) {
            baseLabel = "PrimaryGPT";
        }
        String baseFile = defaultFileName == null ? "" : defaultFileName.trim();
        if (baseFile.isEmpty()) {
            baseFile = sanitizeVipPartitionName(baseLabel) + ".bin";
        }

        long currentSector = startSector;
        long remainingSectors = numSectors;
        long fileOffsetBytes = 0L;

        while (remainingSectors > 0) {
            long sliceSectors;
            String sliceLabel;
            String sliceFile;

            if (currentSector < specialSector) {
                sliceSectors = Math.min(remainingSectors, specialSector - currentSector);
                sliceLabel = "PrimaryGPT";
                sliceFile = buildVipMainFileName(lun);
            } else if (currentSector == specialSector) {
                sliceSectors = 1L;
                if (firstPartition != null) {
                    sliceLabel = firstPartition.name;
                    sliceFile = buildVipFirstPartitionFileName(firstPartition);
                } else {
                    sliceLabel = baseLabel;
                    sliceFile = baseFile;
                }
            } else {
                sliceSectors = Math.min(remainingSectors, maxChunkSectors);
                sliceLabel = "PrimaryGPT";
                sliceFile = buildVipMainFileName(lun);
            }

            slices.add(new VipRangeSlice(currentSector, sliceSectors, sliceLabel,
                    sliceFile, fileOffsetBytes));
            currentSector += sliceSectors;
            remainingSectors -= sliceSectors;
            fileOffsetBytes += sliceSectors * (long) sectorSize;
        }

        return slices;
    }

    private List<VipRangeSlice> buildVipBackupRangeSlices(int lun, long startSector,
                                                          long numSectors, int sectorSize) {
        List<VipRangeSlice> slices = new ArrayList<>();
        if (numSectors <= 0) {
            return slices;
        }
        long maxChunkSectors = resolveVipMaxChunkSectors(sectorSize);
        long currentSector = startSector;
        long remainingSectors = numSectors;
        long fileOffsetBytes = 0L;
        while (remainingSectors > 0) {
            long sliceSectors = Math.min(remainingSectors, maxChunkSectors);
            slices.add(new VipRangeSlice(currentSector, sliceSectors, "BackupGPT",
                    "gpt_backup" + lun + ".bin", fileOffsetBytes));
            currentSector += sliceSectors;
            remainingSectors -= sliceSectors;
            fileOffsetBytes += sliceSectors * (long) sectorSize;
        }
        return slices;
    }

    private List<List<VipRangeSlice>> buildVipRangeRoutes(int lun, long startSector,
                                                          long numSectors, int sectorSize,
                                                          String label,
                                                          String defaultFileName) {
        List<List<VipRangeSlice>> routes = new ArrayList<>();
        List<VipRangeSlice> primary = buildVipRangeSlices(lun, startSector, numSectors,
                sectorSize, label, defaultFileName);
        if (!primary.isEmpty()) {
            routes.add(primary);
        }
        List<VipRangeSlice> backup = buildVipBackupRangeSlices(lun, startSector, numSectors,
                sectorSize);
        if (!backup.isEmpty()) {
            routes.add(backup);
        }
        return routes;
    }

    private boolean runVipReadSlices(File runDir, FhContext ctx, int lun, long startSector,
                                     long numSectors, int sectorSize, File outputFile,
                                     String label) {
        if (runDir == null || ctx == null || outputFile == null) {
            return false;
        }
        List<List<VipRangeSlice>> routes = buildVipRangeRoutes(lun, startSector, numSectors,
                sectorSize, label, outputFile.getName());
        ensureDir(outputFile.getParentFile());
        for (List<VipRangeSlice> slices : routes) {
            if (outputFile.exists()) {
                outputFile.delete();
            }
            boolean routeOk = !slices.isEmpty();
            for (int i = 0; i < slices.size(); i++) {
                VipRangeSlice slice = slices.get(i);
                File tempFile = new File(runDir, "vip_read_slice_" + i + "_"
                        + sanitizeFileName(outputFile.getName()));
                boolean ok = runFhReadSectorsSingle(runDir, ctx, lun, slice.startSector,
                        slice.numSectors, sectorSize, tempFile, slice.deviceLabel,
                        slice.deviceFilename);
                if (!ok || !tempFile.exists() || tempFile.length() <= 0) {
                    routeOk = false;
                } else {
                    try {
                        appendFileToFile(tempFile, outputFile);
                    } catch (IOException e) {
                        routeOk = false;
                        appendWorkLog(runDir, "合并读取数据失败: " + e.getMessage());
                        recordErrorReason("合并读取数据失败");
                    }
                }
                tempFile.delete();
                if (!routeOk) {
                    break;
                }
            }
            if (routeOk && outputFile.exists() && outputFile.length() > 0) {
                return true;
            }
            if (outputFile.exists()) {
                outputFile.delete();
            }
        }
        return false;
    }

    private boolean runVipWriteSlices(File runDir, FhContext ctx, int lun, long startSector,
                                      long numSectors, int sectorSize, File imageFile,
                                      String label) {
        if (runDir == null || ctx == null || imageFile == null
                || !rootExists(imageFile.getAbsolutePath(), false)) {
            return false;
        }
        List<List<VipRangeSlice>> routes = buildVipRangeRoutes(lun, startSector, numSectors,
                sectorSize, label, imageFile.getName());
        // 一条 route 的全部 slice 合并成单个含多 <program> 的 rawprogram，一次 qdl 单会话写完
        // (firehose_execute_ops 首个 configure 时 oplus 授权/GPT 解析各只一次，随后顺序写完所有
        // program)。取代原"每 64MB 分片物理切文件 + 各起一个 qdl 进程"——super(9.9GB)由约 148
        // 个 qdl 进程降为 1 个，消除每分片重发 loader/重授权/重读 GPT 的死循环式开销。
        for (List<VipRangeSlice> slices : routes) {
            if (slices.isEmpty()) {
                continue;
            }
            List<File> staged = new ArrayList<>();
            File xml = buildVipRouteXml(runDir, lun, sectorSize, slices, imageFile, staged);
            boolean ok = false;
            if (xml != null) {
                try {
                    // includeDir = runDir：route 内的伪装软链接都落在 runDir，qdl 按 search_path 解析
                    CommandResult result = runQdlXmlCommand(runDir, ctx.loaderFile,
                            Collections.singletonList(xml), runDir, false);
                    ok = isCommandSuccess(result);
                } catch (IOException | InterruptedException ignored) {
                }
            }
            for (File link : staged) {
                link.delete();
            }
            if (xml != null) {
                xml.delete();
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    // 把一条 VIP route 的全部 slice 拼成单个多 <program> rawprogram XML：所有 slice 共享同一真实镜像
    // (用 root 软链接成各自的伪装文件名 gpt_main/backup{lun}.bin)，靠 file_sector_offset(扇区数)
    // 错开在镜像内的读取起点，无需逐片物理切文件。route 内同名伪装只软链一次。返回写好的 XML，软链
    // 接收集追加到 stagedOut 供调用方刷完后清理；任一软链接失败返回 null。
    private File buildVipRouteXml(File runDir, int lun, int sectorSize,
                                  List<VipRangeSlice> route, File imageFile, List<File> stagedOut) {
        if (runDir == null || imageFile == null || route == null || route.isEmpty()
                || sectorSize <= 0) {
            return null;
        }
        Map<String, File> links = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" ?>\n<data>\n");
        for (VipRangeSlice slice : route) {
            String fname = slice.deviceFilename == null ? "" : slice.deviceFilename.trim();
            File link = links.get(fname);
            if (link == null) {
                link = prepareProgramImageForProfile(runDir, imageFile, fname);
                if (link == null || !rootExists(link.getAbsolutePath(), false)) {
                    return null;
                }
                links.put(fname, link);
                if (!link.equals(imageFile)) {
                    stagedOut.add(link);
                }
            }
            long offsetSectors = slice.fileOffsetBytes / sectorSize;
            sb.append("  <program SECTOR_SIZE_IN_BYTES=\"").append(sectorSize).append("\" ")
                    .append("file_sector_offset=\"").append(offsetSectors).append("\" ")
                    .append("filename=\"").append(link.getName()).append("\" ")
                    .append("label=\"").append(slice.deviceLabel).append("\" ")
                    .append("physical_partition_number=\"").append(lun).append("\" ")
                    .append("start_sector=\"").append(slice.startSector).append("\" ")
                    .append("num_partition_sectors=\"").append(slice.numSectors).append("\" ")
                    .append("sparse=\"false\" />\n");
        }
        sb.append("</data>\n");
        try {
            return writeTextFile(runDir, "vip_route_lun" + lun + "_"
                    + sanitizeFileName(route.get(0).deviceLabel) + ".xml", sb.toString());
        } catch (IOException e) {
            appendWorkLog(runDir, "生成 VIP 批量刷写 XML 失败: " + e.getMessage());
            return null;
        }
    }

    // OplusEdlTool Step1：刷普通分区前先照刷包内 PrimaryGPT/BackupGPT(真实 label，verbatim start_sector)。
    // VIP split 旧版用 isFlashableProgramEntry 跳过包内 GPT，致设备分区表不更新成包内新布局→.ops 不开机；
    // 而 !splitMode 路径把整 rawprogram(含 GPT)交 qdl 故能开机(这正是 rawprogram 包能开机、.ops 不能的差别)。
    // OPlus loader 放行 PrimaryGPT/BackupGPT 写入(分区伪装机制的根基)，故 GPT 用真实 label 直刷即可；
    // start_sector 原样下发(BackupGPT 的 NUM_DISK_SECTORS-5 由设备 firehose 解析，与 !splitMode/bkerler 一致)。
    // best-effort：GPT 刷失败仅告警续刷分区——失败时退化为旧行为，不引入新失败模式。
    private void flashPackageGptEntries(File runDir, FhContext ctx, List<ProgramEntry> programs,
                                        File imageDir, File rawprogramFile) {
        if (runDir == null || ctx == null || programs == null) {
            return;
        }
        File parentDir = rawprogramFile == null ? null : rawprogramFile.getParentFile();
        File includeDir = null;
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" ?>\n<data>\n");
        int count = 0;
        for (ProgramEntry entry : programs) {
            if (entry == null || isFlashableProgramEntry(entry)) {
                continue; // 只刷 GPT 元数据项(PrimaryGPT/BackupGPT)，普通分区由下方主循环处理
            }
            String filename = entry.filename == null ? "" : entry.filename.trim();
            if (filename.isEmpty() || "disk".equalsIgnoreCase(filename)) {
                continue; // 无镜像的纯布局项，跳过
            }
            String startSector = entry.attrs.get("start_sector");
            String numSectors = entry.attrs.get("num_partition_sectors");
            if (startSector == null || startSector.trim().isEmpty()
                    || numSectors == null || numSectors.trim().isEmpty()) {
                continue;
            }
            File imageFile = resolveProgramImageFile(filename, imageDir, parentDir);
            if (imageFile == null || !rootExists(imageFile.getAbsolutePath(), false)) {
                appendWorkLog(runDir, "包内 GPT 镜像缺失，跳过: " + filename);
                continue;
            }
            if (includeDir == null) {
                includeDir = imageFile.getParentFile();
            } else if (!includeDir.equals(imageFile.getParentFile())) {
                // 多个 GPT 文件分散在不同目录时，单次 XML 只能共享一个 search_path；异目录项本轮跳过
                appendWorkLog(runDir, "GPT 镜像目录不一致，跳过: " + filename);
                continue;
            }
            int sectorSize = parseIntSafe(entry.attrs.get("SECTOR_SIZE_IN_BYTES"), ctx.sectorSize);
            sb.append("  <program SECTOR_SIZE_IN_BYTES=\"").append(sectorSize).append("\" ")
                    .append("filename=\"").append(imageFile.getName()).append("\" ")
                    .append("label=\"").append(resolveProgramLabel(entry)).append("\" ")
                    .append("physical_partition_number=\"")
                    .append(parseIntSafe(entry.attrs.get("physical_partition_number"), 0)).append("\" ")
                    .append("num_partition_sectors=\"").append(numSectors.trim()).append("\" ")
                    .append("start_sector=\"").append(startSector.trim()).append("\" ")
                    .append("sparse=\"false\" />\n");
            count++;
        }
        if (count == 0) {
            return;
        }
        sb.append("</data>\n");
        try {
            File xmlFile = writeTextFile(runDir, "gpt_flash.xml", sb.toString());
            appendWorkLog(runDir, "照刷包内 GPT: " + count + " 项 (PrimaryGPT/BackupGPT 真实 label)");
            CommandResult result = runQdlXmlCommand(runDir, ctx.loaderFile,
                    Collections.singletonList(xmlFile), includeDir, false);
            if (!isCommandSuccess(result)) {
                appendWorkLog(runDir, "包内 GPT 刷写未成功(续刷分区，退化为旧行为)");
            }
        } catch (IOException | InterruptedException e) {
            appendWorkLog(runDir, "包内 GPT 刷写异常(续刷分区): " + e.getMessage());
        }
    }

    private boolean runVipEraseSlices(File runDir, FhContext ctx, int lun, long startSector,
                                      long numSectors, int sectorSize, String label) {
        if (runDir == null || ctx == null) {
            return false;
        }
        List<List<VipRangeSlice>> routes = buildVipRangeRoutes(lun, startSector, numSectors,
                sectorSize, label, sanitizeVipPartitionName(label) + ".bin");
        for (List<VipRangeSlice> slices : routes) {
            boolean routeOk = !slices.isEmpty();
            for (VipRangeSlice slice : slices) {
                boolean ok = runFhEraseRangeSingle(runDir, ctx, lun, slice.startSector,
                        slice.numSectors, sectorSize, slice.deviceLabel, slice.deviceFilename);
                if (!ok) {
                    routeOk = false;
                    break;
                }
            }
            if (routeOk) {
                return true;
            }
        }
        return false;
    }

    private void appendFileToFile(File src, File dest) throws IOException {
        ensureDir(dest == null ? null : dest.getParentFile());
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest, true)) {
            copyStream(in, out);
        }
    }

    private boolean runFhFooter(File runDir, File loaderFile, String outputPath) {
        if (outputPath == null || outputPath.trim().isEmpty()) {
            outputPath = buildReadOutputPath("footer", "", "", "", "footer.img");
        }
        outputPath = ensureImgExtension(outputPath);
        outputPath = normalizeUserOutputPath(outputPath);
        ensureDirExists(new File(outputPath).getParent());
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return finishStep("提取 Footer", false);
        }
        if (gptEntries.isEmpty()) {
            if (!runFhGptList(runDir, loaderFile)) {
                appendWorkLog(runDir, "未读取到分区表");
                recordErrorReason("未读取到分区表");
                return finishStep("提取 Footer", false);
            }
        }
        List<String> candidates = Arrays.asList("userdata2", "metadata", "userdata",
                "reserved1", "reserved2", "reserved3");
        boolean sawCandidate = false;
        for (String name : candidates) {
            GptEntry entry = findGptEntry(name, "");
            if (entry == null) {
                continue;
            }
            int lun = parseIntSafe(entry.partition, 0);
            long start = parseLongSafe(entry.startSector, -1L);
            long total = parseLongSafe(entry.numSectors, -1L);
            int sectorSize = parseIntSafe(entry.sectorSize, ctx.sectorSize);
            if (start < 0 || total <= 0 || sectorSize <= 0) {
                continue;
            }
            long footerSectors = Math.max(1L, 0x4000L / sectorSize);
            if (total < footerSectors) {
                continue;
            }
            long footerStart = start + total - footerSectors;
            File tempFile = new File(runDir, "footer_" + sanitizeFileName(entry.name) + ".bin");
            boolean ok = runFhReadSectors(runDir, ctx, lun, footerStart, footerSectors,
                    sectorSize, tempFile, entry.name);
            if (!ok || !tempFile.exists()) {
                continue;
            }
            sawCandidate = true;
            byte[] header = readFilePrefix(tempFile, 4);
            if (header != null && header.length == 4) {
                long sig = ((long) header[0] & 0xff)
                        | (((long) header[1] & 0xff) << 8)
                        | (((long) header[2] & 0xff) << 16)
                        | (((long) header[3] & 0xff) << 24);
                if ((sig & 0xFFFFFFF0L) == 0xD0B5B1C0L) {
                    File outFile = new File(outputPath);
                    String outName = outFile.getName();
                    if (!copyToDownloadDir(tempFile, outName)) {
                        appendWorkLog(runDir, "保存 Footer 失败");
                        recordErrorReason("保存 Footer 失败");
                        return finishStep("提取 Footer", false);
                    }
                    showToast("Footer 已保存到 " + new File(DEFAULT_DOWNLOAD_DIR, outName).getAbsolutePath());
                    return finishStep("提取 Footer", true);
                }
                appendWorkLog(runDir, "Footer 魔数不匹配");
                recordErrorReason("Footer 魔数不匹配");
                return finishStep("提取 Footer", false);
            }
        }
        if (sawCandidate) {
            appendWorkLog(runDir, "未匹配到 Footer 魔数");
            recordErrorReason("未匹配到 Footer 魔数");
            return finishStep("提取 Footer", false);
        }
        appendWorkLog(runDir, "未找到 Footer 分区");
        recordErrorReason("未找到 Footer 分区");
        return finishStep("提取 Footer", false);
    }

    private boolean runFhPeek(File runDir, File loaderFile,
                              String offsetText, String lengthText, String outputPath) {
        long offset = parseNumberFlexible(offsetText, -1L);
        long length = parseNumberFlexible(lengthText, -1L);
        if (offset < 0 || length <= 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            outputPath = buildReadOutputPath("peek", offsetText, lengthText, "", "");
        }
        outputPath = ensureImgExtension(outputPath);
        outputPath = normalizeUserOutputPath(outputPath);
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return finishStep("读取内存", false);
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "读取内存");
        byte[] data = runFhPeekBytes(runDir, ctx, offset, length);
        if (data == null || data.length == 0) {
            recordErrorReason("读取内存失败");
            return finishStep("读取内存", false);
        }
        File outFile = new File(outputPath);
        File tempFile = new File(runDir, outFile.getName());
        try (OutputStream out = new FileOutputStream(tempFile)) {
            out.write(data);
        } catch (IOException e) {
            appendWorkLog(runDir, "写入文件失败: " + e.getMessage());
            recordErrorReason("写入文件失败");
            return finishStep("读取内存", false);
        }
        if (!copyToDownloadDir(tempFile, outFile.getName())) {
            appendWorkLog(runDir, "保存文件失败");
            recordErrorReason("保存文件失败");
            return finishStep("读取内存", false);
        }
        showToast("提取完成，文件已保存到 " + new File(DEFAULT_DOWNLOAD_DIR, outFile.getName()).getAbsolutePath());
        return finishStep("读取内存", true);
    }

    private boolean runFhPeekHex(File runDir, File loaderFile,
                                 String offsetText, String lengthText) {
        long offset = parseNumberFlexible(offsetText, -1L);
        long length = parseNumberFlexible(lengthText, -1L);
        if (offset < 0 || length <= 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return finishStep("读取内存", false);
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "读取内存");
        byte[] data = runFhPeekBytes(runDir, ctx, offset, length);
        if (data == null || data.length == 0) {
            return false;
        }
        appendLog(bytesToHex(data));
        return true;
    }

    private boolean runFhPeekDword(File runDir, File loaderFile, String offsetText) {
        long offset = parseNumberFlexible(offsetText, -1L);
        if (offset < 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "读取内存");
        byte[] data = runFhPeekBytes(runDir, ctx, offset, 4);
        if (data == null || data.length < 4) {
            return false;
        }
        long value = ((long) data[0] & 0xff)
                | (((long) data[1] & 0xff) << 8)
                | (((long) data[2] & 0xff) << 16)
                | (((long) data[3] & 0xff) << 24);
        appendLog("0x" + Long.toHexString(value & 0xFFFFFFFFL));
        return true;
    }

    private boolean runFhPeekQword(File runDir, File loaderFile, String offsetText) {
        long offset = parseNumberFlexible(offsetText, -1L);
        if (offset < 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "读取内存");
        byte[] data = runFhPeekBytes(runDir, ctx, offset, 8);
        if (data == null || data.length < 8) {
            return false;
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= ((long) data[i] & 0xff) << (i * 8);
        }
        appendLog("0x" + Long.toHexString(value));
        return true;
    }

    private boolean runFhDumpMemTable(File runDir, File loaderFile, String outputPath) {
        return runFhDumpRange(runDir, loaderFile, outputPath, "memtbl", 2);
    }

    private boolean runFhDumpPbl(File runDir, File loaderFile, String outputPath) {
        return runFhDumpRange(runDir, loaderFile, outputPath, "pbl", 0);
    }

    private boolean runFhDumpQfprom(File runDir, File loaderFile, String outputPath) {
        return runFhDumpRange(runDir, loaderFile, outputPath, "qfprom", 1);
    }

    private boolean runFhDumpRange(File runDir, File loaderFile, String outputPath,
                                   String label, int typeIndex) {
        ParsedOutput parsed = parseOutputWithRange(outputPath, label + ".img");
        File outFile = parsed.outputFile;
        QualcommTables.MemRange range = parsed.range;
        if (range == null) {
            String target = resolveTargetName(loaderFile);
            range = QualcommTables.getInfoRange(target, typeIndex);
        }
        if (range == null) {
            appendWorkLog(runDir, "未找到 " + label + " 偏移，请使用 filename@0xADDR:0xSIZE 格式");
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "读取 " + label);
        byte[] data = runFhPeekBytes(runDir, ctx, range.addr, range.size);
        if (data == null || data.length == 0) {
            return false;
        }
        ensureDirExists(outFile.getParent());
        try (OutputStream out = new FileOutputStream(outFile)) {
            out.write(data);
        } catch (IOException e) {
            appendWorkLog(runDir, "写入文件失败: " + e.getMessage());
            return false;
        }
        showToast(label + " 已保存到 " + outFile.getAbsolutePath());
        return true;
    }

    private boolean runFhSecureboot(File runDir, File loaderFile, String addressText) {
        Long address = null;
        if (addressText != null && !addressText.trim().isEmpty()) {
            long parsed = parseNumberFlexible(addressText, -1L);
            if (parsed >= 0) {
                address = parsed;
            }
        }
        if (address == null) {
            String target = resolveTargetName(loaderFile);
            address = QualcommTables.getSecurebootAddr(target);
        }
        if (address == null || address <= 0) {
            appendWorkLog(runDir, "未找到 secureboot 偏移，可输入地址参数");
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "读取 secureboot");
        byte[] data = runFhPeekBytes(runDir, ctx, address, 4);
        if (data == null || data.length < 4) {
            return false;
        }
        long value = ((long) data[0] & 0xff)
                | (((long) data[1] & 0xff) << 8)
                | (((long) data[2] & 0xff) << 16)
                | (((long) data[3] & 0xff) << 24);
        boolean secure = false;
        for (int area = 0; area < 4; area++) {
            int secBoot = (int) ((value >> (area * 8)) & 0xFF);
            int pkIndex = secBoot & 3;
            boolean oemPkHash = ((secBoot >> 4) & 1) == 1;
            boolean authEnabled = ((secBoot >> 5) & 1) == 1;
            boolean useSerial = ((secBoot >> 6) & 1) == 1;
            if (authEnabled) {
                secure = true;
            }
            appendLog("Sec_Boot" + area + " PKHash-Index:" + pkIndex
                    + " OEM_PKHash:" + oemPkHash
                    + " Auth_Enabled:" + authEnabled
                    + " Use_Serial:" + useSerial);
        }
        appendLog(secure ? "Secure boot enabled." : "Secure boot disabled.");
        return true;
    }

    private boolean runFhMemoryDump(File runDir, File loaderFile, String partsText) {
        String parts = partsText == null ? "" : partsText.trim();
        if (parts.isEmpty()) {
            parts = partitionsInput == null ? "" : partitionsInput.getText().toString().trim();
        }
        if (parts.isEmpty()) {
            return runFhReadAll(runDir, loaderFile);
        }
        String[] list = parts.split("[,\\s]+");
        for (String part : list) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            boolean ok = runFhReadPartition(runDir, loaderFile, part.trim(), "");
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private boolean runFhServer(File runDir, File loaderFile) {
        if (tcpPortInput == null) {
            appendWorkLog(runDir, "缺少 TCP 端口设置");
            return false;
        }
        int port = parseIntSafe(tcpPortInput.getText().toString().trim(), 9008);
        if (port <= 0) {
            port = 9008;
        }
        startProgress("服务器模式");
        appendLog("TCP 服务器: 127.0.0.1:" + port);
        try (ServerSocket server = new ServerSocket(port)) {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) {
                            continue;
                        }
                        String response = "<NAK>\n";
                        int sep = trimmed.indexOf(':');
                        if (sep > 0) {
                            String cmd = trimmed.substring(0, sep).trim();
                            String argsText = trimmed.substring(sep + 1).trim();
                            String[] parts = argsText.isEmpty() ? new String[0] : argsText.split(",");
                            String arg1 = parts.length > 0 ? parts[0].trim() : "";
                            String arg2 = parts.length > 1 ? parts[1].trim() : "";
                            String arg3 = parts.length > 2 ? parts[2].trim() : "";
                            if ("server".equalsIgnoreCase(cmd)) {
                                response = "<NAK>\n";
                            } else {
                                QfilInputs qfilInputs = null;
                                if ("qfil".equalsIgnoreCase(cmd)) {
                                    qfilInputs = resolveQfilInputs(arg1, arg2, arg3);
                                }
                                File cmdDir = new File(getFilesDir(), "work/server_" + System.currentTimeMillis());
                                cmdDir.mkdirs();
                                boolean ok = runFhCommand(cmdDir, cmd, loaderFile, arg1, arg2, arg3, qfilInputs);
                                response = ok ? "<ACK>\n" : "<NAK>\n";
                            }
                        }
                        writer.write(response);
                        writer.flush();
                    }
                } catch (IOException e) {
                    appendWorkLog(runDir, "TCP 会话异常: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            appendWorkLog(runDir, "TCP 服务器启动失败: " + e.getMessage());
            return false;
        }
        return true;
    }

    private boolean runFhProvision(File runDir, File loaderFile, String xmlPath) {
        return runFhSendXmlFile(runDir, loaderFile, xmlPath);
    }

    private boolean runFhModules(File runDir, File loaderFile, String command, String options) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        String payload = cmd;
        if (options != null && !options.trim().isEmpty()) {
            payload = cmd + " " + options.trim();
        }
        return runFhSendCommand(runDir, loaderFile, payload);
    }

    private boolean runFhPokeFile(File runDir, File loaderFile, String offsetText, String filePath) {
        long offset = parseNumberFlexible(offsetText, -1L);
        if (offset < 0 || filePath == null || filePath.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        File file = new File(filePath);
        if (!rootExists(filePath, false)) {
            appendWorkLog(runDir, "文件不存在: " + filePath);
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startProgress("写入内存");
        return runFhPokeBytes(runDir, ctx, offset, file);
    }

    private boolean runFhPokeHex(File runDir, File loaderFile, String offsetText, String hexText) {
        long offset = parseNumberFlexible(offsetText, -1L);
        if (offset < 0 || hexText == null || hexText.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        byte[] data = parseHexBytes(hexText);
        if (data == null || data.length == 0) {
            appendWorkLog(runDir, "HEX 数据无效");
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startProgress("写入内存");
        return runFhPokeBytes(runDir, ctx, offset, data);
    }

    private boolean runFhPokeDword(File runDir, File loaderFile, String offsetText, String valueText) {
        long offset = parseNumberFlexible(offsetText, -1L);
        long value = parseNumberFlexible(valueText, -1L);
        if (offset < 0 || value < 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        byte[] data = new byte[4];
        data[0] = (byte) (value & 0xff);
        data[1] = (byte) ((value >> 8) & 0xff);
        data[2] = (byte) ((value >> 16) & 0xff);
        data[3] = (byte) ((value >> 24) & 0xff);
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startProgress("写入内存");
        return runFhPokeBytes(runDir, ctx, offset, data);
    }

    private boolean runFhPokeQword(File runDir, File loaderFile, String offsetText, String valueText) {
        long offset = parseNumberFlexible(offsetText, -1L);
        long value = parseNumberFlexible(valueText, -1L);
        if (offset < 0 || value < 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        byte[] data = new byte[8];
        for (int i = 0; i < 8; i++) {
            data[i] = (byte) ((value >> (i * 8)) & 0xff);
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startProgress("写入内存");
        return runFhPokeBytes(runDir, ctx, offset, data);
    }

    private boolean runFhMemcpy(File runDir, File loaderFile, String offsetText, String sizeText) {
        long offset = parseNumberFlexible(offsetText, -1L);
        long size = parseNumberFlexible(sizeText, -1L);
        if (offset < 0 || size <= 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        if (size > 1024 * 1024) {
            appendWorkLog(runDir, "memcpy 尺寸过大，建议分段操作");
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "读取内存");
        byte[] data = runFhPeekBytes(runDir, ctx, offset, size);
        if (data == null || data.length == 0) {
            return false;
        }
        long dest = offset + size;
        startProgress("写入内存");
        return runFhPokeBytes(runDir, ctx, dest, data);
    }

    private byte[] runFhPeekBytes(File runDir, FhContext ctx, long offset, long length) {
        if (runDir == null || ctx == null) {
            return null;
        }
        if (length <= 0) {
            return null;
        }
        if (length > 2 * 1024 * 1024L) {
            appendWorkLog(runDir, "peek 长度过大，建议使用 r/rs");
            return null;
        }
        String xml = "<?xml version=\"1.0\" ?>\n<data>\n"
                + "<peek address64=\"0x" + Long.toHexString(offset) + "\" "
                + "SizeInBytes=\"0x" + Long.toHexString(length) + "\" />\n"
                + "</data>\n";
        File xmlFile;
        try {
            xmlFile = writeTextFile(runDir, "fh_peek.xml", xml);
        } catch (IOException e) {
            appendWorkLog(runDir, "生成 XML 失败: " + e.getMessage());
            return null;
        }
        boolean ok = runFhSimpleXmlCommand(runDir, ctx, xmlFile, null, "读取内存");
        if (!ok) {
            return null;
        }
        File logFile = new File(runDir, "run.log");
        byte[] data = extractPeekBytesFromLog(logFile);
        if (data == null) {
            return null;
        }
        if (data.length > length) {
            return Arrays.copyOf(data, (int) length);
        }
        return data;
    }

    private boolean runFhPokeBytes(File runDir, FhContext ctx, long offset, File file) {
        long size = rootFileSize(file.getAbsolutePath());
        InputStream in = rootOpenStream(file.getAbsolutePath());
        if (in == null || size <= 0) {
            appendWorkLog(runDir, "读取文件失败: " + file.getAbsolutePath());
            return false;
        }
        try (InputStream rootIn = in) {
            return runFhPokeStream(runDir, ctx, offset, rootIn, size);
        } catch (IOException e) {
            appendWorkLog(runDir, "读取文件失败: " + e.getMessage());
            return false;
        }
    }

    private boolean runFhPokeBytes(File runDir, FhContext ctx, long offset, byte[] data) {
        if (data == null) {
            return false;
        }
        return runFhPokeStream(runDir, ctx, offset, new ByteArrayInputStream(data), data.length);
    }

    private boolean runFhPokeStream(File runDir, FhContext ctx, long offset,
                                    InputStream in, long totalSize) {
        if (runDir == null || ctx == null) {
            return false;
        }
        final int maxChunk = 8;
        final int maxPokesPerXml = 256;
        long written = 0;
        int xmlIndex = 0;
        byte[] buf = new byte[maxChunk];
        try {
            while (true) {
                StringBuilder sb = new StringBuilder();
                sb.append("<?xml version=\"1.0\" ?>\n<data>\n");
                int count = 0;
                while (count < maxPokesPerXml) {
                    int read = in.read(buf);
                    if (read <= 0) {
                        break;
                    }
                    // value64 是字节串的小端整数视图：buf[0] 为最低地址即最低有效字节，
                    // 与 firehose poke 语义一致（设备按小端把 value64 写回内存）
                    long value = 0;
                    for (int i = 0; i < read; i++) {
                        value |= (buf[i] & 0xffL) << (i * 8);
                    }
                    sb.append("  <poke address64=\"0x")
                            .append(Long.toHexString(offset + written))
                            .append("\" SizeInBytes=\"")
                            .append(read)
                            .append("\" value64=\"0x")
                            .append(Long.toHexString(value))
                            .append("\" />\n");
                    written += read;
                    count++;
                }
                if (count == 0) {
                    break;
                }
                sb.append("</data>\n");
                File xmlFile = writeTextFile(runDir, "fh_poke_" + xmlIndex + ".xml", sb.toString());
                boolean ok = runFhSimpleXmlCommand(runDir, ctx, xmlFile, null, "写入内存");
                if (!ok) {
                    return false;
                }
                xmlIndex++;
                if (totalSize > 0) {
                    int percent = (int) Math.min(100L, (written * 100L) / totalSize);
                    setProgressValue(percent, "写入内存 " + percent + "%");
                }
            }
        } catch (IOException e) {
            appendWorkLog(runDir, "写入失败: " + e.getMessage());
            return false;
        }
        return true;
    }

    private boolean runFhXmlCommand(File runDir, FhContext ctx, File xmlFile, File searchPath, String label) {
        if (runDir == null || ctx == null || xmlFile == null) {
            return false;
        }
        try {
            List<File> xmls = Collections.singletonList(xmlFile);
            CommandResult result = runQdlXmlCommand(runDir, ctx.loaderFile, xmls, searchPath, false);
            return isCommandSuccess(result);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private boolean runFhSimpleXmlCommand(File runDir, FhContext ctx, File xmlFile, File searchPath, String label) {
        if (runDir == null || ctx == null || xmlFile == null) {
            return false;
        }
        try {
            List<File> xmls = Collections.singletonList(xmlFile);
            CommandResult result = runQdlXmlCommand(runDir, ctx.loaderFile, xmls, searchPath, false);
            return isCommandSuccess(result);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private boolean runFhSendXmlFile(File runDir, File loaderFile, String xmlPath) {
        if (xmlPath == null || xmlPath.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        File xmlFile = new File(xmlPath);
        if (!rootExists(xmlPath, false)) {
            appendWorkLog(runDir, "XML 文件不存在: " + xmlPath);
            recordErrorReason("XML 文件不存在");
            appendStepResult("发送 XML", false);
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            appendStepResult("发送 XML", false);
            return false;
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "发送 XML");
        boolean ok = runFhSimpleXmlCommand(runDir, ctx, xmlFile, xmlFile.getParentFile(), "发送 XML");
        appendStepResult("发送 XML", ok);
        return ok;
    }

    private boolean runFhSendRawXml(File runDir, File loaderFile, String xmlContent) {
        return runFhSendRawXml(runDir, loaderFile, xmlContent, true);
    }

    private boolean runFhSendRawXml(File runDir, File loaderFile, String xmlContent, boolean strict) {
        return runFhRawXmlCommand(runDir, loaderFile, xmlContent, "发送 XML");
    }

    private boolean runFhRawXmlCommand(File runDir, File loaderFile, String xmlContent, String stepLabel) {
        String raw = xmlContent == null ? "" : xmlContent.trim();
        if (raw.isEmpty()) {
            raw = rawXmlInput.getText().toString().trim();
        }
        if (raw.isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        String lower = raw.toLowerCase(Locale.US);
        if (!lower.contains("<data")) {
            StringBuilder sb = new StringBuilder();
            if (!lower.startsWith("<?xml")) {
                sb.append("<?xml version=\"1.0\"?>\n");
            }
            sb.append("<data>\n").append(raw).append("\n</data>\n");
            raw = sb.toString();
        }
        File xmlFile;
        try {
            xmlFile = writeTextFile(runDir, "raw_cmd.xml", raw);
        } catch (IOException e) {
            appendWorkLog(runDir, "生成 XML 失败: " + e.getMessage());
            recordErrorReason("生成 XML 失败");
            appendStepResult(stepLabel, false, "生成 XML 失败");
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            appendStepResult(stepLabel, false);
            return false;
        }
        startLogProgressMonitor(new File(runDir, "run.log"), stepLabel);
        boolean ok = runFhSimpleXmlCommand(runDir, ctx, xmlFile, xmlFile.getParentFile(), stepLabel);
        appendStepResult(stepLabel, ok);
        return ok;
    }

    private boolean runFhSendCommand(File runDir, File loaderFile, String content) {
        String cmd = content == null ? "" : content.trim();
        if (cmd.isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        String xml;
        if (cmd.startsWith("<")) {
            if (cmd.contains("<data")) {
                xml = cmd;
            } else {
                xml = "<?xml version=\"1.0\" ?>\n<data>\n" + cmd + "\n</data>\n";
            }
        } else {
            xml = "<?xml version=\"1.0\" ?>\n<data>\n<" + cmd + " />\n</data>\n";
        }
        return runFhSendRawXml(runDir, loaderFile, xml, false);
    }

    private boolean runFhResetCommand(File runDir, File loaderFile, String modeText) {
        String mode = modeText == null ? "" : modeText.trim().toLowerCase(Locale.US);
        if (mode.isEmpty() && resetModeInput != null) {
            mode = resetModeInput.getText().toString().trim().toLowerCase(Locale.US);
        }
        String value;
        if ("edl".equals(mode) || "reset_to_edl".equals(mode)) {
            value = "reset_to_edl";
        } else if ("off".equals(mode) || "poweroff".equals(mode)) {
            value = "off";
        } else if ("fastboot".equals(mode) || "bootloader".equals(mode)) {
            value = "reset_to_fastboot";
        } else if ("recovery".equals(mode)) {
            value = "reset_to_recovery";
        } else {
            value = "reset";
        }
        String xml = "<?xml version=\"1.0\" ?>\n<data>\n"
                + "<power value=\"" + value + "\" DelayInSeconds=\"1\" />\n"
                + "</data>\n";
        boolean ok = runFhRawXmlCommand(runDir, loaderFile, xml, "重启");
        if (ok) {
            resetVipAuthState();
        }
        return ok;
    }

    private boolean runFhNopCommand(File runDir, File loaderFile) {
        String xml = "<?xml version=\"1.0\" ?>\n<data>\n"
                + "<nop value=\"ping\" verbose=\"0\" />\n"
                + "</data>\n";
        return runFhSendRawXml(runDir, loaderFile, xml, false);
    }

    // VIP 授权后的主动健康探针：复用会话发一个 nop。若会话被残留 rawmode 污染，nop 会撞
    // "read non multiple sector" 等失败，runFhNopCommand 内部已有 reset+全新会话救活逻辑；
    // 这里再确保会话被标记不健康，使下次读 GPT/刷写不复用脏会话(对齐 log 死循环根治方向)。
    private void probeVipSessionHealth(File runDir, File loaderFile) {
        if (runDir == null || loaderFile == null || !canReuseVipSession()) {
            return;
        }
        if (!runFhNopCommand(runDir, loaderFile)) {
            vipSessionHealthy = false;
            appendWorkLog(runDir, "VIP 会话健康探针未通过，下次操作将走全新会话");
        }
    }

    private boolean runFhGetStorageInfo(File runDir, File loaderFile) {
        String xml = "<?xml version=\"1.0\" ?>\n<data>\n"
                + "<getstorageinfo physical_partition_number=\"0\" />\n"
                + "</data>\n";
        boolean ok = runFhSendRawXml(runDir, loaderFile, xml, false);
        updateCachedStorageInfoFromLog(runDir);
        return ok;
    }

    private void refreshCachedStorageInfo(File runDir, FhContext ctx) {
        if (runDir == null || ctx == null) {
            return;
        }
        File xmlFile;
        try {
            xmlFile = writeTextFile(runDir, "storageinfo_probe.xml",
                    "<?xml version=\"1.0\" ?>\n<data>\n"
                            + "<getstorageinfo physical_partition_number=\"0\" />\n"
                            + "</data>\n");
        } catch (IOException e) {
            return;
        }
        try {
            runQdlXmlCommand(runDir, ctx.loaderFile, Collections.singletonList(xmlFile),
                    xmlFile.getParentFile(), false);
        } catch (IOException | InterruptedException ignored) {
        }
        updateCachedStorageInfoFromLog(runDir);
    }

    // best-effort 读 DDR 类型：OPlus loader 支持的 <getddrtype/> 命令，响应里带 ddr_type=1/2。
    // 仅供读分区表时显示用，吞掉任何错误（失败不影响读表/刷写），与 refreshCachedStorageInfo 同模板。
    private void runFhProbeDdrType(File runDir, FhContext ctx) {
        if (runDir == null || ctx == null) {
            return;
        }
        File xmlFile;
        try {
            xmlFile = writeTextFile(runDir, "ddrtype_probe.xml",
                    "<?xml version=\"1.0\" ?>\n<data>\n<getddrtype />\n</data>\n");
        } catch (IOException e) {
            return;
        }
        try {
            runQdlXmlCommand(runDir, ctx.loaderFile, Collections.singletonList(xmlFile),
                    xmlFile.getParentFile(), false);
        } catch (IOException | InterruptedException ignored) {
        }
        String label = parseDdrTypeFromLog(runDir);
        if (label != null) {
            appendSummaryLog("内存类型: " + label);
        }
    }

    private String parseDdrTypeFromLog(File runDir) {
        String tail = readLogTail(new File(runDir, "run.log"), 65536);
        if (tail == null || tail.isEmpty()) {
            return null;
        }
        // 设备回送可能 XML 转义引号；还原后匹配，取最后一次（getddrtype 之后新增的日志段）
        tail = tail.replace("&quot;", "\"").replace("&#34;", "\"");
        Matcher m = DDR_TYPE_PATTERN.matcher(tail);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        if ("1".equals(last)) {
            return "DDR4";
        }
        if ("2".equals(last)) {
            return "DDR5/LPDDR5";
        }
        return null;
    }

    private boolean runFhSetBootableStorageDrive(File runDir, File loaderFile, String lunText) {
        String value = lunText == null ? "" : lunText.trim();
        if (value.isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        String xml = "<?xml version=\"1.0\" ?>\n<data>\n"
                + "<setbootablestoragedrive value=\"" + value + "\" />\n"
                + "</data>\n";
        return runFhSendRawXml(runDir, loaderFile, xml, false);
    }

    // ---- A/B 活动槽：对齐 edl firehose.py cmd_setactiveslot / getactiveslot ----
    // GPT 表项 8 字节属性 flags 的第 AB_FLAG_OFFSET(=6) 字节带 SLOT_ACTIVE 位标记活动槽。
    // setactiveslot 不是发某条 firehose 命令，而是改各 LUN GPT 的 _a/_b 表项(flags+交换类型GUID)
    // 后重算 CRC 回写主/备份 GPT；setbootablestoragedrive 只是选启动 LUN，二者语义不同。
    private static final int AB_FLAG_OFFSET = 6;
    private static final long AB_PARTITION_ATTR_SLOT_ACTIVE = 0x1L << 2;

    private boolean runFhSetActiveSlot(File runDir, File loaderFile, String slotText) {
        String slot = slotText == null ? "" : slotText.trim().toLowerCase(Locale.US);
        if (!"a".equals(slot) && !"b".equals(slot)) {
            appendWorkLog(runDir, "无效槽位(应为 a 或 b): " + slotText);
            recordErrorReason("无效槽位");
            return finishStep("设置活动槽", false);
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, true);
        if (ctx == null) {
            return finishStep("设置活动槽 " + slot, false);
        }
        if (gptEntries.isEmpty()) {
            runFhGptList(runDir, loaderFile);
        }
        // A/B 配对可能跨 LUN(如 xbl_a@LUN1 / xbl_b@LUN2)——收集所有含 _a 或 _b 的 LUN，
        // 一次性载入各自主 GPT 后做全局配对，避免只收 _a 的 LUN 致跨 LUN 的 _b 漏切却误报成功。
        Set<Integer> luns = new LinkedHashSet<>();
        for (GptEntry e : gptEntries) {
            if (e != null && e.name != null && (e.name.endsWith("_a") || e.name.endsWith("_b"))) {
                luns.add(parseIntSafe(e.partition, 0));
            }
        }
        if (luns.isEmpty()) {
            appendWorkLog(runDir, "未找到 _a/_b 分区，设备可能非 A/B");
            recordErrorReason("非 A/B 设备");
            return finishStep("设置活动槽 " + slot, false);
        }
        return finishStep("设置活动槽 " + slot, applySlotAcrossLuns(runDir, ctx, luns, slot));
    }

    // 跨 LUN 切换活动槽：载入所有相关 LUN 的主 GPT，建全局 name→(lun,偏移)映射，对每个 _a/_b 配对
    // (可能分处不同 LUN)设置 SLOT_ACTIVE flags 并交换 16 字节类型 GUID，最后回写被改动 LUN 的主/备份
    // GPT。对齐 bkerler firehose.py cmd_setactiveslot 的跨 LUN 处理(xbl_a/xbl_b 常分处两 LUN)。
    private boolean applySlotAcrossLuns(File runDir, FhContext ctx, Set<Integer> luns, String slot) {
        int sectorSize = ctx.sectorSize > 0 ? ctx.sectorSize : resolveGptSectorSize();
        long probe = estimateGptMainProbeSectors(sectorSize);
        Map<Integer, byte[]> primaryByLun = new LinkedHashMap<>();
        Map<Integer, GptLayout> layoutByLun = new LinkedHashMap<>();
        Map<String, int[]> nameToLoc = new HashMap<>(); // name → {lun, offset}
        for (int lun : luns) {
            byte[] primary = readGptRegionBytes(runDir, ctx, lun, 0, probe, sectorSize, "PrimaryGPT");
            if (primary == null) {
                recordErrorReason("读取主 GPT 失败");
                return false;
            }
            GptLayout g = parseGptLayout(primary, sectorSize, primary.length);
            if (g == null) {
                recordErrorReason("解析主 GPT 失败");
                return false;
            }
            if (g.entriesOffset + g.entryCount * (long) g.entrySize > primary.length) {
                recordErrorReason("主 GPT 区不完整，拒绝写回");
                return false;
            }
            primaryByLun.put(lun, primary);
            layoutByLun.put(lun, g);
            for (Map.Entry<String, Integer> e : g.nameToOffset.entrySet()) {
                nameToLoc.put(e.getKey(), new int[]{lun, e.getValue()});
            }
        }
        boolean aActive = "a".equals(slot);
        boolean anyPair = false;
        Set<Integer> dirty = new LinkedHashSet<>();
        for (Map.Entry<String, int[]> e : new ArrayList<>(nameToLoc.entrySet())) {
            String name = e.getKey();
            if (name == null || !name.endsWith("_a")) {
                continue;
            }
            int[] bLoc = nameToLoc.get(name.substring(0, name.length() - 2) + "_b");
            if (bLoc == null) {
                continue; // 无配对的 _a(单分区)，不切
            }
            anyPair = true;
            int[] aLoc = e.getValue();
            byte[] aBuf = primaryByLun.get(aLoc[0]);
            byte[] bBuf = primaryByLun.get(bLoc[0]);
            int offA = aLoc[1];
            int offB = bLoc[1];
            // 按对幂等：明确处于目标态(a/b 标志相反且 a 已等于目标)则跳过此对，避免重复交换 GUID 把映射换回，
            // 正确处理"上次只切了部分对"的重试；处于模糊态(两侧标志都置/都清)则强制切到目标。
            boolean aIsActive = isSlotActive(aBuf, offA);
            boolean bIsActive = isSlotActive(bBuf, offB);
            if (aIsActive != bIsActive && aIsActive == aActive) {
                continue;
            }
            boolean isBoot = "boot_a".equals(name);
            writeUInt64LE(aBuf, offA + 48, applySlotFlags(readUInt64LE(aBuf, offA + 48), aActive, isBoot));
            writeUInt64LE(bBuf, offB + 48, applySlotFlags(readUInt64LE(bBuf, offB + 48), !aActive, isBoot));
            for (int i = 0; i < 16; i++) {
                byte t = aBuf[offA + i];
                aBuf[offA + i] = bBuf[offB + i];
                bBuf[offB + i] = t;
            }
            dirty.add(aLoc[0]);
            dirty.add(bLoc[0]);
        }
        if (!anyPair) {
            appendWorkLog(runDir, "未找到可切换的 _a/_b 配对，设备可能非 A/B");
            recordErrorReason("非 A/B 设备");
            return false;
        }
        if (dirty.isEmpty()) {
            appendWorkLog(runDir, "所有 A/B 配对已是活动槽 " + slot + "，无需切换");
        } else {
            for (int lun : dirty) {
                if (!writeBackSlotGpt(runDir, ctx, lun, slot, primaryByLun.get(lun),
                        layoutByLun.get(lun), sectorSize)) {
                    return false;
                }
            }
        }
        // 切槽后须告诉 PBL 从活动槽 xbl 所在物理 LUN 引导：UFS 的 xbl_a/xbl_b 常分处不同 LUN，仅置 GPT
        // SLOT_ACTIVE 位不足以让 PBL 切到另一 LUN(GPT 显示已切但设备仍从旧 LUN 引导→不开机)。用设备【实际
        // GPT】里 xbl 的真实 LUN(不硬编码 a→1/b→2)，仅当 xbl_a/xbl_b 分处不同 LUN 时才发——同 LUN 设备
        // 跳过(GPT flags 已足够，避免把启动盘误设到不存在的 LUN)。对齐 md.7z slot.bat 切槽收尾。best-effort。
        int[] xblA = nameToLoc.get("xbl_a");
        int[] xblB = nameToLoc.get("xbl_b");
        if (xblA != null && xblB != null && xblA[0] != xblB[0]) {
            int bootLun = aActive ? xblA[0] : xblB[0];
            appendWorkLog(runDir, "切槽后设置可启动存储驱动: LUN" + bootLun + " (活动槽 " + slot + "，xbl 跨 LUN)");
            runFhSetBootableStorageDrive(runDir, ctx.loaderFile, String.valueOf(bootLun));
        }
        return true;
    }

    // 回写单个 LUN 已编辑的主 GPT，并把本 LUN 的 _a/_b 表项(flags+类型GUID)按名同步到备份 GPT。
    // 跨 LUN 交换后的 GUID 已落在主表，故备份按名复制即自动带上，无需在备份里再做跨 LUN 配对。
    private boolean writeBackSlotGpt(File runDir, FhContext ctx, int lun, String slot,
                                     byte[] primary, GptLayout g, int sectorSize) {
        recomputeGptCrc(primary, g.headerOffset, g.entriesOffset, g.entryCount, g.entrySize);
        long entriesBytes = g.entryCount * (long) g.entrySize;
        long entrySectors = (entriesBytes + sectorSize - 1) / sectorSize;
        // 仅回写 GPT 区(PMBR+头+表项)，不回写探测窗口里多读到的分区数据扇区。
        int gptRegionBytes = (int) Math.min((long) primary.length,
                (g.entryLba + entrySectors) * (long) sectorSize);
        byte[] primaryRegion = gptRegionBytes == primary.length
                ? primary : Arrays.copyOf(primary, gptRegionBytes);
        File pf = new File(runDir, "gptpatch_primary_lun" + lun + ".bin");
        boolean pok = writeBytes(pf, primaryRegion)
                && runFhWriteProgram(runDir, ctx, lun, 0, gptRegionBytes / sectorSize, sectorSize,
                        pf, "PrimaryGPT");
        pf.delete();
        if (!pok) {
            recordErrorReason("写回主 GPT 失败");
            return false;
        }
        // 备份 GPT(位于盘尾)：表项在 backupLba 之前，备份头在 backupLba
        if (g.backupLba > 0) {
            long backupEntriesLba = g.backupLba - entrySectors;
            if (backupEntriesLba > 0) {
                long bkSectors = entrySectors + 1;
                int bkHeaderOff = (int) (entrySectors * (long) sectorSize);
                byte[] backup = readGptRegionBytes(runDir, ctx, lun, backupEntriesLba, bkSectors,
                        sectorSize, "BackupGPT");
                if (backup != null && backup.length >= bkHeaderOff + 92
                        && "EFI PART".equals(new String(backup, bkHeaderOff, 8, StandardCharsets.US_ASCII))) {
                    Map<String, Integer> bkMap = mapEntryNames(backup, 0, g.entryCount, g.entrySize);
                    if (copyEditedAbEntries(primary, g.nameToOffset, backup, bkMap)) {
                        // 重建备份头自指/互指 LBA：current_lba=备份头位置、alternate_lba=1(主头 LBA1)
                        writeUInt64LE(backup, bkHeaderOff + 24, g.backupLba);
                        writeUInt64LE(backup, bkHeaderOff + 32, 1L);
                        recomputeGptCrc(backup, bkHeaderOff, 0, g.entryCount, g.entrySize);
                        File bf = new File(runDir, "gptpatch_backup_lun" + lun + ".bin");
                        boolean bok = writeBytes(bf, backup)
                                && runFhWriteProgram(runDir, ctx, lun, backupEntriesLba, bkSectors,
                                        sectorSize, bf, "BackupGPT");
                        bf.delete();
                        if (!bok) {
                            appendWorkLog(runDir, "LUN" + lun + " 备份 GPT 回写失败(主 GPT 已更新；xbl 加载时通常据主表重建备份)");
                        }
                    }
                } else {
                    appendWorkLog(runDir, "LUN" + lun + " 备份 GPT 不可用，仅更新主 GPT");
                }
            }
        }
        appendWorkLog(runDir, "LUN" + lun + " 活动槽已切到 " + slot);
        return true;
    }

    // 把主 GPT 里 _a/_b 表项的类型 GUID(0..15)与属性 flags(48..55)按名复制到备份 GPT 对应表项。
    private boolean copyEditedAbEntries(byte[] primary, Map<String, Integer> pMap,
                                        byte[] backup, Map<String, Integer> bMap) {
        boolean changed = false;
        for (Map.Entry<String, Integer> e : pMap.entrySet()) {
            String name = e.getKey();
            if (name == null || !(name.endsWith("_a") || name.endsWith("_b"))) {
                continue;
            }
            Integer bOff = bMap.get(name);
            if (bOff == null) {
                continue;
            }
            int pOff = e.getValue();
            if (pOff + 56 > primary.length || bOff + 56 > backup.length) {
                continue;
            }
            System.arraycopy(primary, pOff, backup, bOff, 16);        // 类型 GUID
            System.arraycopy(primary, pOff + 48, backup, bOff + 48, 8); // 属性 flags
            changed = true;
        }
        return changed;
    }

    // active=该槽将成为活动槽；isBoot 时按参考用整字节 0x6f(活动)/0x3a(非活动)
    private long applySlotFlags(long flags, boolean active, boolean isBoot) {
        long shift = (long) AB_FLAG_OFFSET * 8;
        if (active) {
            return isBoot ? (0x6fL << shift) : (flags | (AB_PARTITION_ATTR_SLOT_ACTIVE << shift));
        }
        return isBoot ? (0x3aL << shift) : (flags & ~(AB_PARTITION_ATTR_SLOT_ACTIVE << shift));
    }

    private boolean runFhGetActiveSlot(File runDir, File loaderFile) {
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return finishStep("查询活动槽", false);
        }
        String slot = resolveCurrentActiveSlot(runDir, ctx);
        if (slot == null) {
            appendWorkLog(runDir, "无法判定活动槽，设备可能非 A/B 或 GPT 读取失败");
            recordErrorReason("无法判定活动槽");
            return finishStep("查询活动槽", false);
        }
        appendWorkLog(runDir, "当前活动槽: " + slot);
        showToast("当前活动槽: " + slot);
        return finishStep("查询活动槽", true);
    }

    // 解析设备当前活动槽：定位 boot_a 所在 LUN，优先按备份 GPT 判定（对齐 bkerler：备份头在 xbl
    // 加载时更新，是 A/B 状态的权威来源，主备不一致时更可靠），备份不可用回退主 GPT。无法判定返回
    // null。getactiveslot 查询与 setactiveslot 幂等守卫共用此逻辑，避免重复读 GPT。
    private String resolveCurrentActiveSlot(File runDir, FhContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (gptEntries.isEmpty()) {
            runFhGptList(runDir, ctx.loaderFile);
        }
        GptEntry bootA = null;
        for (GptEntry e : gptEntries) {
            if (e != null && "boot_a".equals(e.name)) {
                bootA = e;
                break;
            }
        }
        if (bootA == null) {
            return null;
        }
        int lun = parseIntSafe(bootA.partition, 0);
        int sectorSize = parseIntSafe(bootA.sectorSize, ctx.sectorSize);
        long probe = estimateGptMainProbeSectors(sectorSize);
        byte[] region = readGptRegionBytes(runDir, ctx, lun, 0, probe, sectorSize, "PrimaryGPT");
        if (region == null) {
            return null;
        }
        GptLayout g = parseGptLayout(region, sectorSize, region.length);
        if (g == null) {
            return null;
        }
        String slot = resolveActiveSlotFromBackup(runDir, ctx, lun, sectorSize, g);
        if (slot == null) {
            slot = resolveActiveSlot(region, g.nameToOffset);
        }
        return slot;
    }

    // 按区内 boot_a/boot_b 的 SLOT_ACTIVE 位判定活动槽，无法判定返回 null。
    private String resolveActiveSlot(byte[] region, Map<String, Integer> nameToOffset) {
        if (isSlotActive(region, nameToOffset.get("boot_a"))) {
            return "a";
        }
        if (isSlotActive(region, nameToOffset.get("boot_b"))) {
            return "b";
        }
        return null;
    }

    // 读备份 GPT 表项并按其 boot_a/boot_b 属性判定活动槽；备份不可用返回 null（由调用方回退主 GPT）。
    private String resolveActiveSlotFromBackup(File runDir, FhContext ctx, int lun, int sectorSize,
                                               GptLayout g) {
        if (g == null || g.backupLba <= 0) {
            return null;
        }
        long entriesBytes = g.entryCount * (long) g.entrySize;
        long entrySectors = (entriesBytes + sectorSize - 1) / sectorSize;
        long backupEntriesLba = g.backupLba - entrySectors;
        if (backupEntriesLba <= 0) {
            return null;
        }
        byte[] backup = readGptRegionBytes(runDir, ctx, lun, backupEntriesLba, entrySectors + 1,
                sectorSize, "BackupGPT");
        int bkHeaderOff = (int) (entrySectors * (long) sectorSize);
        if (backup == null || backup.length < bkHeaderOff + 92
                || !"EFI PART".equals(new String(backup, bkHeaderOff, 8, StandardCharsets.US_ASCII))) {
            return null;
        }
        Map<String, Integer> bkMap = mapEntryNames(backup, 0, g.entryCount, g.entrySize);
        return resolveActiveSlot(backup, bkMap);
    }

    private boolean isSlotActive(byte[] region, Integer off) {
        if (off == null || off + 56 > region.length) {
            return false;
        }
        long flags = readUInt64LE(region, off + 48);
        return (((flags >>> (AB_FLAG_OFFSET * 8)) & 0xFF) & AB_PARTITION_ATTR_SLOT_ACTIVE)
                == AB_PARTITION_ATTR_SLOT_ACTIVE;
    }

    // 读 [startSector,startSector+sectors) 的 GPT 区到内存（qdl 以 root 落盘，故用 root 直读）
    private byte[] readGptRegionBytes(File runDir, FhContext ctx, int lun, long startSector,
                                      long sectors, int sectorSize, String label) {
        File f = new File(runDir, "gptregion_lun" + lun + "_" + startSector + ".bin");
        if (f.exists()) {
            f.delete();
        }
        if (!runFhReadProgram(runDir, ctx, lun, startSector, sectors, sectorSize, f, label)) {
            return null;
        }
        byte[] data = rootReadBytes(f.getAbsolutePath());
        f.delete();
        return data;
    }

    // 解析 GPT：headerStart 处探测 EFI PART(优先 preferred 扇区大小，回退 512/4096)
    private GptLayout parseGptLayout(byte[] region, int preferred, int limit) {
        int[] candidates = preferred == 512 ? new int[]{512, 4096} : new int[]{preferred, 512, 4096};
        for (int ss : candidates) {
            if (region.length < ss + 92 || ss + 8 > limit) {
                continue;
            }
            if (!"EFI PART".equals(new String(region, ss, 8, StandardCharsets.US_ASCII))) {
                continue;
            }
            long entryLba = readUInt64LE(region, ss + 72);
            long entryCount = readUInt32LE(region, ss + 80);
            int entrySize = (int) readUInt32LE(region, ss + 84);
            if (entryLba <= 0 || entryCount <= 0 || entrySize <= 0) {
                continue;
            }
            GptLayout g = new GptLayout();
            g.sectorSize = ss;
            g.headerOffset = ss;
            g.backupLba = readUInt64LE(region, ss + 32);
            g.entryLba = entryLba;
            g.entryCount = entryCount;
            g.entrySize = entrySize;
            g.entriesOffset = (int) (entryLba * (long) ss);
            g.nameToOffset = mapEntryNames(region, g.entriesOffset, entryCount, entrySize);
            if (g.nameToOffset.isEmpty()) {
                continue;
            }
            return g;
        }
        return null;
    }

    private Map<String, Integer> mapEntryNames(byte[] region, int entriesOffset, long entryCount,
                                               int entrySize) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < entryCount; i++) {
            int off = entriesOffset + i * entrySize;
            if (off < 0 || off + entrySize > region.length) {
                break;
            }
            if (isEmptyGuid(region, off)) {
                continue;
            }
            String name = readGptName(region, off + 56, 72);
            if (!name.isEmpty()) {
                map.put(name, off);
            }
        }
        return map;
    }

    // 重算分区表 CRC(头+88) 与头 CRC(头+16)，否则改过表项的 GPT 会因 CRC 不符被拒
    private void recomputeGptCrc(byte[] region, int headerOffset, int entriesOffset,
                                 long entryCount, int entrySize) {
        int headerSize = (int) readUInt32LE(region, headerOffset + 12);
        if (headerSize <= 0 || headerOffset + headerSize > region.length) {
            headerSize = 92;
        }
        long entriesBytes = entryCount * (long) entrySize;
        if (entriesOffset < 0 || entriesOffset + entriesBytes > region.length) {
            return;
        }
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(region, entriesOffset, (int) entriesBytes);
        writeUInt32LE(region, headerOffset + 88, crc.getValue());
        writeUInt32LE(region, headerOffset + 16, 0L);
        crc.reset();
        crc.update(region, headerOffset, headerSize);
        writeUInt32LE(region, headerOffset + 16, crc.getValue());
    }

    private static void writeUInt32LE(byte[] data, int off, long value) {
        data[off] = (byte) (value & 0xff);
        data[off + 1] = (byte) ((value >>> 8) & 0xff);
        data[off + 2] = (byte) ((value >>> 16) & 0xff);
        data[off + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private static void writeUInt64LE(byte[] data, int off, long value) {
        for (int i = 0; i < 8; i++) {
            data[off + i] = (byte) ((value >>> (i * 8)) & 0xff);
        }
    }

    private boolean writeBytes(File f, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static class GptLayout {
        int sectorSize;
        int headerOffset;
        long entryLba;
        long entryCount;
        int entrySize;
        long backupLba;
        int entriesOffset;
        Map<String, Integer> nameToOffset;
    }

    private boolean runFhErasePartition(File runDir, File loaderFile, String partName) {
        if (partName == null || partName.trim().isEmpty()) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return false;
        }
        String lunText = lunInput.getText().toString().trim();
        GptEntry entry = findGptEntry(partName, lunText);
        if (entry == null && gptEntries.isEmpty()) {
            runFhGptList(runDir, loaderFile);
            entry = findGptEntry(partName, lunText);
        }
        if (entry == null) {
            appendWorkLog(runDir, "未找到分区信息: '" + partName + "' (lun="
                    + (lunText.isEmpty() ? "未指定" : lunText) + ", 已读分区数=" + gptEntries.size()
                    + ")。该分区名不在设备 GPT 中——A/B 设备的引导分区只有 _a/_b 形态(如 boot_a)，"
                    + "请从分区列表点选具体分区，或先读取分区表");
            recordErrorReason("未找到分区 '" + partName + "'");
            return finishStep("擦除分区 " + partName, false);
        }
        int lun = parseIntSafe(entry.partition, 0);
        long start = parseLongSafe(entry.startSector, -1L);
        long num = parseLongSafe(entry.numSectors, -1L);
        int sectorSize = parseIntSafe(entry.sectorSize, ctx.sectorSize);
        if (start < 0 || num <= 0) {
            appendWorkLog(runDir, "分区信息无效: " + partName);
            recordErrorReason("分区信息无效");
            return finishStep("擦除分区 " + partName, false);
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "擦除 " + entry.name);
        boolean ok = shouldUseVipSliceRouting(ctx, sectorSize)
                ? runVipEraseSlices(runDir, ctx, lun, start, num, sectorSize, entry.name)
                : runFhEraseRangeSingle(runDir, ctx, lun, start, num, sectorSize,
                entry.name, null);
        return finishStep("擦除分区 " + entry.name, ok);
    }

    private boolean runFhEraseSectors(File runDir, File loaderFile, String startText, String sectorsText) {
        long start = parseLongSafe(startText, -1L);
        long num = parseLongSafe(sectorsText, -1L);
        if (start < 0 || num <= 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return finishStep("擦除扇区", false);
        }
        int lun = parseIntSafe(lunInput.getText().toString().trim(), 0);
        int sectorSize = resolveFhSectorSize();
        startLogProgressMonitor(new File(runDir, "run.log"), "擦除 扇区");
        boolean ok = shouldUseVipSliceRouting(ctx, sectorSize)
                ? runVipEraseSlices(runDir, ctx, lun, start, num, sectorSize, "erase")
                : runFhEraseRangeSingle(runDir, ctx, lun, start, num, sectorSize,
                "erase", null);
        return finishStep("擦除扇区", ok);
    }

    private boolean runFhErasePartitionSectors(File runDir, File loaderFile, String partName, String sectorsText) {
        long num = parseLongSafe(sectorsText, -1L);
        if (partName == null || partName.trim().isEmpty() || num <= 0) {
            showToast(getString(R.string.toast_missing_required));
            return false;
        }
        FhContext ctx = buildFhContext(runDir, loaderFile, false);
        if (ctx == null) {
            return finishStep("擦除分区 " + partName, false);
        }
        String lunText = lunInput.getText().toString().trim();
        GptEntry entry = findGptEntry(partName, lunText);
        if (entry == null && gptEntries.isEmpty()) {
            runFhGptList(runDir, loaderFile);
            entry = findGptEntry(partName, lunText);
        }
        if (entry == null) {
            appendWorkLog(runDir, "未找到分区信息: '" + partName + "' (lun="
                    + (lunText.isEmpty() ? "未指定" : lunText) + ", 已读分区数=" + gptEntries.size()
                    + ")。该分区名不在设备 GPT 中——A/B 设备的引导分区只有 _a/_b 形态(如 boot_a)，"
                    + "请从分区列表点选具体分区，或先读取分区表");
            recordErrorReason("未找到分区 '" + partName + "'");
            return finishStep("擦除分区 " + partName, false);
        }
        int lun = parseIntSafe(entry.partition, 0);
        long start = parseLongSafe(entry.startSector, -1L);
        long total = parseLongSafe(entry.numSectors, -1L);
        int sectorSize = parseIntSafe(entry.sectorSize, ctx.sectorSize);
        if (start < 0 || total <= 0) {
            appendWorkLog(runDir, "分区信息无效: " + partName);
            recordErrorReason("分区信息无效");
            return finishStep("擦除分区 " + partName, false);
        }
        if (num > total) {
            num = total;
        }
        startLogProgressMonitor(new File(runDir, "run.log"), "擦除 " + entry.name);
        boolean ok = shouldUseVipSliceRouting(ctx, sectorSize)
                ? runVipEraseSlices(runDir, ctx, lun, start, num, sectorSize, entry.name)
                : runFhEraseRangeSingle(runDir, ctx, lun, start, num, sectorSize,
                entry.name, null);
        return finishStep("擦除分区 " + entry.name, ok);
    }

    private boolean runFhEraseRangeSingle(File runDir, FhContext ctx, int lun,
                                          long startSector, long numSectors,
                                          int sectorSize, String label,
                                          String filename) {
        File xmlFile = writeFhEraseXml(runDir, label, filename, lun, startSector,
                numSectors, sectorSize);
        if (xmlFile == null) {
            return false;
        }
        return runFhSimpleXmlCommand(runDir, ctx, xmlFile, null, "擦除 " + label);
    }

    private File writeQdlReadXml(File runDir, File outputFile,
                                 int lun, long startSector, long numSectors, int sectorSize,
                                 String label, boolean requireLabel) {
        if (runDir == null || outputFile == null) {
            return null;
        }
        String safeLabel = label == null ? "" : label.trim();
        if (safeLabel.isEmpty() || "read".equalsIgnoreCase(safeLabel)) {
            String inferred = inferReadLabel(lun, startSector, numSectors, outputFile);
            if (inferred != null && !inferred.trim().isEmpty()) {
                safeLabel = inferred.trim();
            } else if (requireLabel) {
                // OPlus programmer 强制 read 带 label，无法推断只能放弃
                appendWorkLog(runDir, "无法确定分区标签，请先读取 GPT 或使用分区名");
                recordErrorReason("无法确定分区标签");
                return null;
            } else {
                // stock 设备按起始扇区读，read 无需 label(对齐 bkerler edl cmd_read / qdl read.c)，
                // 留空 → 下方生成不含 label 属性的 read XML，使裸扇区/跨分区/间隙读不再误失败
                safeLabel = "";
            }
        }
        safeLabel = sanitizeFileName(safeLabel);
        String labelAttr = safeLabel.isEmpty() ? "" : ("label=\"" + safeLabel + "\" ");
        String xml = "<?xml version=\"1.0\" ?>\n"
                + "<data>\n"
                + "  <read SECTOR_SIZE_IN_BYTES=\"" + sectorSize + "\" "
                + "filename=\"" + outputFile.getAbsolutePath() + "\" "
                + labelAttr
                + "physical_partition_number=\"" + lun + "\" "
                + "start_sector=\"" + startSector + "\" "
                + "num_partition_sectors=\"" + numSectors + "\" />\n"
                + "</data>\n";
        try {
            return writeTextFile(runDir, "qdl_read_" + outputFile.getName() + ".xml", xml);
        } catch (IOException e) {
            appendWorkLog(runDir, "生成读取 XML 失败: " + e.getMessage());
            recordErrorReason("生成读取 XML 失败");
            return null;
        }
    }

    private String inferReadLabel(int lun, long startSector, long numSectors, File outputFile) {
        if (outputFile != null) {
            String outputName = outputFile.getName().toLowerCase(Locale.US);
            if (outputName.startsWith("gpt")
                    || outputName.contains("gpt_hdr")
                    || outputName.contains("gpt_ent")
                    || outputName.contains("gpt_main")) {
                return "PrimaryGPT";
            }
        }
        GptEntry entry = findGptEntryByRange(lun, startSector, numSectors);
        if (entry != null && entry.name != null && !entry.name.trim().isEmpty()) {
            return entry.name.trim();
        }
        return null;
    }

    private String stripImageExtension(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.endsWith(".img") || lower.endsWith(".bin")) {
            int dot = trimmed.lastIndexOf('.');
            if (dot > 0) {
                return trimmed.substring(0, dot);
            }
        }
        return trimmed;
    }

    private String inferWriteLabel(int lun, long startSector, long numSectors, File imageFile) {
        GptEntry entry = findGptEntryByRange(lun, startSector, numSectors);
        if (entry != null && entry.name != null && !entry.name.trim().isEmpty()) {
            return entry.name.trim();
        }
        if (imageFile != null) {
            String base = stripImageExtension(imageFile.getName());
            if (!base.isEmpty()) {
                GptEntry match = findGptEntry(base, String.valueOf(lun));
                if (match != null && match.name != null && !match.name.trim().isEmpty()) {
                    return match.name.trim();
                }
            }
        }
        return null;
    }

    private String inferEraseLabel(int lun, long startSector, long numSectors) {
        GptEntry entry = findGptEntryByRange(lun, startSector, numSectors);
        if (entry != null && entry.name != null && !entry.name.trim().isEmpty()) {
            return entry.name.trim();
        }
        return null;
    }

    private GptEntry findGptEntryByRange(int lun, long startSector, long numSectors) {
        if (gptEntries.isEmpty()) {
            return null;
        }
        long endSector = startSector + Math.max(0L, numSectors - 1L);
        for (GptEntry entry : gptEntries) {
            if (entry == null) {
                continue;
            }
            int entryLun = parseIntSafe(entry.partition, -1);
            if (entryLun != lun) {
                continue;
            }
            long entryStart = parseLongSafe(entry.startSector, -1L);
            long entryNum = parseLongSafe(entry.numSectors, -1L);
            if (entryStart < 0 || entryNum <= 0) {
                continue;
            }
            long entryEnd = entryStart + entryNum - 1L;
            if (startSector >= entryStart && endSector <= entryEnd) {
                return entry;
            }
        }
        return null;
    }

    private File writeQdlProgramXml(File runDir, File imageFile, String label,
                                    int lun, long startSector, long numSectors, int sectorSize) {
        if (runDir == null || imageFile == null) {
            return null;
        }
        String safeLabel = label == null ? "" : label.trim();
        if (safeLabel.isEmpty() || "write".equalsIgnoreCase(safeLabel)) {
            String inferred = inferWriteLabel(lun, startSector, numSectors, imageFile);
            if (inferred == null || inferred.trim().isEmpty()) {
                appendWorkLog(runDir, "无法确定分区标签，请先读取 GPT 或使用分区名");
                recordErrorReason("无法确定分区标签");
                return null;
            }
            safeLabel = inferred.trim();
        }
        String xml = "<?xml version=\"1.0\" ?>\n"
                + "<data>\n"
                + "  <program SECTOR_SIZE_IN_BYTES=\"" + sectorSize + "\" "
                + "file_sector_offset=\"0\" "
                + "filename=\"" + imageFile.getName() + "\" "
                + "label=\"" + safeLabel + "\" "
                + "physical_partition_number=\"" + lun + "\" "
                + "start_sector=\"" + startSector + "\" "
                + "num_partition_sectors=\"" + numSectors + "\" "
                + "sparse=\"false\" />\n"
                + "</data>\n";
        try {
            return writeTextFile(runDir, "qdl_program_" + sanitizeFileName(safeLabel) + ".xml", xml);
        } catch (IOException e) {
            appendWorkLog(runDir, "生成刷写 XML 失败: " + e.getMessage());
            recordErrorReason("生成刷写 XML 失败");
            return null;
        }
    }

    private File writeFhEraseXml(File runDir, String label, String filename, int lun,
                                 long startSector, long numSectors, int sectorSize) {
        if (runDir == null) {
            return null;
        }
        String safeLabel = label == null ? "" : label.trim();
        if (safeLabel.isEmpty() || "erase".equalsIgnoreCase(safeLabel)) {
            String inferred = inferEraseLabel(lun, startSector, numSectors);
            if (inferred == null || inferred.trim().isEmpty()) {
                appendWorkLog(runDir, "无法确定分区标签，请先读取 GPT 或使用分区名");
                recordErrorReason("无法确定分区标签");
                return null;
            }
            safeLabel = inferred.trim();
        }
        String safeFile = filename == null || filename.trim().isEmpty()
                ? ""
                : sanitizeFileName(new File(filename).getName());
        String xml = "<?xml version=\"1.0\" ?>\n"
                + "<data>\n"
                + "  <erase SECTOR_SIZE_IN_BYTES=\"" + sectorSize + "\" "
                + "label=\"" + safeLabel + "\" "
                + (safeFile.isEmpty() ? "" : "filename=\"" + safeFile + "\" ")
                + "physical_partition_number=\"" + lun + "\" "
                + "start_sector=\"" + startSector + "\" "
                + "num_partition_sectors=\"" + numSectors + "\" />\n"
                + "</data>\n";
        try {
            return writeTextFile(runDir, "fh_erase_" + sanitizeFileName(safeLabel) + ".xml", xml);
        } catch (IOException e) {
            appendWorkLog(runDir, "生成擦除 XML 失败: " + e.getMessage());
            recordErrorReason("生成擦除 XML 失败");
            return null;
        }
    }

    private void runGptList() {
        clearLog();
        setActiveVipFiles(null, null);
        startProgress("读取分区表");
        if (!rootAvailable) {
            requestRoot();
            if (!rootAvailable) {
                showToast(getString(R.string.error_permission));
                finishProgress(false);
                return;
            }
        }

        cleanupWorkDir();
        File runDir = new File(getFilesDir(), "work/gpt_" + System.currentTimeMillis());
        if (!runDir.mkdirs()) {
            finishProgress(false);
            return;
        }
        ensureDir(new File(runDir, "logs"));

        File loaderFile = null;
        File digestFile = null;
        File authLoaderFile = null;
        boolean useVipMode = isVipMode();
        if (builtinVendorDir != null) {
            if (builtinDevprgAssetPath == null) {
                showToast(getString(R.string.builtin_loader_missing));
                finishProgress(false);
                return;
            }
            try {
                loaderFile = copyBuiltinLoader(runDir, builtinDevprgAssetPath);
                if (useVipMode && builtinDigestAssetPath != null) {
                    digestFile = copyBuiltinLoader(runDir, builtinDigestAssetPath);
                }
                if (useVipMode && builtinSignAssetPath != null) {
                    authLoaderFile = copyBuiltinLoader(runDir, builtinSignAssetPath);
                }
            } catch (IOException e) {
                finishProgress(false);
                return;
            }
        } else if (loaderUri != null) {
            loaderFile = resolveUriToFile(loaderUri, getString(R.string.loader_devprg_title));
            if (loaderFile == null) {
                finishProgress(false);
                return;
            }
        }
        if (builtinVendorDir == null) {
            if (useVipMode && digestUri != null) {
                digestFile = resolveUriToFile(digestUri, getString(R.string.loader_digest_title));
                if (digestFile == null) {
                    finishProgress(false);
                    return;
                }
            }
            if (useVipMode && signUri != null) {
                authLoaderFile = resolveUriToFile(signUri, getString(R.string.loader_sig_title));
                if (authLoaderFile == null) {
                    finishProgress(false);
                    return;
                }
            }
        }
        if (loaderFile == null) {
            showToast(getString(R.string.loader_devprg_none));
            finishProgress(false);
            return;
        }
        setActiveVipFiles(useVipMode ? digestFile : null, useVipMode ? authLoaderFile : null);

        startLogProgressMonitor(new File(runDir, "run.log"), "读取中");
        boolean ok = runFhGptList(runDir, loaderFile);
        updateCachedStorageInfoFromLog(runDir);
        finishProgress(ok);
    }

    private void runVipAuthOnly() {
        clearLog();
        startProgress("授权");
        if (hasAuthFilesMismatch() || !hasAuthFilesConfigured()) {
            showToast(getString(R.string.error_missing_digest_sign));
            finishProgress(false);
            return;
        }
        if (!rootAvailable) {
            requestRoot();
            if (!rootAvailable) {
                showToast(getString(R.string.error_permission));
                finishProgress(false);
                return;
            }
        }

        cleanupWorkDir();
        File runDir = new File(getFilesDir(), "work/vip_" + System.currentTimeMillis());
        if (!runDir.mkdirs()) {
            finishProgress(false);
            return;
        }
        ensureDir(new File(runDir, "logs"));

        File loaderFile = null;
        File digestFile = null;
        File authLoaderFile = null;
        boolean useVipMode = isVipMode();
        if (builtinVendorDir != null) {
            if (builtinDevprgAssetPath == null) {
                showToast(getString(R.string.builtin_loader_missing));
                finishProgress(false);
                return;
            }
            try {
                loaderFile = copyBuiltinLoader(runDir, builtinDevprgAssetPath);
                if (useVipMode && builtinDigestAssetPath != null) {
                    digestFile = copyBuiltinLoader(runDir, builtinDigestAssetPath);
                }
                if (useVipMode && builtinSignAssetPath != null) {
                    authLoaderFile = copyBuiltinLoader(runDir, builtinSignAssetPath);
                }
            } catch (IOException e) {
                finishProgress(false);
                return;
            }
        } else if (loaderUri != null) {
            loaderFile = resolveUriToFile(loaderUri, getString(R.string.loader_devprg_title));
            if (loaderFile == null) {
                finishProgress(false);
                return;
            }
        }
        if (builtinVendorDir == null) {
            if (useVipMode && digestUri != null) {
                digestFile = resolveUriToFile(digestUri, getString(R.string.loader_digest_title));
                if (digestFile == null) {
                    finishProgress(false);
                    return;
                }
            }
            if (useVipMode && signUri != null) {
                authLoaderFile = resolveUriToFile(signUri, getString(R.string.loader_sig_title));
                if (authLoaderFile == null) {
                    finishProgress(false);
                    return;
                }
            }
        }
        if (loaderFile == null) {
            showToast(getString(R.string.loader_devprg_none));
            finishProgress(false);
            return;
        }
        if (digestFile == null || authLoaderFile == null) {
            String reason = getString(R.string.error_missing_digest_sign);
            recordErrorReason(reason);
            appendStepResult("授权", false, reason);
            showToast(reason);
            finishProgress(false);
            return;
        }

        logFileInfo(runDir, "Digest", digestFile);
        logFileInfo(runDir, "Sig", authLoaderFile);
        vipAuthorized = false;
        try {
            boolean ok = runQdlVipAuth(runDir, loaderFile, digestFile, authLoaderFile);
            if (ok) {
                // 授权成功即设备在连，连接态与授权态原子一致（见 syncVipAuthState 守卫说明）。
                lastEdlConnected = true;
                vipAuthorized = true;
                vipSessionHealthy = true;
                persistVipAuthState();
                showToast(getString(R.string.vip_auth_done));
                finishProgress(true);
            } else {
                if (!firehoseStepLogged && !configureStepLogged) {
                    finishStep("授权", false);
                }
                finishProgress(false);
            }
        } catch (IOException | InterruptedException e) {
            recordErrorReason("授权失败");
            if (!firehoseStepLogged && !configureStepLogged) {
                finishStep("授权", false);
            }
            finishProgress(false);
        }
    }

    private boolean runQdlVipAuth(File runDir, File loaderFile, File digestFile, File signFile)
            throws IOException, InterruptedException {
        throwIfCommandCanceled();
        File qdlTool = new File(getRootEdlBinDir(), TOOL_QDL);
        if (!qdlTool.exists()) {
            String reason = "缺少 qdl";
            appendWorkLog(runDir, reason);
            recordErrorReason(reason);
            return false;
        }
        if (loaderFile == null || digestFile == null || signFile == null) {
            String reason = getString(R.string.error_missing_digest_sign);
            appendWorkLog(runDir, reason);
            recordErrorReason(reason);
            return false;
        }
        if (canReuseVipSession()) {
            appendWorkLog(runDir, "当前端口已完成 VIP 授权，跳过重复授权");
            if (!firehoseStepLogged) {
                appendStepResult("发送 Firehose", true);
                firehoseStepLogged = true;
            }
            if (!configureStepLogged) {
                appendStepResult("配置设备", true);
                configureStepLogged = true;
            }
            appendStepResult("发送 Digest", true);
            appendStepResult("签名", true);
            return true;
        }

        resolveQdlPortArg();
        String usbPath = resolveQdlUsbPath(null);

        String memory = resolveFhMemoryName();
        if (memory == null || memory.trim().isEmpty()) {
            memory = "ufs";
        }
        String vipEnv = buildVipEnvPrefix(runDir, digestFile, signFile, loaderFile, memory,
                skipStorageInitCheck.isChecked(),
                maxPayloadInput.getText().toString().trim());
        if (vipEnv == null || vipEnv.trim().isEmpty()) {
            String reason = getString(R.string.error_missing_digest_sign);
            appendWorkLog(runDir, reason);
            recordErrorReason(reason);
            return false;
        }

        List<String> args = new ArrayList<>();
        args.add("--debug");
        args.add("--out-chunk-size=" + QDL_OUT_CHUNK_DEFAULT);
        args.add("--storage=" + memory.trim());
        String serial = resolveQdlPortArg();
        if (serial != null && !serial.isEmpty() && !"auto".equalsIgnoreCase(serial)) {
            args.add("--serial=" + serial);
        }
        args.add("--signeddigests=" + digestFile.getAbsolutePath());
        args.add("--signeddigests=" + signFile.getAbsolutePath());
        args.add(loaderFile.getAbsolutePath());

        String resetEnv = buildQdlAutoResetEnvPrefix(false);
        String portEnv = buildQdlUsbEnvPrefix(usbPath);
        String vipPartEnv = buildVipPartitionEnvPrefix(runDir, loaderFile);
        String cmdLine = resetEnv + portEnv + vipEnv + vipPartEnv
                + shQuote(qdlTool.getAbsolutePath()) + " " + joinArgs(args);
        setProgressValue(30, "qdl 授权");
        prepareQdlCommandState(false, true);
        CommandResult result;
        try {
            // 120s 业务超时只在抓到设备(CONNECTED)后才起算；无设备时停在 WAITING 持续等待，
            // 不再误杀报"授权超时"。
            result = runQdlCommandWithRoot(runDir, cmdLine, QDL_VIP_AUTH_COMMAND_TIMEOUT_MS);
        } catch (InterruptedException e) {
            // 真超时才区分归因：已抓到设备=握手超时；否则=一直没等到设备。
            // 非超时中断（如 executor 关停）仍归为授权失败。
            String reason;
            if (isCommandTimedOut(e)) {
                reason = qdlPhase.get() == QdlPhase.CONNECTED ? "授权超时" : "设备未检测到";
            } else {
                reason = "授权失败";
            }
            appendWorkLog(runDir, reason + ": " + e.getMessage());
            recordErrorReason(reason);
            return false;
        } finally {
            clearQdlCommandState();
        }
        boolean vipDone = hasVipAuthDone(result.output);
        boolean ok = result.exitCode == 0 && vipDone && !vipAuthHasFatalError(result.output);
        String reason = ok ? null : summarizeQdlFailure(result.output);
        if (!ok && (reason == null || reason.trim().isEmpty())) {
            reason = "未进入 Firehose";
        }
        if (!ok) {
            if (reason != null && !reason.trim().isEmpty()) {
                recordErrorReason(reason);
            }
            return false;
        }
        if (!firehoseStepLogged) {
            appendStepResult("发送 Firehose", true);
            firehoseStepLogged = true;
        }
        if (!configureStepLogged) {
            appendStepResult("配置设备", true);
            configureStepLogged = true;
        }
        appendStepResult("发送 Digest", true);
        appendStepResult("签名", true);
        // 握手成功即设备在连，连接态与授权态原子一致（见 syncVipAuthState 守卫说明）。
        lastEdlConnected = true;
        vipAuthorized = true;
        vipSessionHealthy = true;
        persistVipAuthState();
        // 授权成功后主动健康探针：复用会话发一个 nop，若残留 rawmode 等污染则提前发现并标记
        // 会话不健康，使后续读 GPT/刷写走全新会话，而非把脏会话留到撞死循环才事后 reset。
        probeVipSessionHealth(runDir, loaderFile);
        return true;
    }

    private boolean hasVipAuthDone(String output) {
        if (output == null || output.trim().isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.US);
        return lower.contains("vip auth done") || lower.contains("vip auth success");
    }

    // VIP 授权的明确致命信号——drain_response(oplus_vip.c) 仅扫 ACK/NAK，设备"报 ERROR 后仍 ACK"
    // 的情形会漏检；这里在 Java 侧补判。仅匹配确定致命且不会出现在成功授权日志里的措辞，
    // 不碰 DevprgRSAVerify verify signature failed / Mode= Invalid 等 OPlus 设备良性固有日志，
    // 避免误杀正常授权。
    private boolean vipAuthHasFatalError(String output) {
        if (output == null || output.trim().isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.US);
        return lower.contains("signed digest rejected")
                || lower.contains("signed digest table rejected")
                || lower.contains("is unmatch")
                || lower.contains("hash mismatch")
                || lower.contains("packet_hash")
                || lower.contains("not authenticated")
                || lower.contains("authentication failed");
    }

    private boolean isCommandTimedOut(InterruptedException e) {
        return e != null
                && e.getMessage() != null
                && e.getMessage().toLowerCase(Locale.US).contains("timed out");
    }

    private void copyFileTo(File src, File dest) throws IOException {
        if (src == null || dest == null) {
            throw new IOException(getString(R.string.error_open_file));
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            copyStream(in, out);
        }
    }

    private void ensureDir(File dir) {
        if (dir == null) {
            return;
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private boolean buildGptOutputs(File runDir, List<GptEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        List<GptEntry> entriesSnapshot = new ArrayList<>(entries);
        updatePartitionPicker(entriesSnapshot);

        File listFile = new File(runDir, "partitions.txt");
        File readFile = new File(runDir, "readback.xml");
        File programFile = new File(runDir, "rawprogram0.xml");
        try (BufferedWriter listOut = new BufferedWriter(new FileWriter(listFile, false));
             BufferedWriter readOut = new BufferedWriter(new FileWriter(readFile, false));
             BufferedWriter programOut = new BufferedWriter(new FileWriter(programFile, false))) {
            listOut.write("name,partition,start_sector,num_sectors\n");
            readOut.write("<?xml version=\"1.0\"?>\n<data>\n");
            programOut.write("<?xml version=\"1.0\"?>\n<data>\n");
            for (GptEntry entry : entries) {
                listOut.write(entry.name + "," + entry.partition + "," +
                        entry.startSector + "," + entry.numSectors + "\n");
                // 跨 LUN 同名分区(如 last_parti 在 LUN1/2/3/5)用 lunN_ 前缀区分 filename，避免导出
                // 镜像互相覆盖；label/physical_partition_number 仍为真实值，与逐 LUN 读出的文件名一致。
                int entryLun = parseIntSafe(entry.partition, 0);
                String outName = (entryLun == 0 ? entry.name : "lun" + entryLun + "_" + entry.name) + ".img";
                readOut.write("  <read SECTOR_SIZE_IN_BYTES=\"" + entry.sectorSize +
                        "\" filename=\"" + outName + "\" " +
                        "label=\"" + entry.name + "\" " +
                        "physical_partition_number=\"" + entry.partition + "\" " +
                        "start_sector=\"" + entry.startSector + "\" " +
                        "num_partition_sectors=\"" + entry.numSectors + "\" />\n");
                programOut.write("  <program SECTOR_SIZE_IN_BYTES=\"" + entry.sectorSize +
                        "\" filename=\"" + outName + "\" " +
                        "label=\"" + entry.name + "\" " +
                        "physical_partition_number=\"" + entry.partition + "\" " +
                        "start_sector=\"" + entry.startSector + "\" " +
                        "num_partition_sectors=\"" + entry.numSectors + "\" />\n");
            }
            readOut.write("</data>\n");
            programOut.write("</data>\n");
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private GptHeader parseGptHeaderFile(File headerFile) {
        byte[] data = readFileBytes(headerFile);
        if (data == null || data.length < 92) {
            return null;
        }
        String signature = new String(data, 0, 8, StandardCharsets.US_ASCII);
        if (!"EFI PART".equals(signature)) {
            return null;
        }
        long backupLba = readUInt64LE(data, 32);
        long lastUsableLba = readUInt64LE(data, 48);
        long entryLba = readUInt64LE(data, 72);
        long entryCount = readUInt32LE(data, 80);
        int entrySize = (int) readUInt32LE(data, 84);
        if (entryLba <= 0 || entryCount <= 0 || entrySize <= 0) {
            return null;
        }
        return new GptHeader(entryLba, entryCount, entrySize, backupLba, lastUsableLba);
    }

    private List<GptEntry> parseGptMainFile(File gptFile, int defaultSectorSize, int lun) {
        List<GptEntry> entries = new ArrayList<>();
        // gpt_main*.bin 在下载目录(/storage)，默认走 root 直读
        byte[] data = gptFile == null ? null : rootReadBytes(gptFile.getAbsolutePath());
        if (data == null) {
            return entries;
        }
        int sectorSize = defaultSectorSize;
        int headerOffset = sectorSize;
        if (data.length < headerOffset + 92) {
            return entries;
        }
        String signature = new String(data, headerOffset, 8, StandardCharsets.US_ASCII);
        if (!"EFI PART".equals(signature)) {
            headerOffset = 512;
            if (data.length < headerOffset + 92) {
                return entries;
            }
            signature = new String(data, headerOffset, 8, StandardCharsets.US_ASCII);
            if (!"EFI PART".equals(signature)) {
                return entries;
            }
            sectorSize = 512;
        }
        long entryLba = readUInt64LE(data, headerOffset + 72);
        long entryCount = readUInt32LE(data, headerOffset + 80);
        int entrySize = (int) readUInt32LE(data, headerOffset + 84);
        if (entryLba <= 0 || entryCount <= 0 || entrySize <= 0) {
            return entries;
        }
        long entryOffset = entryLba * (long) sectorSize;
        long entriesBytes = entryCount * (long) entrySize;
        // 仅在 dump 完整覆盖表项区时才校验 CRC——单会话粗读未覆盖完整表项区属正常(由
        // isGptMainDumpComplete 回退精确重读)，不应误报为 CRC 损坏；失败仅警告不拒绝。
        boolean entriesComplete = entryOffset >= 0 && entriesBytes > 0
                && entryOffset <= data.length && entriesBytes <= data.length - entryOffset;
        if (entriesComplete
                && !verifyGptCrc(data, headerOffset, sectorSize, entryLba, entryCount, entrySize)) {
            cb.onLog("警告: LUN" + lun + " GPT CRC 校验未通过，分区表可能损坏");
        }
        // 表项数上界防护：不超过缓冲区可容纳的数量，防越界/超大 entryCount
        long maxEntries = entrySize > 0 ? (data.length - entryOffset) / (long) entrySize : 0;
        if (maxEntries > 0 && entryCount > maxEntries) {
            entryCount = maxEntries;
        }
        for (int i = 0; i < entryCount; i++) {
            long offset = entryOffset + (long) i * entrySize;
            if (offset + entrySize > data.length) {
                break;
            }
            int off = (int) offset;
            if (isEmptyGuid(data, off)) {
                continue;
            }
            long startLba = readUInt64LE(data, off + 32);
            long endLba = readUInt64LE(data, off + 40);
            if (endLba < startLba) {
                continue;
            }
            String name = readGptName(data, off + 56, 72);
            if (name.isEmpty()) {
                continue;
            }
            long numSectors = endLba - startLba + 1;
            entries.add(new GptEntry(name, Integer.toString(lun),
                    Long.toString(startLba), Long.toString(numSectors),
                    Integer.toString(sectorSize)));
        }
        return entries;
    }

    // 标准 GPT CRC32 校验：头 CRC(offset 16，算时该 4 字节置 0) + 表项区 CRC(offset 88)。
    private boolean verifyGptCrc(byte[] data, int headerOffset, int sectorSize,
                                 long entryLba, long entryCount, int entrySize) {
        int headerSize = (int) readUInt32LE(data, headerOffset + 12);
        if (headerSize < 92 || (long) headerOffset + headerSize > data.length) {
            return false;
        }
        long storedHeaderCrc = readUInt32LE(data, headerOffset + 16);
        byte[] hdr = new byte[headerSize];
        System.arraycopy(data, headerOffset, hdr, 0, headerSize);
        hdr[16] = 0;
        hdr[17] = 0;
        hdr[18] = 0;
        hdr[19] = 0;
        java.util.zip.CRC32 hc = new java.util.zip.CRC32();
        hc.update(hdr, 0, headerSize);
        if (hc.getValue() != storedHeaderCrc) {
            return false;
        }
        long entryOffset = entryLba * (long) sectorSize;
        long entriesBytes = entryCount * (long) entrySize;
        if (entryOffset < 0 || entriesBytes <= 0 || entryOffset > data.length
                || entriesBytes > data.length - entryOffset || entriesBytes > Integer.MAX_VALUE) {
            return false;
        }
        long storedEntriesCrc = readUInt32LE(data, headerOffset + 88);
        java.util.zip.CRC32 ec = new java.util.zip.CRC32();
        ec.update(data, (int) entryOffset, (int) entriesBytes);
        return ec.getValue() == storedEntriesCrc;
    }

    private List<GptEntry> parseGptEntryFile(File entryFile, GptHeader header, int sectorSize, int lun) {
        List<GptEntry> entries = new ArrayList<>();
        byte[] data = readFileBytes(entryFile);
        if (data == null || header == null) {
            return entries;
        }
        for (int i = 0; i < header.entryCount; i++) {
            long offset = (long) i * header.entrySize;
            if (offset + header.entrySize > data.length) {
                break;
            }
            int off = (int) offset;
            if (isEmptyGuid(data, off)) {
                continue;
            }
            long startLba = readUInt64LE(data, off + 32);
            long endLba = readUInt64LE(data, off + 40);
            if (endLba < startLba) {
                continue;
            }
            String name = readGptName(data, off + 56, 72);
            if (name.isEmpty()) {
                continue;
            }
            long numSectors = endLba - startLba + 1;
            entries.add(new GptEntry(name, Integer.toString(lun),
                    Long.toString(startLba), Long.toString(numSectors),
                    Integer.toString(sectorSize)));
        }
        return entries;
    }

    private long estimateGptMainSectors(int sectorSize) {
        // 与单会话探测同一语义(读 PrimaryGPT 标签注册的保留区)，统一到一处避免两份副本再次漂移超读。
        return estimateGptMainProbeSectors(sectorSize);
    }

    private int resolveGptSectorSize() {
        String input = sectorSizeInput.getText().toString().trim();
        if (!input.isEmpty()) {
            try {
                int value = Integer.parseInt(input);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String memory = memoryInput.getText().toString().trim().toLowerCase(Locale.US);
        if (memory.isEmpty()) {
            memory = "ufs";
        }
        int cached = getCachedBlockSize();
        if (cached > 0) {
            if (("emmc".equals(memory) && cached == 512)
                    || (!"emmc".equals(memory) && cached == 4096)) {
                return cached;
            }
        }
        if ("emmc".equals(memory)) {
            return 512;
        }
        return 4096;
    }

    private List<Integer> buildGptSectorCandidates(int preferred) {
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        boolean userSpecified = false;
        if (sectorSizeInput != null) {
            String input = sectorSizeInput.getText().toString().trim();
            userSpecified = !input.isEmpty();
        }
        boolean preferUfs4096 = !userSpecified && "ufs".equals(resolveFhMemoryName());
        if (preferUfs4096) {
            candidates.add(4096);
            if (preferred > 0) {
                candidates.add(preferred);
            }
            candidates.add(512);
        } else {
            if (preferred > 0) {
                candidates.add(preferred);
            }
            candidates.add(4096);
            candidates.add(512);
        }
        return new ArrayList<>(candidates);
    }

    private String resolveFhMemoryName() {
        String memory = memoryInput.getText().toString().trim();
        if (memory.isEmpty()) {
            memory = "ufs";
        }
        return memory.toLowerCase(Locale.US);
    }

    private int resolveFhSectorSize() {
        String input = sectorSizeInput.getText().toString().trim();
        if (!input.isEmpty()) {
            try {
                int value = Integer.parseInt(input);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String memory = resolveFhMemoryName();
        int cached = getCachedBlockSize();
        if (cached > 0) {
            if (("emmc".equals(memory) && cached == 512)
                    || (!"emmc".equals(memory) && cached == 4096)) {
                return cached;
            }
        }
        if ("emmc".equals(memory)) {
            return 512;
        }
        return 4096;
    }

    private String resolveQdlMemoryName() {
        String memory = memoryInput == null ? "" : memoryInput.getText().toString().trim();
        if (memory.isEmpty()) {
            memory = "ufs";
        }
        return memory.toLowerCase(Locale.US);
    }

    private long parseLongSafe(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int parseIntSafe(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseNumberFlexible(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim().toLowerCase(Locale.US);
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            if (text.startsWith("0x")) {
                return Long.parseLong(text.substring(2), 16);
            }
            if (text.matches(".*[a-f].*")) {
                return Long.parseLong(text, 16);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private byte[] parseHexBytes(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        text = text.replace("0x", "");
        text = text.replace("0X", "");
        text = text.replaceAll("[^0-9a-fA-F]", "");
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() % 2 != 0) {
            text = "0" + text;
        }
        int len = text.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int index = i * 2;
            out[i] = (byte) Integer.parseInt(text.substring(index, index + 2), 16);
        }
        return out;
    }

    private List<Integer> resolveGptLuns() {
        List<Integer> luns = new ArrayList<>();
        // 读整张分区表 / 提取全盘是设备级操作，必须枚举所有 LUN（对齐 bkerler/edl、linux-msm/qdl
        // 的 printgpt 默认扫全 LUN）。这里【不能】信 lunInput——它承载的是"当前选中分区/默认 LUN"
        // 状态，RN 解析 GPT 后会自动选中 entries[0]（多为 ssd@LUN0）使 lunInput 落为 "0"，旧逻辑据此
        // 短路返回 [0]，把 6-LUN 设备(如一加9R)的整表扫描坍缩为只读 LUN0，xbl/modem(LUN4/5)全丢。
        // 定向单分区/单扇区读写擦另行直接读取 lunInput，不经此函数，故移除短路无回归。
        String memory = memoryInput.getText().toString().trim().toLowerCase(Locale.US);
        if ("emmc".equals(memory)) {
            luns.add(0);
            return luns;
        }
        if (cachedLunEnableMask > 0L) {
            for (int i = 0; i < Long.SIZE; i++) {
                if (((cachedLunEnableMask >> i) & 1L) != 0L) {
                    luns.add(i);
                }
            }
            if (!luns.isEmpty()) {
                return luns;
            }
        }
        // UFS 规范最多 8 个 LUN(0..7)；无 storageinfo 缓存时按此上限枚举，并由读循环
        // "读失败即停"提前终止。原值 12 会对根本不存在的 LUN 空跑整轮 Sahara+握手。
        int maxLun = cachedNumPhysical > 0 ? (cachedNumPhysical - 1) : 7;
        for (int i = 0; i <= Math.max(0, maxLun); i++) {
            luns.add(i);
        }
        return luns;
    }

    private int parseLunFromGptFileName(String name) {
        if (name == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("gpt_main(\\d+)\\.bin").matcher(name);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private String readGptName(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || offset + length > data.length) {
            return "";
        }
        byte[] nameBytes = Arrays.copyOfRange(data, offset, offset + length);
        String name = new String(nameBytes, StandardCharsets.UTF_16LE);
        int zero = name.indexOf('\0');
        if (zero >= 0) {
            name = name.substring(0, zero);
        }
        // 只截断 NUL 终止符，不 trim 空白：qdl gpt.c/bkerler gpt.py 均保留原名，trim 会改写
        // 含空白的真实分区名导致按名查找(读/写/擦)失配。
        return name;
    }

    private boolean isEmptyGuid(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 16 > data.length) {
            return true;
        }
        for (int i = 0; i < 16; i++) {
            if (data[offset + i] != 0) {
                return false;
            }
        }
        return true;
    }

    private long readUInt64LE(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 8 > data.length) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getLong();
    }

    private long readUInt32LE(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 4 > data.length) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getInt() & 0xffffffffL;
    }

    private byte[] readFileBytes(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = in.read(data);
            if (read != data.length) {
                return null;
            }
            return data;
        } catch (IOException ignored) {
            return null;
        }
    }

    private boolean isGptMetaEntry(String name) {
        if (name == null) {
            return true;
        }
        String lower = name.toLowerCase(Locale.US);
        return "primarygpt".equals(lower) || "backupgpt".equals(lower)
                || lower.startsWith("gpt_main") || lower.startsWith("gpt_backup");
    }

    private void updatePartitionPicker(List<GptEntry> entries) {
        partitionOptions.clear();
        gptEntries.clear();
        if (entries == null) {
            selectedPartitionIndex = -1;
            cb.onPartitions(new ArrayList<>(partitionOptions));
            return;
        }
        gptEntries.addAll(entries);
        for (GptEntry entry : entries) {
            if (entry == null || entry.name == null || entry.name.isEmpty()) {
                continue;
            }
            partitionOptions.add(new PartitionOption(entry.name, entry.partition,
                    entry.startSector, entry.numSectors, entry.sectorSize));
        }
        Collections.sort(partitionOptions, Comparator.comparing(o -> o.label.toLowerCase(Locale.US)));
        // 默认选中首项，对齐原 Spinner getSelectedItemPosition() 的默认行为
        selectedPartitionIndex = partitionOptions.isEmpty() ? -1 : 0;
        cb.onPartitions(new ArrayList<>(partitionOptions));
    }

    private GptEntry parseProgramLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = RAWPROGRAM_ATTR_PATTERN.matcher(line);
        String label = null;
        String filename = null;
        String partition = null;
        String start = null;
        String num = null;
        String sectorSize = null;
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(3);
            if ("label".equals(key)) {
                label = value;
            } else if ("filename".equals(key)) {
                filename = value;
            } else if ("physical_partition_number".equals(key)) {
                partition = value;
            } else if ("start_sector".equals(key)) {
                start = value;
            } else if ("num_partition_sectors".equals(key)) {
                num = value;
            } else if ("SECTOR_SIZE_IN_BYTES".equals(key)) {
                sectorSize = value;
            }
        }
        String name = (label != null && !label.isEmpty()) ? label : filename;
        if (name == null || name.isEmpty() || start == null || num == null) {
            return null;
        }
        if (name.endsWith(".bin") || name.endsWith(".img")) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
        }
        if (partition == null || partition.isEmpty()) {
            partition = "0";
        }
        if (sectorSize == null || sectorSize.isEmpty()) {
            sectorSize = "4096";
        }
        return new GptEntry(name, partition, start, num, sectorSize);
    }

    private GptEntry findGptEntry(String name, String lun) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String target = name.trim();
        String targetLun = lun == null ? "" : lun.trim();
        GptEntry candidate = null;
        boolean ambiguous = false;
        for (GptEntry entry : gptEntries) {
            if (entry == null || !target.equalsIgnoreCase(entry.name)) {
                continue;
            }
            // 显式 LUN 精确命中直接返回(批量读取/UI 选择均已带 LUN)
            if (!targetLun.isEmpty() && targetLun.equals(entry.partition)) {
                return entry;
            }
            if (candidate == null) {
                candidate = entry;
            } else if (!candidate.partition.equals(entry.partition)) {
                ambiguous = true;
            }
        }
        // 同名仅在单一 LUN 时沿用宽容回退；跨多 LUN(如 last_parti 在 LUN1/2/3/5)且未精确命中则返回 null，
        // 避免静默读/写/擦到错误 LUN(对齐 qdl 对重复分区名的报错)。
        return ambiguous ? null : candidate;
    }

    private static class GptEntry {
        final String name;
        final String partition;
        final String startSector;
        final String numSectors;
        final String sectorSize;

        GptEntry(String name, String partition, String startSector, String numSectors, String sectorSize) {
            this.name = name;
            this.partition = partition;
            this.startSector = startSector;
            this.numSectors = numSectors;
            this.sectorSize = sectorSize;
        }
    }

    private static class GptHeader {
        final long entryLba;
        final long entryCount;
        final int entrySize;
        final long backupLba;
        final long lastUsableLba;

        GptHeader(long entryLba, long entryCount, int entrySize, long backupLba, long lastUsableLba) {
            this.entryLba = entryLba;
            this.entryCount = entryCount;
            this.entrySize = entrySize;
            this.backupLba = backupLba;
            this.lastUsableLba = lastUsableLba;
        }
    }

    // 分区列表：整行点击=单选高亮(记入 selectedPartitionIndex)，行内多选框=批量读取集合

    private static class QfilInputs {
        final String rawprogramPath;
        final String patchPath;
        final String imageDirPath;
        final List<File> rawprogramFiles;
        final List<File> patchFiles;
        final File imageDir;
        final File baseDir;

        QfilInputs(String rawprogramPath,
                   String patchPath,
                   String imageDirPath,
                   List<File> rawprogramFiles,
                   List<File> patchFiles,
                   File imageDir,
                   File baseDir) {
            this.rawprogramPath = rawprogramPath;
            this.patchPath = patchPath;
            this.imageDirPath = imageDirPath;
            this.rawprogramFiles = rawprogramFiles;
            this.patchFiles = patchFiles;
            this.imageDir = imageDir;
            this.baseDir = baseDir;
        }
    }

    private static class ProgramEntry {
        final Map<String, String> attrs;
        final String label;
        final String filename;

        ProgramEntry(Map<String, String> attrs, String label, String filename) {
            this.attrs = attrs;
            this.label = label;
            this.filename = filename;
        }
    }

    private static class EdlPackageInfo {
        final File baseDir;
        final File imagesDir;
        final List<File> rawprogramFiles;
        final List<File> patchFiles;

        EdlPackageInfo(File baseDir, File imagesDir, List<File> rawprogramFiles, List<File> patchFiles) {
            this.baseDir = baseDir;
            this.imagesDir = imagesDir;
            this.rawprogramFiles = rawprogramFiles != null ? rawprogramFiles : new ArrayList<>();
            this.patchFiles = patchFiles != null ? patchFiles : new ArrayList<>();
        }
    }

    private static class SuperDefConfig {
        final String metaSize;
        final List<SuperBlockDevice> blockDevices;
        final List<SuperGroup> groups;
        final List<SuperPartition> partitions;
        // 以下均来自 super_def.json 显式字段，缺省 null/false 时沿用 lpmake 默认(不引入启发式)
        String blockSize;   // super_meta.block_size，非默认几何包需透传给 lpmake --block-size
        String alignment;   // super_meta.alignment，非默认对齐需透传给 lpmake --alignment
        String superName;   // 元数据所在块设备名(lpmake --super-name)，默认 "super"
        boolean virtualAb;  // super_meta.virtual_ab=true 时透传 lpmake --virtual-ab

        SuperDefConfig(String metaSize,
                       List<SuperBlockDevice> blockDevices,
                       List<SuperGroup> groups,
                       List<SuperPartition> partitions) {
            this.metaSize = metaSize;
            this.blockDevices = blockDevices;
            this.groups = groups;
            this.partitions = partitions;
        }
    }

    private static class SuperBlockDevice {
        final String name;
        final String size;
        final String blockSize;
        final String alignment;

        SuperBlockDevice(String name, String size, String blockSize, String alignment) {
            this.name = name;
            this.size = size;
            this.blockSize = blockSize;
            this.alignment = alignment;
        }
    }

    private static class SuperGroup {
        final String name;
        final String maxSize;

        SuperGroup(String name, String maxSize) {
            this.name = name;
            this.maxSize = maxSize;
        }
    }

    private static class SuperPartition {
        final String name;
        final String path;
        final String size;
        final String groupName;
        File rawFile;

        SuperPartition(String name, String path, String size, String groupName) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.groupName = groupName;
        }
    }

    private File zipGptOutputs(File runDir) throws IOException {
        String zipName = "gpt_export.zip";
        File zipFile = new File(runDir, zipName);
        String[] names = new String[]{"partitions.txt", "readback.xml", "rawprogram0.xml"};
        boolean hasAny = false;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (String name : names) {
                File entryFile = new File(runDir, name);
                if (!entryFile.exists()) {
                    continue;
                }
                hasAny = true;
                ZipEntry entry = new ZipEntry(name);
                zos.putNextEntry(entry);
                try (InputStream in = new FileInputStream(entryFile)) {
                    copyStream(in, zos);
                }
                zos.closeEntry();
            }
        }
        if (!hasAny) {
            if (zipFile.exists()) {
                zipFile.delete();
            }
            return null;
        }
        return zipFile;
    }

    private void copyGptOutputsToDownload(File runDir, File zipFile) {
        if (runDir == null) {
            return;
        }
        copyToDownloadDir(new File(runDir, "partitions.txt"), "partitions.txt");
        copyToDownloadDir(new File(runDir, "readback.xml"), "readback.xml");
        copyToDownloadDir(new File(runDir, "rawprogram0.xml"), "rawprogram0.xml");
        if (zipFile != null && zipFile.exists()) {
            copyToDownloadDir(zipFile, zipFile.getName());
        }
    }

    private boolean copyToDownloadDir(File src, String nameOverride) {
        if (src == null || !src.exists()) {
            return false;
        }
        String name = nameOverride == null ? "" : nameOverride.trim();
        if (name.isEmpty()) {
            name = src.getName();
        }
        ensureDownloadDirExists();
        File dest = new File(DEFAULT_DOWNLOAD_DIR, name);
        String cmd = "cp -f " + shQuote(src.getAbsolutePath())
                + " " + shQuote(dest.getAbsolutePath());
        try {
            CommandResult result = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            return result.exitCode == 0;
        } catch (IOException | InterruptedException ignored) {
            return false;
        }
    }

    private void runSignTool() {
        clearLog();
        startProgress("准备签名");
        if (!rootAvailable) {
            requestRoot();
            if (!rootAvailable) {
                showToast(getString(R.string.error_permission));
                finishProgress(false);
                return;
            }
        }
        if (signInputDirUri == null) {
            showToast(getString(R.string.sign_dir_empty));
            finishProgress(false);
            return;
        }
        cleanupWorkDir();
        File runDir = new File(getFilesDir(), "work/sign_" + System.currentTimeMillis());
        if (!runDir.mkdirs()) {
            finishProgress(false);
            return;
        }
        try {
            int copied = copyImagesFromTree(signInputDirUri, runDir);
            if (copied == 0) {
                showToast(getString(R.string.toast_missing_required));
                finishProgress(false);
                return;
            }
        } catch (IOException e) {
            appendLog(getString(R.string.error_open_file));
            finishProgress(false);
            return;
        }

        File keyFile = null;
        if (signKeyUri != null) {
            try {
                keyFile = copyUriToDir(signKeyUri, runDir, "avb_key.pem");
            } catch (IOException e) {
                appendLog(getString(R.string.error_open_file));
                finishProgress(false);
                return;
            }
        }

        List<String> args = new ArrayList<>();
        String partitions = signPartitionsInput.getText().toString().trim();
        if (!partitions.isEmpty()) {
            args.add("--partitions");
            args.addAll(Arrays.asList(partitions.split("\\s+")));
        }
        if (signRegenSaltCheck.isChecked()) {
            args.add("--regenerate-salt");
        }
        if (signVerifyCheck.isChecked()) {
            args.add("--verify-only");
        }
        if (signChainCheck.isChecked()) {
            args.add("--chained-mode");
        }
        if (!signVerifyCheck.isChecked()) {
            if (!hasPartitionImage(runDir)) {
                showToast(getString(R.string.sign_need_partition));
                finishProgress(false);
                return;
            }
            if (!signChainCheck.isChecked() && !new File(runDir, "vbmeta.img").exists()) {
                showToast(getString(R.string.sign_need_vbmeta));
                finishProgress(false);
                return;
            }
        }
        if (keyFile != null) {
            args.add("--private-key");
            args.add(keyFile.getAbsolutePath());
        }

        String toolPath = getRootEdlBinDir() + "/rebuild_avb";
        String cmdLine = shQuote(toolPath) + " " + joinArgs(args);
        try {
            startLogProgressMonitor(new File(runDir, "run.log"), "签名中");
            CommandResult result = runCommandWithRoot(runDir, cmdLine, false, getRootEdlBinDir());
            if (result.exitCode == 0) {
                finishProgress(true);
                appendStepResult("签名", true);
                if (signOutputDirUri != null) {
                    exportImagesToTree(runDir, signOutputDirUri);
                }
            } else {
                appendStepResult("签名", false);
                finishProgress(false);
            }
        } catch (IOException | InterruptedException e) {
            appendStepResult("签名", false);
            finishProgress(false);
        }
    }

    private String buildConfigureXml(String memory, boolean skipStorageInit) {
        String safeMemory = memory == null || memory.trim().isEmpty() ? "ufs" : memory.trim();
        String skipValue = skipStorageInit ? "1" : "0";
        return "<?xml version=\"1.0\" ?>\n"
                + "<data>\n"
                // 不带 MaxDigestTableSizeInBytes：部分高通/OPlus VIP loader 不支持 host digest table，
                // 收到会回 "Host wants to send a Hash table 8192 larger than supported 0" NAK 致整个
                // configure 失败(md.7z qctool.bat:427 正是捕获此 NAK 后删该属性重试)。qdl 内部
                // firehose_send_configure 本就不发它，OPlus 参考 conf.xml 也不带它——故直接对齐不发。
                + "<configure MemoryName=\"" + safeMemory + "\" Verbose=\"0\" AlwaysValidate=\"0\" "
                + "MaxPayloadSizeToTargetInBytes=\"1048576\" "
                + "ZlpAwareHost=\"1\" SkipStorageInit=\"" + skipValue + "\" />\n"
                + "</data>\n";
    }

    private String buildVipEnvPrefix(
            File runDir,
            File digestFile,
            File signFile,
            File loaderFile,
            String memory,
            boolean skipStorageInit,
            String maxPayload
    ) throws IOException {
        if (digestFile == null || signFile == null) {
            return null;
        }
        String payloadValue = maxPayload;
        if (payloadValue == null || payloadValue.isEmpty()) {
            payloadValue = "0x1000";
        }
        File transferXml = writeTextFile(runDir, "transfercfg.xml", XML_TRANSFERCFG);
        File verifyXml = writeTextFile(runDir, "verify.xml", XML_VERIFY);
        File shaXml = writeTextFile(runDir, "sha256init.xml", XML_SHA256INIT);
        File cfgXml = writeTextFile(runDir, "cfg.xml", buildConfigureXml(memory, skipStorageInit));
        StringBuilder env = new StringBuilder();
        env.append("EDL_VIP_DIGEST=").append(shQuote(digestFile.getAbsolutePath())).append(' ');
        env.append("EDL_VIP_SIG=").append(shQuote(signFile.getAbsolutePath())).append(' ');
        env.append("EDL_VIP_TRANSFER=").append(shQuote(transferXml.getAbsolutePath())).append(' ');
        env.append("EDL_VIP_VERIFY=").append(shQuote(verifyXml.getAbsolutePath())).append(' ');
        env.append("EDL_VIP_SHA=").append(shQuote(shaXml.getAbsolutePath())).append(' ');
        env.append("EDL_VIP_CFG=").append(shQuote(cfgXml.getAbsolutePath())).append(' ');
        // 注：OPlus 参考(OPlus_EDL_Toolkit firehose_service.rs)始终发送 transfercfg，无跳过开关；
        // 故不再产生 EDL_VIP_SKIP_TRANSFER(qdl 侧仍保留该 env 作为独立 CLI 逃生开关)。
        env.append("EDL_MAXPAYLOAD=").append(shQuote(payloadValue)).append(' ');
        return env.toString();
    }

    private boolean isOplusVipPath(File file) {
        if (file == null) {
            return false;
        }
        String path = file.getAbsolutePath();
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        return lower.contains("oplus")
                || lower.contains("oneplus")
                || lower.contains("realme")
                || path.contains("欧加");
    }

    private String buildQdlUsbEnvPrefix(String portPath) {
        String usbPath = resolveQdlUsbPath(portPath);
        if (usbPath == null || usbPath.trim().isEmpty()) {
            return "";
        }
        return "QDL_USB_PATH=" + shQuote(usbPath) + " ";
    }

    private String buildQdlAutoResetEnvPrefix(boolean allowReset) {
        return allowReset && isAutoRebootEnabled() ? "QDL_AUTO_RESET=1 " : "QDL_AUTO_RESET=0 ";
    }

    /*
     * OnePlus 机型(OP5..OP9/Nord/N10/N100)用动态 token 授权(setprojmodel/demacia);
     * OPPO/真我走 VIP 签名摘要,不在此路径。判据:厂商目录是一加专用目录(含"一加"或
     * "OnePlus" 且不含 OPPO/Realme/真我/欧珀/Nothing),避免对 OPPO/真我/Nothing 误触发。
     * builtin loader 被拷成统一文件名,故按选中的厂商目录而非 loader 文件名判别。
     */
    private boolean isOnePlusTokenLoader() {
        String vendor = builtinVendorDir == null ? "" : builtinVendorDir;
        String lower = vendor.toLowerCase();
        boolean oneplus = vendor.contains("一加") || lower.contains("oneplus");
        boolean other = vendor.contains("欧珀") || vendor.contains("真我") || vendor.contains("欧加")
                || lower.contains("oppo") || lower.contains("realme") || lower.contains("nothing");
        return oneplus && !other;
    }

    /*
     * 决定一加动态 token 授权的 EDL_OP_AUTH 取值,与 VIP 互斥(vipActive 含复用 VIP 会话,
     * 配了 VIP 就一律不发 token)。三态:
     *   - 手动开关 / 内置一加目录 / 手填 projid  -> "1"(强制授权,行为同旧版)
     *   - 外部自定义引导(builtinVendorDir==null,文件选择器选的 loader) -> "auto":
     *     loader 来源无法判别一加,交给 qdl 按设备 param 分区的 projid 甄别——命中一加机型
     *     表才授权,否则静默跳过,对非一加零副作用。这样自带 loader 刷一加也能进 token 模式。
     *   - 内置非一加目录(小米/OPPO 等) -> 不发,与旧版完全一致。
     * projid 留空时由 qdl 从 param 自动读取;手填 deviceModel/serial 作为覆盖。
     */
    private String buildOplusTokenEnvPrefix(boolean vipActive) {
        if (vipActive) {
            return "";
        }
        boolean manual = oplusTokenAuthCheck != null && oplusTokenAuthCheck.isChecked();
        String projid = deviceModelInput == null ? "" : deviceModelInput.getText().toString().trim();
        String mode;
        if (manual || isOnePlusTokenLoader() || !projid.isEmpty()) {
            mode = "1";
        } else if (builtinVendorDir == null) {
            // 外部自定义引导:来源不可判,下沉到 qdl 按设备身份探测
            mode = "auto";
        } else {
            // 内置非一加目录:不授权
            return "";
        }
        StringBuilder env = new StringBuilder();
        env.append("EDL_OP_AUTH=").append(mode).append(' ');
        // v1/v2 一加 loader 多数支持 demacia,先发(qdl 对 v3 自动忽略、失败非致命)
        env.append("EDL_OP_DEMACIA=1 ");
        if (!projid.isEmpty()) {
            env.append("EDL_OP_PROJID=").append(shQuote(projid)).append(' ');
        }
        String serial = oplusSerialInput == null ? "" : oplusSerialInput.getText().toString().trim();
        if (!serial.isEmpty()) {
            env.append("EDL_OP_SERIAL=").append(shQuote(serial)).append(' ');
        }
        return env.toString();
    }

    private boolean shouldEnableQdlAutoReset(List<File> xmlFiles) {
        if (xmlFiles == null || xmlFiles.isEmpty()) {
            return false;
        }
        boolean hasWriteLike = false;
        for (File xmlFile : xmlFiles) {
            if (xmlFile == null || !xmlFile.exists()) {
                continue;
            }
            String xml = readFileText(xmlFile);
            if (xml == null) {
                continue;
            }
            String lower = xml.toLowerCase(Locale.US);
            // 任一 XML 含 <power>(显式 reset/power)：复位意图由该命令本身表达，绝不再让 qdl 追加
            // RESET，否则会用默认 value="reset" 覆盖 reset_to_edl/fastboot/recovery 等意图。
            if (lower.contains("<power")) {
                return false;
            }
            // <ufs>(provision) 走 qdl firehose_provision 分支、由 --skip-reset 而非 QDL_AUTO_RESET
            // 控制复位，故不计入；仅写/擦/切槽类命令完成后才需要 qdl 追加自动复位。
            if (lower.contains("<program")
                    || lower.contains("<patch")
                    || lower.contains("<erase")
                    || lower.contains("<setbootablestoragedrive")) {
                hasWriteLike = true;
            }
        }
        return hasWriteLike;
    }

    private boolean isElfFile(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] hdr = new byte[4];
            if (in.read(hdr) != hdr.length) {
                return false;
            }
            return hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    private void updateEdlStatus() {
        if (edlStatusView == null || portInput == null || vidInput == null || pidInput == null) {
            return;
        }
        PortId portId = resolveStatusPortId();
        if (portId == null) {
            cb.onDeviceStatus(getString(R.string.edl_status_unknown));
            return;
        }
        UsbPathInfo info = resolveEdlUsbPathInfo(portId);
        String vidPid = info.vidPid;
        String directPath = "";
        String usbPath = info.usbPath;
        syncVipAuthState(usbPath, vidPid);
        String status;
        if (usbPath != null) {
            status = getString(R.string.edl_status_connected, usbPath, vidPid);
        } else if (!directPath.isEmpty()) {
            status = getString(R.string.edl_status_connected, directPath, vidPid);
        } else {
            status = getString(R.string.edl_status_disconnected, vidPid);
        }
        cb.onDeviceStatus(status);
    }

    private void refreshVipAuthStateForRun() {
        PortId portId = resolveStatusPortId();
        if (portId == null) {
            return;
        }
        UsbPathInfo info = resolveEdlUsbPathInfo(portId);
        syncVipAuthState(info.usbPath, info.vidPid);
    }

    private void syncVipAuthState(String usbPath, String vidPid) {
        boolean connected = usbPath != null && !usbPath.trim().isEmpty();
        if (!connected) {
            if (lastEdlConnected) {
                resetVipAuthState();
            }
            lastEdlConnected = false;
            return;
        }
        if (!lastEdlConnected) {
            vipAuthorized = false;
            vipSessionHealthy = false;
        }
        lastEdlConnected = true;
    }

    private void persistVipAuthState() {
        // 兼容旧版本残留的持久化字段，新的 VIP 会话状态只保存在内存中。
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit()
                .putBoolean(PREF_VIP_AUTH, false)
                .remove(PREF_VIP_AUTH_KEY)
                .apply();
    }

    private UsbPathInfo resolveEdlUsbPathInfo(PortId portId) {
        if (portId == null) {
            return new UsbPathInfo(null, "");
        }
        // 主 PID 优先；指定的是默认 9008 时再按 EDL 备用 PID(900e crash dump / 901d 变体)回退，
        // 与 qdl usb.c 的 9008/900e/901d 白名单一致。每个 PID 走完整 4 级检测。
        List<String> pids = new ArrayList<>();
        pids.add(portId.pid);
        if (DEFAULT_USB_PID.equalsIgnoreCase(portId.pid)) {
            pids.add(DEFAULT_USB_PID_ALT);
            pids.add(DEFAULT_USB_PID_ALT2);
        }
        for (String pid : pids) {
            String usbPath = detectUsbPathAllSources(portId.vid, pid);
            if (usbPath != null) {
                return new UsbPathInfo(usbPath, portId.vid + ":" + pid);
            }
        }
        return new UsbPathInfo(null, portId.vid + ":" + portId.pid);
    }

    // 依次用 4 种来源(UsbManager→sysfs→root sysfs→debug)定位 USB 路径，任一命中即返回。
    private String detectUsbPathAllSources(String vid, String pid) {
        String usbPath = detectUsbPathFromUsbManager(new PortId(vid, pid));
        if (usbPath == null) {
            usbPath = detectUsbBusPath(vid, pid);
        }
        if (usbPath == null) {
            usbPath = detectUsbBusPathWithRoot(vid, pid);
        }
        if (usbPath == null) {
            usbPath = detectUsbPathFromDebug(vid, pid);
        }
        return usbPath;
    }

    private static class UsbPathInfo {
        final String usbPath;
        final String vidPid;

        UsbPathInfo(String usbPath, String vidPid) {
            this.usbPath = usbPath;
            this.vidPid = vidPid == null ? "" : vidPid;
        }
    }

    private PortId resolveStatusPortId() {
        return new PortId(DEFAULT_USB_VID, DEFAULT_USB_PID);
    }

    private String detectUsbBusPath(String vid, String pid) {
        File base = new File("/sys/bus/usb/devices");
        File[] dirs = base.listFiles();
        if (dirs == null) {
            return null;
        }
        String targetVid = normalizeHexId(vid);
        String targetPid = normalizeHexId(pid);
        if (targetVid == null || targetPid == null) {
            return null;
        }
        for (File dir : dirs) {
            String vendor = normalizeHexId(readSysfsValue(new File(dir, "idVendor")));
            String product = normalizeHexId(readSysfsValue(new File(dir, "idProduct")));
            if (vendor == null || product == null) {
                continue;
            }
            if (!targetVid.equalsIgnoreCase(vendor) || !targetPid.equalsIgnoreCase(product)) {
                continue;
            }
            String busnum = readSysfsValue(new File(dir, "busnum"));
            String devnum = readSysfsValue(new File(dir, "devnum"));
            if (busnum == null || devnum == null) {
                continue;
            }
            String path = formatUsbDevPath(busnum.trim(), devnum.trim());
            if (path != null && new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    private String detectUsbBusPathWithRoot(String vid, String pid) {
        if (!rootAvailable) {
            return null;
        }
        String targetVid = normalizeHexId(vid);
        String targetPid = normalizeHexId(pid);
        if (targetVid == null || targetPid == null) {
            return null;
        }
        String cmd = "for d in /sys/bus/usb/devices/*; do "
                + "v=$(cat $d/idVendor 2>/dev/null | tr 'A-F' 'a-f'); "
                + "p=$(cat $d/idProduct 2>/dev/null | tr 'A-F' 'a-f'); "
                + "if [ \"$v\" = \"" + targetVid + "\" ] && [ \"$p\" = \"" + targetPid + "\" ]; then "
                + "b=$(cat $d/busnum 2>/dev/null); "
                + "n=$(cat $d/devnum 2>/dev/null); "
                + "if [ -n \"$b\" ] && [ -n \"$n\" ]; then "
                + "echo \"$b $n\"; "
                + "break; "
                + "fi; "
                + "fi; "
                + "done";
        try {
            CommandResult result = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            if (result.exitCode == 0 && result.output != null) {
                String[] parts = result.output.trim().split("\\s+");
                if (parts.length >= 2) {
                    String path = formatUsbDevPath(parts[0], parts[1]);
                    if (path != null && new File(path).exists()) {
                        return path;
                    }
                }
            }
        } catch (IOException | InterruptedException ignored) {
        }
        return null;
    }

    private String detectUsbPathFromUsbManager(PortId portId) {
        if (portId == null) {
            return null;
        }
        try {
            int vid = Integer.parseInt(portId.vid, 16);
            int pid = Integer.parseInt(portId.pid, 16);
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (manager == null) {
                return null;
            }
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device.getVendorId() == vid && device.getProductId() == pid) {
                    return device.getDeviceName();
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private String detectUsbPathFromDebug(String vid, String pid) {
        String targetVid = normalizeHexId(vid);
        String targetPid = normalizeHexId(pid);
        if (targetVid == null || targetPid == null) {
            return null;
        }
        String content = readDebugUsbDevices();
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        String bus = null;
        String dev = null;
        String foundVid = null;
        String foundPid = null;
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                String path = buildUsbPathIfMatch(targetVid, targetPid, bus, dev, foundVid, foundPid);
                if (path != null) {
                    return path;
                }
                bus = null;
                dev = null;
                foundVid = null;
                foundPid = null;
                continue;
            }
            if (trimmed.startsWith("T:")) {
                Matcher matcher = DEBUG_T_PATTERN.matcher(trimmed);
                if (matcher.matches()) {
                    bus = matcher.group(1);
                    dev = matcher.group(2);
                }
            } else if (trimmed.startsWith("P:")) {
                Matcher matcher = DEBUG_P_PATTERN.matcher(trimmed);
                if (matcher.matches()) {
                    foundVid = normalizeHexId(matcher.group(1));
                    foundPid = normalizeHexId(matcher.group(2));
                }
            }
        }
        return buildUsbPathIfMatch(targetVid, targetPid, bus, dev, foundVid, foundPid);
    }

    private String buildUsbPathIfMatch(String targetVid, String targetPid,
                                       String bus, String dev, String vid, String pid) {
        if (bus == null || dev == null || vid == null || pid == null) {
            return null;
        }
        if (!targetVid.equalsIgnoreCase(vid) || !targetPid.equalsIgnoreCase(pid)) {
            return null;
        }
        String path = formatUsbDevPath(bus.trim(), dev.trim());
        return path != null && new File(path).exists() ? path : null;
    }

    private String formatUsbDevPath(String bus, String dev) {
        try {
            int busNum = Integer.parseInt(bus);
            int devNum = Integer.parseInt(dev);
            return String.format(Locale.US, "/dev/bus/usb/%03d/%03d", busNum, devNum);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String findSerialPortForVidPid(String vid, String pid) {
        String targetVid = normalizeHexId(vid);
        String targetPid = normalizeHexId(pid);
        if (targetVid == null || targetPid == null) {
            return null;
        }
        String[] prefixes = new String[] {"ttyUSB", "ttyACM", "ttyHSUSB"};
        for (String prefix : prefixes) {
            String match = findSerialPortForVidPid(prefix, targetVid, targetPid);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private String findSerialPortForVidPid(String prefix, String vid, String pid) {
        File ttyBase = new File("/sys/class/tty");
        File[] entries = ttyBase.listFiles();
        if (entries == null) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (name == null || !name.startsWith(prefix)) {
                continue;
            }
            candidates.add(name);
        }
        candidates.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : candidates) {
            File devDir = new File(ttyBase, name + "/device");
            String foundVid = readSysfsUpwards(devDir, "idVendor");
            String foundPid = readSysfsUpwards(devDir, "idProduct");
            if (foundVid == null || foundPid == null) {
                continue;
            }
            if (vid.equalsIgnoreCase(normalizeHexId(foundVid))
                    && pid.equalsIgnoreCase(normalizeHexId(foundPid))) {
                return "/dev/" + name;
            }
        }
        return null;
    }

    private String readSysfsUpwards(File start, String name) {
        File current = start;
        for (int i = 0; i < 6 && current != null; i++) {
            File candidate = new File(current, name);
            String value = readSysfsValue(candidate);
            if (value != null) {
                return value;
            }
            current = current.getParentFile();
        }
        return null;
    }

    private String findFirstDevNode(String prefix) {
        File devDir = new File("/dev");
        File[] entries = devDir.listFiles();
        if (entries == null) {
            return null;
        }
        List<String> matches = new ArrayList<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (name != null && name.startsWith(prefix)) {
                matches.add("/dev/" + name);
            }
        }
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private String readSysfsValue(File file) {
        if (!file.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private String readFileContent(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private String readDebugUsbDevices() {
        String content = readFileContent(new File("/sys/kernel/debug/usb/devices"));
        if (content != null && !content.trim().isEmpty()) {
            return content;
        }
        if (!rootAvailable) {
            return null;
        }
        String cmd = "if ! grep -q ' /sys/kernel/debug ' /proc/mounts; then "
                + "mount -t debugfs debugfs /sys/kernel/debug 2>/dev/null; "
                + "fi; "
                + "if [ -r /sys/kernel/debug/usb/devices ]; then "
                + "cat /sys/kernel/debug/usb/devices; "
                + "fi";
        try {
            CommandResult result = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            if (result.exitCode == 0) {
                return result.output;
            }
        } catch (IOException | InterruptedException ignored) {
        }
        return null;
    }

    private String normalizeHexId(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim().toLowerCase(Locale.US);
        if (text.startsWith("0x")) {
            text = text.substring(2);
        }
        if (text.isEmpty() || !text.matches("[0-9a-f]{1,4}")) {
            return null;
        }
        try {
            int num = Integer.parseInt(text, 16);
            return String.format(Locale.US, "%04x", num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveQdlPortArg() {
        String raw = portInput == null ? "" : portInput.getText().toString().trim();
        if (raw.isEmpty() || "auto".equalsIgnoreCase(raw)) {
            return "auto";
        }
        String usbPath = normalizeUsbDevPath(raw);
        if (usbPath != null) {
            return "auto";
        }
        return "auto";
    }

    private String resolveQdlUsbPath(String preferredPath) {
        String direct = normalizeUsbDevPath(preferredPath);
        if (direct != null && new File(direct).exists()) {
            return direct;
        }
        PortId portId = resolveStatusPortId();
        if (portId == null) {
            return null;
        }
        // 不在此阻塞等设备：只做一次只读枚举，在场则给路径，不在场返回 null
        // 让 qdl 的 usb_open 承担等待（设备出现即抓取），避免操作前的循环轮询。
        UsbPathInfo info = resolveEdlUsbPathInfo(portId);
        if (info == null || info.usbPath == null || info.usbPath.trim().isEmpty()) {
            return null;
        }
        return info.usbPath.trim();
    }

    private String normalizeUsbDevPath(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "auto".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (trimmed.startsWith("/dev/bus/usb/")) {
            return trimmed;
        }
        Matcher matcher = USB_BUSDEV_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return formatUsbDevPath(matcher.group(1), matcher.group(2));
        }
        return null;
    }

    private String normalizeAbsolutePath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.startsWith("/")) {
            return trimmed;
        }
        return "/" + trimmed;
    }

    private String normalizeUserOutputPath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        trimmed = ensureImgExtension(trimmed);
        String base = normalizeAbsolutePath(DEFAULT_DOWNLOAD_DIR);
        String baseLower = base.toLowerCase(Locale.US);
        if (trimmed.startsWith("/")) {
            String lower = trimmed.toLowerCase(Locale.US);
            if (lower.startsWith(baseLower)) {
                return normalizeAbsolutePath(trimmed);
            }
            return normalizeAbsolutePath(base + "/" + new File(trimmed).getName());
        }
        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.startsWith("sdcard") || lower.startsWith("storage") || lower.startsWith("mnt")) {
            String candidate = "/" + trimmed;
            if (candidate.toLowerCase(Locale.US).startsWith(baseLower)) {
                return normalizeAbsolutePath(candidate);
            }
            return normalizeAbsolutePath(base + "/" + new File(trimmed).getName());
        }
        if (trimmed.contains("/")) {
            String candidate = "/" + trimmed;
            if (candidate.toLowerCase(Locale.US).startsWith(baseLower)) {
                return normalizeAbsolutePath(candidate);
            }
            return normalizeAbsolutePath(base + "/" + new File(trimmed).getName());
        }
        return normalizeAbsolutePath(base + "/" + trimmed);
    }

    private String buildDownloadImagePath(String partitionName) {
        String name = partitionName == null ? "" : partitionName.trim();
        if (name.isEmpty()) {
            name = "partition";
        }
        name = ensureImgExtension(name);
        String base = normalizeAbsolutePath(DEFAULT_DOWNLOAD_DIR);
        return base + "/" + name;
    }

    private ParsedOutput parseOutputWithRange(String outputPath, String fallbackName) {
        String name = outputPath == null ? "" : outputPath.trim();
        if (name.isEmpty()) {
            name = fallbackName;
        }
        QualcommTables.MemRange range = null;
        int at = name.lastIndexOf('@');
        if (at > 0 && at + 1 < name.length()) {
            String baseName = name.substring(0, at).trim();
            String rangeText = name.substring(at + 1).trim();
            QualcommTables.MemRange parsed = parseMemRange(rangeText);
            if (parsed != null) {
                range = parsed;
            }
            if (!baseName.isEmpty()) {
                name = baseName;
            }
        }
        name = ensureImgExtension(name);
        String path = buildReadOutputPath("mem", "", "", "", name);
        return new ParsedOutput(new File(path), range);
    }

    private QualcommTables.MemRange parseMemRange(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts;
        if (raw.contains(":")) {
            parts = raw.split(":", 2);
        } else if (raw.contains("+")) {
            parts = raw.split("\\+", 2);
        } else if (raw.contains(",")) {
            parts = raw.split(",", 2);
        } else {
            return null;
        }
        if (parts.length < 2) {
            return null;
        }
        long addr = parseNumberFlexible(parts[0], -1L);
        long size = parseNumberFlexible(parts[1], -1L);
        if (addr <= 0 || size <= 0) {
            return null;
        }
        return new QualcommTables.MemRange(addr, size);
    }

    private String buildReadOutputPath(String command, String arg1, String arg2, String arg3, String outputName) {
        String fileName = "";
        if (outputName != null && !outputName.trim().isEmpty()) {
            fileName = new File(outputName.trim()).getName();
        }
        if (fileName.isEmpty()) {
            fileName = defaultReadFileName(command, arg1, arg2, arg3);
        }
        fileName = sanitizeFileName(ensureImgExtension(fileName));
        String base = normalizeAbsolutePath(DEFAULT_DOWNLOAD_DIR);
        return base + "/" + fileName;
    }

    private String buildDownloadDirPath(String command, String outputName) {
        String dirName = "";
        if (outputName != null && !outputName.trim().isEmpty()) {
            dirName = new File(outputName.trim()).getName();
        }
        if (dirName.isEmpty()) {
            dirName = command + "_" + System.currentTimeMillis();
        }
        String base = normalizeAbsolutePath(DEFAULT_DOWNLOAD_DIR);
        return base + "/" + sanitizeFileName(dirName);
    }

    private String defaultReadFileName(String command, String arg1, String arg2, String arg3) {
        if ("rf".equals(command)) {
            return "full_dump.img";
        }
        if ("rs".equals(command)) {
            String start = arg1 == null ? "" : arg1.trim();
            String count = arg2 == null ? "" : arg2.trim();
            String name = "rs";
            if (!start.isEmpty() && !count.isEmpty()) {
                name = "rs_" + start + "_" + count;
            }
            return name + ".img";
        }
        if ("peek".equals(command)) {
            String offset = arg1 == null ? "" : arg1.trim();
            String len = arg2 == null ? "" : arg2.trim();
            String name = "peek";
            if (!offset.isEmpty() && !len.isEmpty()) {
                name = "peek_" + offset + "_" + len;
            }
            return name + ".img";
        }
        if ("memtbl".equals(command)) {
            return "memtbl.img";
        }
        if ("pbl".equals(command)) {
            return "pbl.img";
        }
        if ("qfp".equals(command)) {
            return "qfp.img";
        }
        if ("footer".equals(command)) {
            return "footer.img";
        }
        return "read_output.img";
    }

    private String ensureImgExtension(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "image.img";
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.endsWith(".img")) {
            return trimmed.substring(0, trimmed.length() - 4) + ".img";
        }
        if (lower.endsWith(".bin")) {
            return trimmed.substring(0, trimmed.length() - 4) + ".img";
        }
        return trimmed + ".img";
    }

    private String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        return name.replace("/", "_").replace("\\", "_");
    }

    private void ensureDownloadDirExists() {
        ensureDirExists(DEFAULT_DOWNLOAD_DIR);
    }

    private void ensureDirExists(String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        String normalized = normalizeAbsolutePath(path);
        try {
            CommandResult result = runCommandWithRoot(null,
                    "mkdir -p " + shQuote(normalized),
                    false,
                    getRootEdlBinDir());
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private boolean isFastModeEnabled() {
        if (fastModeCheck != null) {
            return fastModeCheck.isChecked();
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getBoolean(PREF_FAST_MODE, true);
    }

    private boolean isAutoRebootEnabled() {
        if (autoRebootCheck != null) {
            return autoRebootCheck.isChecked();
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getBoolean(PREF_AUTO_REBOOT, true);
    }

    private boolean isQfilSplitEnabled() {
        return edlPackageSplitCheck != null && edlPackageSplitCheck.isChecked();
    }

    private boolean isProtectLun5Enabled() {
        return edlPackageProtectLun5Check != null && edlPackageProtectLun5Check.isChecked();
    }

    // 是否在缺少现成 super.img 时由分片(super.N)或组件(super_def + lpmake)合并生成。
    // 默认开(含 null，保持原行为)；关闭则只用已存在的 super.img，否则跳过 super 不构建。
    private boolean isMergeSuperEnabled() {
        return edlPackageMergeSuperCheck == null || edlPackageMergeSuperCheck.isChecked();
    }

    private int getCachedBlockSize() {
        if (cachedBlockSize > 0) {
            return cachedBlockSize;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        cachedBlockSize = prefs.getInt(PREF_BLOCK_SIZE, -1);
        return cachedBlockSize;
    }

    private void cacheBlockSize(int sectorSize) {
        if (sectorSize <= 0 || cachedBlockSize == sectorSize) {
            return;
        }
        cachedBlockSize = sectorSize;
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putInt(PREF_BLOCK_SIZE, cachedBlockSize);
        editor.apply();
    }

    private int getCachedMaxPayload() {
        if (cachedMaxPayload > 0) {
            return cachedMaxPayload;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        cachedMaxPayload = prefs.getInt(PREF_MAX_PAYLOAD, -1);
        return cachedMaxPayload;
    }

    private void updateCachedStorageInfoFromLog(File runDir) {
        if (runDir == null) {
            return;
        }
        File logFile = new File(runDir, "run.log");
        String tail = readLogTail(logFile, 65536);
        if (tail == null || tail.isEmpty()) {
            return;
        }
        int blockSize = parseBlockSize(tail);
        int maxPayload = parseMaxPayload(tail);
        long totalBlocks = parseTotalBlocks(tail);
        int numPhysical = parseNumPhysical(tail);
        long lunMask = parseLunEnableMask(tail);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = null;
        if (blockSize > 0 && blockSize != cachedBlockSize) {
            cachedBlockSize = blockSize;
            if (editor == null) {
                editor = prefs.edit();
            }
            editor.putInt(PREF_BLOCK_SIZE, blockSize);
        }
        if (maxPayload > 0 && maxPayload != cachedMaxPayload) {
            cachedMaxPayload = maxPayload;
            if (editor == null) {
                editor = prefs.edit();
            }
            editor.putInt(PREF_MAX_PAYLOAD, maxPayload);
        }
        if (totalBlocks > 0) {
            cachedTotalBlocks = totalBlocks;
        }
        if (numPhysical > 0) {
            cachedNumPhysical = numPhysical;
        }
        if (lunMask > 0L) {
            cachedLunEnableMask = lunMask;
        }
        if (editor != null) {
            editor.apply();
        }
    }

    private int parseBlockSize(String text) {
        int value = -1;
        Matcher matcher = BLOCK_SIZE_PATTERN.matcher(text);
        while (matcher.find()) {
            String hex = matcher.group(1);
            String dec = matcher.group(2);
            String sector = matcher.group(3);
            try {
                if (hex != null && !hex.isEmpty()) {
                    value = Integer.parseInt(hex, 16);
                } else if (dec != null && !dec.isEmpty()) {
                    value = Integer.parseInt(dec);
                } else if (sector != null && !sector.isEmpty()) {
                    value = Integer.parseInt(sector);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (value <= 0) {
            matcher = STORAGE_BLOCK_SIZE_PATTERN.matcher(text);
            while (matcher.find()) {
                String dec = matcher.group(1);
                if (dec == null || dec.isEmpty()) {
                    continue;
                }
                try {
                    value = Integer.parseInt(dec);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return value;
    }

    private long parseTotalBlocks(String text) {
        if (text == null || text.isEmpty()) {
            return -1L;
        }
        long value = -1L;
        Matcher matcher = STORAGE_TOTAL_BLOCKS_PATTERN.matcher(text);
        while (matcher.find()) {
            String dec = matcher.group(1);
            if (dec == null || dec.isEmpty()) {
                continue;
            }
            try {
                value = Long.parseLong(dec);
            } catch (NumberFormatException ignored) {
            }
        }
        return value;
    }

    private int parseNumPhysical(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        int value = -1;
        Matcher matcher = STORAGE_NUM_PHYSICAL_PATTERN.matcher(text);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String raw = matcher.group(i);
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                long parsed = parseNumberFlexible(raw, -1L);
                if (parsed > 0 && parsed <= Integer.MAX_VALUE) {
                    value = (int) parsed;
                }
            }
        }
        return value;
    }

    private long parseLunEnableMask(String text) {
        if (text == null || text.isEmpty()) {
            return -1L;
        }
        long value = -1L;
        Matcher matcher = STORAGE_LUN_MASK_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            long parsed = parseNumberFlexible(raw, -1L);
            if (parsed > 0L) {
                value = parsed;
            }
        }
        return value;
    }

    private int parseMaxPayload(String text) {
        int value = -1;
        Matcher matcher = MAX_PAYLOAD_PATTERN.matcher(text);
        while (matcher.find()) {
            String dec = matcher.group(1);
            if (dec == null || dec.isEmpty()) {
                continue;
            }
            try {
                value = Integer.parseInt(dec);
            } catch (NumberFormatException ignored) {
            }
        }
        return value;
    }

    private boolean renameFile(File source, File target) {
        if (source == null || target == null) {
            return false;
        }
        if (source.getAbsolutePath().equals(target.getAbsolutePath())) {
            return true;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (target.exists() && target.lastModified() <= source.lastModified()) {
            target.delete();
        }
        return source.renameTo(target);
    }

    private String resolveArg(String currentValue, Uri uri, File runDir, ArgType type) {
        if (uri == null || type == ArgType.NONE || type == ArgType.TEXT || type == ArgType.OUTPUT) {
            return currentValue;
        }
        String path = resolvePathFromUri(uri, type == ArgType.DIR);
        if (path == null || path.isEmpty()) {
            showToast(getString(R.string.error_uri_no_path_generic));
            return currentValue;
        }
        return path;
    }

    // Resolve a list of SAF/content URIs (multi-select) to real file paths.
    private List<File> resolveUriListToFiles(List<Uri> uris, File runDir) {
        List<File> files = new ArrayList<>();
        if (uris == null || uris.isEmpty()) {
            return files;
        }
        for (Uri uri : uris) {
            if (uri == null) {
                continue;
            }
            String path = resolvePathFromUri(uri, false);
            if (path != null && !path.trim().isEmpty()) {
                files.add(new File(path));
            } else if (runDir != null) {
                // SAF URI without a real filesystem path: copy it into runDir
                // so qdl can read it (images are still found via the arg3 dir).
                try {
                    String name = sanitizeFileName(getDisplayName(uri));
                    if (name.isEmpty()) {
                        name = "selected_" + files.size() + ".xml";
                    }
                    files.add(copyUriToDir(uri, runDir, name));
                } catch (IOException e) {
                    appendWorkLog(runDir, "XML 复制失败: " + e.getMessage());
                }
            }
        }
        return files;
    }

    private QfilInputs resolveQfilInputs(String rawprogram, String patch, String imageDir) {
        return resolveQfilInputs(rawprogram, patch, imageDir, null, null);
    }

    private QfilInputs resolveQfilInputs(String rawprogram, String patch, String imageDir,
                                         List<File> rawMultiFiles, List<File> patchMultiFiles) {
        String rawprogramPath = rawprogram == null ? "" : rawprogram.trim();
        String patchPath = patch == null ? "" : patch.trim();
        String imageDirPath = imageDir == null ? "" : imageDir.trim();
        boolean manualRawprogram = !rawprogramPath.isEmpty();
        boolean manualPatch = !patchPath.isEmpty();
        boolean manualImageDir = !imageDirPath.isEmpty();

        EdlPackageInfo info = null;
        if (edlPackageUri != null) {
            if (edlPackageInfoData == null) {
                String basePath = resolvePathFromUri(edlPackageUri, true);
                if (basePath != null && !basePath.trim().isEmpty()) {
                    edlPackageInfoData = parseEdlPackageInfo(basePath);
                }
            }
            info = edlPackageInfoData;
        }

        List<File> rawprogramFiles = new ArrayList<>();
        List<File> patchFiles = new ArrayList<>();
        File imagesDir = null;
        if (info != null) {
            imagesDir = info.imagesDir;
            if (!manualRawprogram && info.rawprogramFiles != null) {
                rawprogramFiles.addAll(info.rawprogramFiles);
            }
            if (!manualPatch && info.patchFiles != null) {
                patchFiles.addAll(info.patchFiles);
            }
            if (!manualRawprogram && rawprogramPath.isEmpty()) {
                rawprogramPath = pickDefaultXmlPath(info.rawprogramFiles, RAWPROGRAM_FILE_PATTERN);
            }
            if (!manualPatch && patchPath.isEmpty()) {
                patchPath = pickDefaultXmlPath(info.patchFiles, PATCH_FILE_PATTERN);
            }
            if (!manualImageDir && imageDirPath.isEmpty() && imagesDir != null) {
                imageDirPath = imagesDir.getAbsolutePath();
            }
        }

        if (rawMultiFiles != null && !rawMultiFiles.isEmpty()) {
            rawprogramFiles.clear();
            rawprogramFiles.addAll(rawMultiFiles);
            rawprogramPath = rawMultiFiles.get(0).getAbsolutePath();
        } else if (manualRawprogram && !rawprogramPath.isEmpty()) {
            rawprogramFiles.clear();
            rawprogramFiles.add(new File(rawprogramPath));
        }
        if (patchMultiFiles != null && !patchMultiFiles.isEmpty()) {
            patchFiles.clear();
            patchFiles.addAll(patchMultiFiles);
            patchPath = patchMultiFiles.get(0).getAbsolutePath();
        } else if (manualPatch && !patchPath.isEmpty()) {
            patchFiles.clear();
            patchFiles.add(new File(patchPath));
        }
        rawprogramFiles = dedupeRawprogramVariants(filterLun5XmlFiles(rawprogramFiles, RAWPROGRAM_FILE_PATTERN));
        patchFiles = filterLun5XmlFiles(patchFiles, PATCH_FILE_PATTERN);
        // 镜像目录为空、或只拿到相对名（如 SAF 仅返回目录显示名 "IMAGES"）时，
        // 以 rawprogram.xml 所在目录为基准还原真实路径
        if (!rawprogramFiles.isEmpty()
                && (imageDirPath.isEmpty() || !imageDirPath.startsWith("/"))) {
            File parent = rawprogramFiles.get(0).getParentFile();
            if (parent != null) {
                File candidate = imageDirPath.isEmpty() ? parent : new File(parent, imageDirPath);
                // 相对名拼出的子目录存在就用它，否则退回 xml 所在目录（镜像通常与 xml 同级）
                if (rootExists(candidate.getAbsolutePath(), true)) {
                    imageDirPath = candidate.getAbsolutePath();
                } else {
                    imageDirPath = parent.getAbsolutePath();
                }
            }
        }

        File imageDirFile = imageDirPath.isEmpty() ? null : new File(imageDirPath);
        File baseDir = info != null ? info.baseDir : null;
        if (baseDir == null && imageDirFile != null) {
            baseDir = imageDirFile;
        }
        if (baseDir == null && !rawprogramFiles.isEmpty()) {
            baseDir = rawprogramFiles.get(0).getParentFile();
        }
        return new QfilInputs(rawprogramPath, patchPath, imageDirPath, rawprogramFiles, patchFiles, imageDirFile, baseDir);
    }

    private QfilInputs prepareQfilInputs(File runDir, QfilInputs inputs) {
        if (runDir == null || inputs == null) {
            return inputs;
        }
        if (inputs.rawprogramFiles != null && !inputs.rawprogramFiles.isEmpty()) {
            return inputs;
        }
        if (edlPackageUri == null) {
            return inputs;
        }
        String path = resolvePathFromUri(edlPackageUri, false);
        String displayName = getDisplayName(edlPackageUri);
        String probe = path != null && !path.trim().isEmpty() ? path.trim() : displayName;
        if (!isOplusPackageFile(probe)) {
            return inputs;
        }
        File packageFile = resolveOplusPackageFile(runDir, edlPackageUri, path, displayName);
        if (packageFile == null || !packageFile.exists()) {
            appendWorkLog(runDir, "未找到 OFP/OPS 文件");
            return inputs;
        }
        appendWorkLog(runDir, "检测到 OFP/OPS 包，开始解包...");
        appendLog("检测到 OFP/OPS 包，开始解包...");
        String extractedPath = extractOplusPackage(runDir, packageFile);
        if (extractedPath == null) {
            appendWorkLog(runDir, "解包失败");
            appendLog(getString(R.string.edl_package_extract_failed));
            return inputs;
        }
        File extractDir = new File(extractedPath);
        EdlPackageInfo info = parseEdlPackageInfo(extractDir.getAbsolutePath());
        if (info == null || info.rawprogramFiles.isEmpty()) {
            appendWorkLog(runDir, "解包目录未发现 rawprogram.xml");
            return inputs;
        }
        edlPackageInfoData = info;
        String rawprogramPath = pickDefaultXmlPath(info.rawprogramFiles, RAWPROGRAM_FILE_PATTERN);
        String patchPath = pickDefaultXmlPath(info.patchFiles, PATCH_FILE_PATTERN);
        File imagesDir = info.imagesDir != null ? info.imagesDir : extractDir;
        String imageDirPath = imagesDir.getAbsolutePath();
        return new QfilInputs(rawprogramPath, patchPath, imageDirPath,
                info.rawprogramFiles, info.patchFiles, imagesDir, info.baseDir);
    }

    private File resolveOplusPackageFile(File runDir, Uri uri, String path, String displayName) {
        if (path != null && !path.trim().isEmpty()) {
            File file = new File(path.trim());
            if (file.exists()) {
                return file;
            }
        }
        if (uri == null || runDir == null) {
            return null;
        }
        String fallback = displayName;
        if (fallback == null || fallback.trim().isEmpty()) {
            fallback = "oplus_package.ofp";
        }
        try {
            return copyUriToDir(uri, runDir, fallback);
        } catch (IOException e) {
            appendWorkLog(runDir, "拷贝 OFP/OPS 失败: " + e.getMessage());
            return null;
        }
    }

    private String extractOplusPackage(File runDir, File packageFile) {
        if (runDir == null || packageFile == null) {
            return null;
        }
        // 解包产物落到独立的持久缓存目录 edl_extract/<包名_大小>，而非 work/ 下:
        //  - work/ 每次操作起点都被 cleanupWorkDir 清空(原实现把解包放这里→预览解一遍、刷写又解一遍)；
        //  - edl/ 随 ASSET_VERSION 被 ensureEdlExtracted 整体删(升级即丢)。
        // edl_extract/ 两者都不触及 → 同一个几 GB 的包预览解一次，刷写按 key 命中直接复用。
        File cacheRoot = new File(getFilesDir(), "edl_extract");
        String key = sanitizeFileName(packageFile.getName()) + "_" + packageFile.length();
        File cacheDir = new File(cacheRoot, key);
        if (isExtractCacheValid(cacheDir)) {
            appendWorkLog(runDir, "复用已解包目录(免重复解压): " + cacheDir.getAbsolutePath());
            return cacheDir.getAbsolutePath();
        }
        deleteRecursive(cacheDir);
        if (!cacheRoot.exists()) {
            cacheRoot.mkdirs();
        }
        // 换包即清掉其它包的旧解包缓存，避免 edl_extract/ 无限堆积(每个解包可达数 GB)；
        // 只保留当前包,重复刷同一包仍命中复用。
        File[] stale = cacheRoot.listFiles();
        if (stale != null) {
            for (File s : stale) {
                if (s != null && !s.getName().equals(key) && !s.getName().equals(key + ".tmp")) {
                    deleteRecursive(s);
                }
            }
        }
        // 先解到 .tmp 再原子改名，避免中途失败的半截目录被下次误判为命中
        File tmpDir = new File(cacheRoot, key + ".tmp");
        deleteRecursive(tmpDir);
        OfpDecryptor.Logger logger = msg -> {
            appendWorkLog(runDir, msg);
            appendLog(msg);
        };
        String name = packageFile.getName().toLowerCase(Locale.US);
        String out;
        if (name.endsWith(".ops")) {
            out = new OpsDecryptor(logger::log).decrypt(packageFile.getAbsolutePath(), tmpDir.getAbsolutePath());
        } else {
            out = new OfpDecryptor(logger).decrypt(packageFile.getAbsolutePath(), tmpDir.getAbsolutePath());
        }
        if (out == null || !isExtractCacheValid(tmpDir)) {
            deleteRecursive(tmpDir);
            return out;
        }
        if (tmpDir.renameTo(cacheDir)) {
            return cacheDir.getAbsolutePath();
        }
        appendWorkLog(runDir, "解包缓存改名失败，本次直接使用临时目录");
        return tmpDir.getAbsolutePath();
    }

    // 解包缓存目录是否自洽可复用:能解析出 rawprogram/<Setting> settings.xml 即视为完整。
    // 解包走"先解到 .tmp 再原子 rename"，故 cacheDir 存在即代表上次解包已整盘成功。
    private boolean isExtractCacheValid(File dir) {
        if (dir == null || !rootExists(dir.getAbsolutePath(), true)) {
            return false;
        }
        EdlPackageInfo info = parseEdlPackageInfo(dir.getAbsolutePath());
        return info != null && info.rawprogramFiles != null && !info.rawprogramFiles.isEmpty();
    }

    private void maybePrepareSuperImage(File runDir, QfilInputs inputs) {
        if (runDir == null || inputs == null) {
            return;
        }
        File imagesDir = inputs.imageDir != null ? inputs.imageDir : inputs.baseDir;
        File baseDir = inputs.baseDir != null ? inputs.baseDir : imagesDir;
        if (imagesDir == null) {
            return;
        }
        if (!ensureSuperImage(runDir, baseDir, imagesDir)) {
            appendWorkLog(runDir, "未生成 super.img，将尝试继续刷写");
        }
    }

    private String pickDefaultXmlPath(List<File> files, Pattern pattern) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        File best = null;
        int bestIndex = Integer.MAX_VALUE;
        for (File file : files) {
            if (file == null) {
                continue;
            }
            int index = parseXmlIndex(file.getName(), pattern);
            if (best == null || index < bestIndex) {
                best = file;
                bestIndex = index;
            }
        }
        return best != null ? best.getAbsolutePath() : "";
    }

    private Map<Integer, File> buildPatchIndexMap(List<File> patches) {
        Map<Integer, File> map = new HashMap<>();
        if (patches == null) {
            return map;
        }
        for (File patch : patches) {
            if (patch == null) {
                continue;
            }
            int index = parseXmlIndex(patch.getName(), PATCH_FILE_PATTERN);
            map.put(index, patch);
        }
        return map;
    }

    private List<ProgramEntry> parseRawprogramPrograms(File rawprogram) {
        List<ProgramEntry> entries = new ArrayList<>();
        if (rawprogram == null || !rootExists(rawprogram.getAbsolutePath(), false)) {
            return entries;
        }
        byte[] xml = rootReadBytes(rawprogram.getAbsolutePath());
        if (xml == null || xml.length == 0) {
            return entries;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml));
            NodeList list = doc.getElementsByTagName("program");
            for (int i = 0; i < list.getLength(); i++) {
                Node node = list.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                Element element = (Element) node;
                NamedNodeMap attrs = element.getAttributes();
                Map<String, String> map = new HashMap<>();
                String label = null;
                String filename = null;
                for (int j = 0; j < attrs.getLength(); j++) {
                    Node attr = attrs.item(j);
                    if (attr == null) {
                        continue;
                    }
                    String name = attr.getNodeName();
                    String value = attr.getNodeValue();
                    map.put(name, value);
                    if ("label".equalsIgnoreCase(name)) {
                        label = value;
                    } else if ("filename".equalsIgnoreCase(name)) {
                        filename = value;
                    }
                }
                entries.add(new ProgramEntry(map, label, filename));
            }
        } catch (IOException | ParserConfigurationException | SAXException | RuntimeException e) {
            entries.clear();
        }
        return entries;
    }

    // 解析 QFIL 输入（已解包目录或手选 rawprogram*.xml）为待刷分区预览 JSON，供前端列出
    // 每个 XML 及其分区并勾选裁剪。仅解析不刷写；读不到 rawprogram（如未解包的加密整包）时
    // 返回空 xmls，前端回退现有自动识别刷写。
    public String parseQfilPreviewJson() {
        List<File> rawFiles = new ArrayList<>();
        File imageDir = null;
        if (input != null && input.arg1Paths != null && !input.arg1Paths.isEmpty()) {
            for (String p : input.arg1Paths) {
                if (p != null && !p.trim().isEmpty()) {
                    rawFiles.add(new File(p.trim()));
                }
            }
            if (!rawFiles.isEmpty()) {
                imageDir = rawFiles.get(0).getParentFile();
            }
        } else if (edlPackageUri != null) {
            // 与刷写侧共用同一解析+按需解包逻辑(DRY)：普通已解包目录直接取 rawprogram；
            // 加密整包(.ofp/.ops)先解包再解析，否则预览恒空(最常见 OPlus 场景无法逐分区勾选)。
            File previewDir = new File(getFilesDir(), "work/preview_" + System.currentTimeMillis());
            previewDir.mkdirs();
            QfilInputs pkg = prepareQfilInputs(previewDir, resolveQfilInputs("", "", ""));
            if (pkg != null && pkg.rawprogramFiles != null && !pkg.rawprogramFiles.isEmpty()) {
                rawFiles.addAll(pkg.rawprogramFiles);
                imageDir = pkg.imageDir != null ? pkg.imageDir : pkg.baseDir;
            }
        }
        rawFiles = dedupeRawprogramVariants(filterLun5XmlFiles(rawFiles, RAWPROGRAM_FILE_PATTERN));
        Collections.sort(rawFiles, (a, b) -> {
            int ia = parseXmlIndex(a.getName(), RAWPROGRAM_FILE_PATTERN);
            int ib = parseXmlIndex(b.getName(), RAWPROGRAM_FILE_PATTERN);
            return ia != ib ? Integer.compare(ia, ib) : a.getName().compareToIgnoreCase(b.getName());
        });
        JSONObject result = new JSONObject();
        JSONArray xmls = new JSONArray();
        try {
            for (File raw : rawFiles) {
                if (raw == null || !rootExists(raw.getAbsolutePath(), false)) {
                    continue;
                }
                JSONArray parts = new JSONArray();
                for (ProgramEntry entry : parseRawprogramPrograms(raw)) {
                    JSONObject p = buildPreviewPartition(entry, imageDir, raw.getParentFile());
                    if (p != null) {
                        parts.put(p);
                    }
                }
                if (parts.length() > 0) {
                    JSONObject xmlObj = new JSONObject();
                    xmlObj.put("name", raw.getName());
                    xmlObj.put("partitions", parts);
                    xmls.put(xmlObj);
                }
            }
            result.put("xmls", xmls);
        } catch (JSONException e) {
            return "{\"xmls\":[]}";
        }
        return result.toString();
    }

    private JSONObject buildPreviewPartition(ProgramEntry entry, File imageDir, File rawprogramDir) throws JSONException {
        if (entry == null) {
            return null;
        }
        String filename = entry.filename == null ? "" : entry.filename.trim();
        String label = resolveProgramLabel(entry);
        // 跳过无镜像布局项(disk/空)与 GPT 表项，它们不是可独立勾选的待刷分区
        String fnLower = filename.toLowerCase(Locale.US);
        if (filename.isEmpty() || "disk".equalsIgnoreCase(filename)
                || isGptMetaEntry(label)
                || fnLower.startsWith("gpt_main") || fnLower.startsWith("gpt_backup")) {
            return null;
        }
        Map<String, String> attrs = entry.attrs;
        String lun = attrValueIgnoreCase(attrs, "physical_partition_number", "0");
        String startSector = attrValueIgnoreCase(attrs, "start_sector", "");
        JSONObject p = new JSONObject();
        p.put("name", label);
        p.put("lun", lun);
        p.put("startSector", startSector);
        p.put("numSectors", attrValueIgnoreCase(attrs, "num_partition_sectors", ""));
        p.put("sectorSize", attrValueIgnoreCase(attrs, "SECTOR_SIZE_IN_BYTES", ""));
        p.put("filename", filename);
        // 稳定唯一标识(与枚举无关)：lun:start_sector:filename。super 分片 start_sector 各异、
        // foo.img/foo.bin filename 各异，故同 XML 内归一化后同名的多 program 不会再共用一个 key。
        p.put("uid", qfilProgramUid(lun, startSector, filename));
        p.put("sparse", "true".equalsIgnoreCase(attrValueIgnoreCase(attrs, "sparse", "")));
        // 与真实刷写一致地解析镜像(支持 .img/.bin 互换、imageDir/rawprogram 目录)，避免假阴性
        File img = resolveProgramImageFile(filename, imageDir, rawprogramDir);
        boolean exists = img != null && rootExists(img.getAbsolutePath(), false);
        // super 由刷写期 ensureSuperImage 从 super.N.img 分片/super_def.json 合并生成，预览期尚未合并；
        // 只要可合并即视为可获得，否则会被前端按"缺图"默认取消勾选而漏刷 super，致设备不开机。
        if (!exists && "super".equalsIgnoreCase(label) && isMergeSuperEnabled()
                && superImageBuildable(imageDir, rawprogramDir)) {
            exists = true;
            p.put("willMerge", true);
        }
        p.put("exists", exists);
        return p;
    }

    // super.img 是否可由刷写期 ensureSuperImage 合并产出(super.N.img 分片或 super_def.json)，判据同源。
    private boolean superImageBuildable(File imageDir, File rawprogramDir) {
        if (!findSuperSegmentImages(rawprogramDir, imageDir).isEmpty()) {
            return true;
        }
        return findSuperDefJson(rawprogramDir) != null
                || (imageDir != null && findSuperDefJson(imageDir) != null);
    }

    private String attrValueIgnoreCase(Map<String, String> attrs, String key, String fallback) {
        if (attrs != null) {
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                    return e.getValue() != null ? e.getValue() : fallback;
                }
            }
        }
        return fallback;
    }

    private String resolveProgramLabel(ProgramEntry entry) {
        return entry == null ? "partition" : normalizeQfilLabel(entry.label, entry.filename);
    }

    // 大小写不敏感地从 ProgramEntry.attrs 取属性(不同刷机包属性名大小写可能不一致)
    private String getEntryAttr(ProgramEntry entry, String name) {
        if (entry == null || entry.attrs == null || name == null) {
            return "";
        }
        String direct = entry.attrs.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : entry.attrs.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) {
                return e.getValue() == null ? "" : e.getValue();
            }
        }
        return "";
    }

    // 规范化分区名：优先 label，回退 filename，去掉 .img/.bin 扩展名（与前端预览/skip 匹配一致）
    private String normalizeQfilLabel(String label, String filename) {
        boolean fromFilename = label == null || label.trim().isEmpty();
        String name = fromFilename ? filename : label;
        if (name == null || name.trim().isEmpty()) {
            return "partition";
        }
        name = name.trim();
        // 由 filename 兜底时取 basename（去掉子目录），label 本身就是分区名不含路径
        if (fromFilename) {
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
        }
        // 大小写不敏感地去掉镜像扩展名
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".bin") || lower.endsWith(".img")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isEmpty() ? "partition" : name;
    }

    private File resolveProgramImageFile(String filename, File imageDir, File rawprogramDir) {
        if (filename == null || filename.trim().isEmpty()) {
            return null;
        }
        String name = filename.trim();
        if (name.startsWith("/")) {
            return new File(name);
        }
        File base = imageDir != null ? imageDir : rawprogramDir;
        File candidate = base == null ? new File(name) : new File(base, name);
        if (rootExists(candidate.getAbsolutePath(), false)) {
            return candidate;
        }
        String lower = name.toLowerCase(Locale.US);
        String alt = null;
        if (lower.endsWith(".img")) {
            alt = name.substring(0, name.length() - 4) + ".bin";
        } else if (lower.endsWith(".bin")) {
            alt = name.substring(0, name.length() - 4) + ".img";
        }
        if (alt != null) {
            File altFile = base == null ? new File(alt) : new File(base, alt);
            if (rootExists(altFile.getAbsolutePath(), false)) {
                return altFile;
            }
        }
        return candidate;
    }

    private boolean ensureSuperImage(File runDir, File baseDir, File imagesDir) {
        if (imagesDir == null) {
            return false;
        }
        File superImg = new File(imagesDir, "super.img");
        if (rootFileSize(superImg.getAbsolutePath()) <= 0
                && baseDir != null && !sameDir(baseDir, imagesDir)) {
            File alt = new File(baseDir, "super.img");
            if (rootFileSize(alt.getAbsolutePath()) > 0) {
                // root 拷贝（两端均可能在 /storage）
                String cmd = "cp -f " + shQuote(alt.getAbsolutePath()) + " "
                        + shQuote(superImg.getAbsolutePath());
                try {
                    runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
                } catch (IOException | InterruptedException e) {
                    appendWorkLog(runDir, "复制 super.img 失败: " + e.getMessage());
                }
            }
        }
        if (rootFileSize(superImg.getAbsolutePath()) > 0) {
            return true;
        }
        // 缺现成 super.img 时是否合并/构建，由选项控制（对齐 oplus 工具的『合并 super』开关）
        if (!isMergeSuperEnabled()) {
            appendWorkLog(runDir, "未启用『合并 super』，跳过分片/组件合并（如需请在选项中开启）");
            return false;
        }
        if (mergeSuperSegments(runDir, baseDir, imagesDir)) {
            return rootFileSize(superImg.getAbsolutePath()) > 0;
        }
        File jsonFile = findSuperDefJson(baseDir);
        if (jsonFile != null && buildSuperFromDef(runDir, jsonFile, baseDir, imagesDir)) {
            return rootFileSize(superImg.getAbsolutePath()) > 0;
        }
        return false;
    }

    private boolean mergeSuperSegments(File runDir, File baseDir, File imagesDir) {
        List<File> segments = findSuperSegmentImages(baseDir, imagesDir);
        if (segments.isEmpty()) {
            return false;
        }
        File output = new File(imagesDir, "super.img");
        // 合并写入 app 工作目录（可写），分片用 root 流读取，最后用 root cp 落到目标目录。
        File localOut = new File(runDir, "merged_super.img");
        appendWorkLog(runDir, "发现 super 分片，开始合并...");
        boolean ok;
        if (shouldOverlaySuperSegments(segments)) {
            // 全尺寸 sparse 分片：每片仅自身区段含真实数据、其余为 DONT_CARE 空洞。须叠加到同一
            // 偏移 0（对齐 AOSP simg2img 对每个输入 lseek(out,0) 后展开、DONT_CARE 用 lseek 跳过以
            // 保留其它分片已写数据），而非顺序拼接——拼接会得到 N×super 大小且各段落在错误偏移的损坏镜像。
            appendWorkLog(runDir, "检测到全尺寸 sparse 分片，按叠加方式合并");
            ok = overlaySuperSegments(runDir, segments, localOut);
        } else {
            // 单分片或连续切片(各片展开大小不同)：按顺序展开拼接。
            ok = concatSuperSegments(runDir, segments, localOut);
        }
        if (!ok || localOut.length() <= 0) {
            return false;
        }
        // root cp 到（可能位于 /storage 的）目标镜像目录
        String cmd = "cp -f " + shQuote(localOut.getAbsolutePath()) + " " + shQuote(output.getAbsolutePath());
        try {
            CommandResult r = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            if (r == null || r.exitCode != 0) {
                appendWorkLog(runDir, "写入 super.img 失败");
                return false;
            }
        } catch (IOException | InterruptedException e) {
            appendWorkLog(runDir, "写入 super.img 失败: " + e.getMessage());
            return false;
        }
        long localLen = localOut.length();
        localOut.delete();
        return rootFileSize(output.getAbsolutePath()) == localLen;
    }

    // 顺序展开拼接：适用于单分片或连续切片(每片覆盖 raw super 的不同连续区段)。
    private boolean concatSuperSegments(File runDir, List<File> segments, File localOut) {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(localOut))) {
            for (File segment : segments) {
                appendWorkLog(runDir, "合并: " + segment.getName());
                InputStream in = rootOpenStream(segment.getAbsolutePath());
                if (in == null || !appendStreamToOutput(in, out)) {
                    appendWorkLog(runDir, "合并失败: " + segment.getName());
                    return false;
                }
            }
        } catch (IOException e) {
            appendWorkLog(runDir, "合并 super 失败: " + e.getMessage());
            return false;
        }
        return true;
    }

    // 叠加合并：每片 seek 到 0 后展开，RAW/FILL 在当前位置写入、DONT_CARE 仅 seek 前进(保留其它
    // 分片已写数据)，最后把文件补齐到完整 super 大小(对齐 simg2img write_normal_end_chunk)。
    private boolean overlaySuperSegments(File runDir, List<File> segments, File localOut) {
        long fullSize = 0;
        try (RandomAccessFile raf = new RandomAccessFile(localOut, "rw")) {
            raf.setLength(0);
            for (File segment : segments) {
                appendWorkLog(runDir, "叠加合并: " + segment.getName());
                InputStream raw = rootOpenStream(segment.getAbsolutePath());
                if (raw == null) {
                    appendWorkLog(runDir, "合并失败: " + segment.getName());
                    return false;
                }
                try (BufferedInputStream in = new BufferedInputStream(raw)) {
                    if (readIntLE(in) != SPARSE_HEADER_MAGIC) {
                        appendWorkLog(runDir, "分片不是 sparse，无法叠加: " + segment.getName());
                        return false;
                    }
                    raf.seek(0);
                    long segFull = expandSparseToRandomAccess(in, raf);
                    if (segFull < 0) {
                        appendWorkLog(runDir, "合并失败: " + segment.getName());
                        return false;
                    }
                    fullSize = Math.max(fullSize, segFull);
                }
            }
            if (raf.length() < fullSize) {
                raf.setLength(fullSize);
            }
        } catch (IOException e) {
            appendWorkLog(runDir, "叠加合并 super 失败: " + e.getMessage());
            return false;
        }
        return localOut.length() > 0;
    }

    // 把已读掉魔数的 sparse 流展开到可随机定位的输出(叠加模型)：DONT_CARE 仅前进不写零，
    // 返回该分片展开后的完整大小(totalBlocks*blockSize)，出错返回 -1。
    private long expandSparseToRandomAccess(InputStream in, RandomAccessFile raf) throws IOException {
        int major = readShortLE(in);
        readShortLE(in); // minor
        int fileHdrSz = readShortLE(in);
        int chunkHdrSz = readShortLE(in);
        int blockSize = readIntLE(in);
        long totalBlocks = readIntLE(in) & 0xffffffffL;
        long totalChunks = readIntLE(in) & 0xffffffffL;
        readIntLE(in); // checksum
        if (fileHdrSz > 28) {
            skipFully(in, fileHdrSz - 28);
        }
        if (major != 1 || blockSize <= 0 || chunkHdrSz < 12) {
            return -1;
        }
        byte[] fillPattern = new byte[4];
        byte[] buffer = new byte[8192];
        long writtenBlocks = 0;
        for (long c = 0; c < totalChunks; c++) {
            int chunkType = readShortLE(in) & 0xffff;
            readShortLE(in); // reserved
            long chunkBlocks = readIntLE(in) & 0xffffffffL;
            long totalSize = readIntLE(in) & 0xffffffffL;
            long dataBytes = chunkBlocks * (long) blockSize;
            // CRC32 块不携带数据块、不计入块对账(对齐 AOSP libsparse / bkerler sparse.py 的 CRC 返回 0 块)
            if (chunkType != SPARSE_CHUNK_TYPE_CRC32) {
                writtenBlocks += chunkBlocks;
            }
            if (chunkHdrSz > 12) {
                skipFully(in, chunkHdrSz - 12);
            }
            if (chunkType == SPARSE_CHUNK_TYPE_RAW) {
                if (totalSize != (long) chunkHdrSz + dataBytes) {
                    return -1;
                }
                long remaining = dataBytes;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = in.read(buffer, 0, toRead);
                    if (read <= 0) {
                        return -1;
                    }
                    raf.write(buffer, 0, read);
                    remaining -= read;
                }
            } else if (chunkType == SPARSE_CHUNK_TYPE_FILL) {
                if (totalSize != (long) chunkHdrSz + fillPattern.length) {
                    return -1;
                }
                readExactly(in, fillPattern, 0, fillPattern.length);
                writeFillToRandomAccess(raf, fillPattern, dataBytes);
            } else if (chunkType == SPARSE_CHUNK_TYPE_DONT_CARE) {
                if (totalSize != chunkHdrSz) {
                    return -1;
                }
                raf.seek(raf.getFilePointer() + dataBytes);
            } else if (chunkType == SPARSE_CHUNK_TYPE_CRC32) {
                if (totalSize > chunkHdrSz) {
                    skipFully(in, totalSize - chunkHdrSz);
                }
            } else {
                return -1;
            }
        }
        // 累计块数必须等于头声明的 total_blks，否则分片损坏/截断
        if (writtenBlocks != totalBlocks) {
            return -1;
        }
        return totalBlocks * (long) blockSize;
    }

    private void writeFillToRandomAccess(RandomAccessFile raf, byte[] pattern, long total)
            throws IOException {
        if (total <= 0) {
            return;
        }
        byte[] buffer = new byte[8192];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = pattern[i % pattern.length];
        }
        long remaining = total;
        while (remaining > 0) {
            int toWrite = (int) Math.min(buffer.length, remaining);
            raf.write(buffer, 0, toWrite);
            remaining -= toWrite;
        }
    }

    // 多个 super 分片是否为"全尺寸 sparse"(叠加模型)：>=2 片且每片均为 sparse、展开后大小一致。
    // 否则按拼接处理(单片或各片大小不同的连续切片)。
    private boolean shouldOverlaySuperSegments(List<File> segments) {
        if (segments.size() < 2) {
            return false;
        }
        long full = -1;
        for (File seg : segments) {
            long size = peekSparseExpandedSize(seg);
            if (size <= 0) {
                return false;
            }
            if (full < 0) {
                full = size;
            } else if (size != full) {
                return false;
            }
        }
        return true;
    }

    // 读取 sparse 头返回展开后完整大小(totalBlocks*blockSize)；非 sparse 或读取失败返回 -1。
    private long peekSparseExpandedSize(File segment) {
        InputStream raw = rootOpenStream(segment.getAbsolutePath());
        if (raw == null) {
            return -1;
        }
        try (BufferedInputStream in = new BufferedInputStream(raw)) {
            if (readIntLE(in) != SPARSE_HEADER_MAGIC) {
                return -1;
            }
            readShortLE(in); // major
            readShortLE(in); // minor
            readShortLE(in); // fileHdrSz
            readShortLE(in); // chunkHdrSz
            int blockSize = readIntLE(in);
            long totalBlocks = readIntLE(in) & 0xffffffffL;
            if (blockSize <= 0 || totalBlocks <= 0) {
                return -1;
            }
            return totalBlocks * (long) blockSize;
        } catch (IOException e) {
            return -1;
        }
    }

    private List<File> findSuperSegmentImages(File baseDir, File imagesDir) {
        List<File> matches = new ArrayList<>();
        collectSuperSegmentImages(imagesDir, matches);
        if (baseDir != null && (imagesDir == null || !sameDir(baseDir, imagesDir))) {
            collectSuperSegmentImages(baseDir, matches);
        }
        matches.sort((a, b) -> {
            int idxA = parseSuperSegmentIndex(a.getName());
            int idxB = parseSuperSegmentIndex(b.getName());
            return Integer.compare(idxA, idxB);
        });
        return matches;
    }

    private void collectSuperSegmentImages(File dir, List<File> out) {
        if (dir == null || !rootExists(dir.getAbsolutePath(), true)) {
            return;
        }
        for (String name : rootListNames(dir.getAbsolutePath())) {
            if (name != null && SUPER_SEGMENT_PATTERN.matcher(name).matches()) {
                out.add(new File(dir, name));
            }
        }
    }

    private int parseSuperSegmentIndex(String name) {
        if (name == null) {
            return Integer.MAX_VALUE;
        }
        Matcher matcher = SUPER_SEGMENT_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    // 把一个镜像流（可为 root cat 流）追加写入 out，自动识别并展开 sparse。
    private boolean appendStreamToOutput(InputStream rawIn, OutputStream out) {
        if (rawIn == null || out == null) {
            return false;
        }
        try (BufferedInputStream in = new BufferedInputStream(rawIn)) {
            in.mark(64);
            int magic = readIntLE(in);
            if (magic != SPARSE_HEADER_MAGIC) {
                in.reset();
                copyStream(in, out);
                return true;
            }
            return writeSparseToStream(in, out);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean writeSparseToStream(InputStream in, OutputStream out) throws IOException {
        int major = readShortLE(in);
        int minor = readShortLE(in);
        int fileHdrSz = readShortLE(in);
        int chunkHdrSz = readShortLE(in);
        int blockSize = readIntLE(in);
        long totalBlocks = readIntLE(in) & 0xffffffffL;
        long totalChunks = readIntLE(in) & 0xffffffffL;
        readIntLE(in); // checksum
        if (fileHdrSz > 28) {
            skipFully(in, fileHdrSz - 28);
        }
        if (major != 1 || blockSize <= 0 || chunkHdrSz < 12) {
            return false;
        }
        byte[] fillPattern = new byte[4];
        long writtenBlocks = 0;
        for (long i = 0; i < totalChunks; i++) {
            int chunkType = readShortLE(in) & 0xffff;
            readShortLE(in); // reserved
            long chunkBlocks = readIntLE(in) & 0xffffffffL;
            long totalSize = readIntLE(in) & 0xffffffffL;
            long dataBytes = chunkBlocks * (long) blockSize;
            // CRC32 块不携带数据块、不计入块对账(对齐 AOSP libsparse / bkerler sparse.py 的 CRC 返回 0 块)
            if (chunkType != SPARSE_CHUNK_TYPE_CRC32) {
                writtenBlocks += chunkBlocks;
            }
            if (chunkHdrSz > 12) {
                skipFully(in, chunkHdrSz - 12);
            }
            // 校验 total_sz 与声明的数据量是否一致，不符即判定镜像损坏（对齐 qdl sparse.c）
            if (chunkType == SPARSE_CHUNK_TYPE_RAW) {
                if (totalSize != (long) chunkHdrSz + dataBytes) {
                    return false;
                }
                if (!copyFixedBytes(in, out, dataBytes)) {
                    return false;
                }
            } else if (chunkType == SPARSE_CHUNK_TYPE_FILL) {
                if (totalSize != (long) chunkHdrSz + fillPattern.length) {
                    return false;
                }
                readExactly(in, fillPattern, 0, fillPattern.length);
                writePattern(out, fillPattern, dataBytes);
            } else if (chunkType == SPARSE_CHUNK_TYPE_DONT_CARE) {
                if (totalSize != chunkHdrSz) {
                    return false;
                }
                writePattern(out, new byte[4], dataBytes);
            } else if (chunkType == SPARSE_CHUNK_TYPE_CRC32) {
                if (totalSize > chunkHdrSz) {
                    skipFully(in, totalSize - chunkHdrSz);
                }
            } else {
                return false;
            }
        }
        // 累计块数必须等于头声明的 total_blks，否则镜像损坏/截断（对齐 sparse.py/qdl sparse.c 对账）
        if (writtenBlocks != totalBlocks) {
            return false;
        }
        return true;
    }

    private boolean copyFixedBytes(InputStream in, OutputStream out, long bytes) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = bytes;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, toRead);
            if (read <= 0) {
                return false;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return true;
    }

    private void writePattern(OutputStream out, byte[] pattern, long total) throws IOException {
        if (total <= 0) {
            return;
        }
        byte[] buffer = new byte[8192];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = pattern[i % pattern.length];
        }
        long remaining = total;
        while (remaining > 0) {
            int toWrite = (int) Math.min(buffer.length, remaining);
            out.write(buffer, 0, toWrite);
            remaining -= toWrite;
        }
    }

    private int readIntLE(InputStream in) throws IOException {
        byte[] buf = new byte[4];
        readExactly(in, buf, 0, buf.length);
        return readIntLE(buf, 0);
    }

    private int readIntLE(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 4 > data.length) {
            return 0;
        }
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private int readShortLE(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 < 0 || b2 < 0) {
            throw new IOException("Unexpected EOF");
        }
        return (b1 & 0xff) | ((b2 & 0xff) << 8);
    }

    private void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("Unexpected EOF");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private boolean sameDir(File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.equals(b);
        }
    }

    private File findSuperDefJson(File baseDir) {
        if (baseDir == null) {
            return null;
        }
        File metaDir = new File(baseDir, "META");
        File candidate = findSuperDefJsonInDir(metaDir);
        if (candidate == null) {
            metaDir = new File(baseDir, "meta");
            candidate = findSuperDefJsonInDir(metaDir);
        }
        if (candidate != null) {
            return candidate;
        }
        return findSuperDefJsonInDir(baseDir);
    }

    private File findSuperDefJsonInDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles((d, name) -> name != null
                && name.toLowerCase(Locale.US).startsWith("super_def.")
                && name.toLowerCase(Locale.US).endsWith(".json"));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return files[files.length - 1];
    }

    private boolean buildSuperFromDef(File runDir, File jsonFile, File baseDir, File imagesDir) {
        if (jsonFile == null || imagesDir == null) {
            return false;
        }
        SuperDefConfig config = parseSuperDefJson(jsonFile);
        if (config == null || config.partitions.isEmpty()) {
            appendWorkLog(runDir, "super_def 解析失败");
            return false;
        }
        File lpmake = resolveLpmakeTool(imagesDir);
        if (lpmake == null) {
            appendWorkLog(runDir, "缺少 lpmake，无法生成 super.img");
            return false;
        }
        List<File> rawFiles = new ArrayList<>();
        // 声明了 size 且声明了 path 的分区，其镜像必须成功纳入；缺图或转换失败时不能静默跳过，
        // 否则 lpmake 仍会成功产出"缺该分区"的损坏 super.img(对齐 OPLUS super_image_creater：缺图即失败)。
        boolean missingDeclaredImage = false;
        for (SuperPartition part : config.partitions) {
            // size 空的占位分区在第二轮以 none:0 登记，此处无需镜像，跳过避免无意义 simg2img 与噪声日志
            if (part.size == null || part.size.isEmpty()) {
                continue;
            }
            if (part.path == null || part.path.isEmpty()) {
                continue;
            }
            File img = new File(baseDir, part.path.replace("/", File.separator));
            if (!img.exists()) {
                appendWorkLog(runDir, "缺少分区镜像: " + part.path);
                missingDeclaredImage = true;
                continue;
            }
            File raw = new File(imagesDir, part.name + ".raw");
            if (!convertSparseToRaw(img, raw)) {
                appendWorkLog(runDir, "转换失败: " + img.getName());
                if (raw.exists()) {
                    raw.delete();
                }
                missingDeclaredImage = true;
                continue;
            }
            part.rawFile = raw;
            rawFiles.add(raw);
        }
        if (missingDeclaredImage) {
            appendWorkLog(runDir, "声明的动态分区镜像缺失或转换失败，终止 super 构建以免产出残缺镜像");
            recordErrorReason("super 分区镜像缺失");
            for (File raw : rawFiles) {
                if (raw != null && raw.exists()) {
                    raw.delete();
                }
            }
            return false;
        }
        if (rawFiles.isEmpty()) {
            appendWorkLog(runDir, "未生成 super 原始镜像");
            return false;
        }
        File output = new File(imagesDir, "super.img");
        List<String> args = new ArrayList<>();
        if (config.metaSize != null && !config.metaSize.isEmpty()) {
            args.add("--metadata-size");
            args.add(config.metaSize);
        }
        args.add("--metadata-slots");
        args.add(resolveSuperMetadataSlots(jsonFile, config));
        // 以下均仅在 super_def.json 显式提供时透传，缺省沿用 lpmake 默认(4096/1MiB/super)，不引入启发式：
        // --super-name 指定承载元数据的块设备名(块设备名非 "super" 或多设备时不指定会找不到元数据设备)
        if (config.superName != null && !config.superName.isEmpty()) {
            args.add("--super-name");
            args.add(config.superName);
        }
        // 非默认几何(block_size/alignment)必须透传，否则 lpmake 用默认值会导致内部 extent 错位
        if (config.blockSize != null && !config.blockSize.isEmpty()) {
            args.add("--block-size");
            args.add(config.blockSize);
        }
        if (config.alignment != null && !config.alignment.isEmpty()) {
            args.add("--alignment");
            args.add(config.alignment);
        }
        // 虚拟 A/B(VABC)包：元数据需带 VIRTUAL_AB 标志。OPLUS super_def 不含 virtual_ab 字段，故显式
        // 给出则尊重，否则按 _a/_b 组/分区名推导(现代高通含 SM8650 均为虚拟 A/B，对齐 super_image_creater)
        if (config.virtualAb || isAbSuperDef(config)) {
            args.add("--virtual-ab");
        }
        for (SuperBlockDevice dev : config.blockDevices) {
            if (dev.name != null && dev.size != null && !dev.name.isEmpty() && !dev.size.isEmpty()) {
                args.add("--device");
                args.add(dev.name + ":" + dev.size);
            }
        }
        for (SuperGroup group : config.groups) {
            if (group.name != null && group.maxSize != null
                    && !group.name.isEmpty() && !group.maxSize.isEmpty()) {
                args.add("--group");
                args.add(group.name + ":" + group.maxSize);
            }
        }
        for (SuperPartition part : config.partitions) {
            if (part == null || part.name == null || part.name.isEmpty()
                    || part.groupName == null || part.groupName.isEmpty()) {
                continue;
            }
            // 空/占位动态分区(无镜像、size 空)：仍须登记进 super 元数据，OTA 才能后续扩容写入；
            // 对齐 OPLUS super_image_creater.rs 的 name:none:0:group（不带 --image），不能直接丢弃
            if (part.size == null || part.size.isEmpty()) {
                args.add("--partition");
                args.add(part.name + ":none:0:" + part.groupName);
                continue;
            }
            if (part.rawFile == null) {
                continue;
            }
            args.add("--partition");
            args.add(part.name + ":readonly:" + part.size + ":" + part.groupName);
            args.add("--image");
            args.add(part.name + "=" + part.rawFile.getAbsolutePath());
        }
        args.add("--output");
        args.add(output.getAbsolutePath());
        String cmdLine = shQuote(lpmake.getAbsolutePath()) + " " + joinArgs(args);
        try {
            CommandResult result = runCommandWithRoot(imagesDir, cmdLine, false, getRootEdlBinDir());
            if (result.exitCode != 0 || !output.exists()) {
                appendWorkLog(runDir, "lpmake 生成 super.img 失败");
                return false;
            }
        } catch (IOException | InterruptedException e) {
            appendWorkLog(runDir, "lpmake 执行失败: " + e.getMessage());
            return false;
        } finally {
            for (File raw : rawFiles) {
                if (raw != null && raw.exists()) {
                    raw.delete();
                }
            }
        }
        return output.exists() && output.length() > 0;
    }

    // 元数据槽数：优先用 super_def.json 的 super_meta.slot_number；否则按 A/B 组/分区名推导
    // （虚拟 A/B 需 3、非 A/B 用 2，对齐 AOSP build_super_image.py，旧版固定 2 会令虚拟 A/B 几何错误）
    private String resolveSuperMetadataSlots(File jsonFile, SuperDefConfig config) {
        String text = jsonFile == null ? null : readFileText(jsonFile);
        if (text != null) {
            try {
                JSONObject meta = new JSONObject(text).optJSONObject("super_meta");
                if (meta != null) {
                    int n = meta.optInt("slot_number", 0);
                    if (n <= 0) {
                        n = meta.optInt("metadata_slot_number", 0);
                    }
                    if (n > 0) {
                        return Integer.toString(n);
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        return isAbSuperDef(config) ? "3" : "2";
    }

    // super_def 是否为 A/B(虚拟 A/B)：组名或分区名带 _a/_b 后缀。供 --virtual-ab 与元数据槽数推导复用。
    private boolean isAbSuperDef(SuperDefConfig config) {
        for (SuperGroup grp : config.groups) {
            if (grp.name != null && (grp.name.endsWith("_a") || grp.name.endsWith("_b"))) {
                return true;
            }
        }
        for (SuperPartition part : config.partitions) {
            if (part.name != null && (part.name.endsWith("_a") || part.name.endsWith("_b"))) {
                return true;
            }
        }
        return false;
    }

    // 读镜像前 4 字节判断 sparse 魔数；外部路径（/storage）默认走 root，
    // app 进程的 FileInputStream 会被 scoped storage 拦截。
    private boolean isSparseImage(File file) {
        if (file == null) {
            return false;
        }
        if (rootFileSize(file.getAbsolutePath()) < 4) {
            return false;
        }
        String cmd = "od -An -N4 -tx1 " + shQuote(file.getAbsolutePath()) + " 2>/dev/null";
        try {
            CommandResult r = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            if (r == null || r.output == null) {
                return false;
            }
            String hex = r.output.replaceAll("[^0-9a-fA-F]", "").toLowerCase(Locale.US);
            // SPARSE_HEADER_MAGIC 0xed26ff3a，磁盘小端字节序为 3a ff 26 ed
            return hex.startsWith("3aff26ed");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // Direct partition/sector writes need a raw image; unsparse first so the
    // sparse header/chunks aren't written verbatim (which corrupts the target).
    private File ensureRawImageForFlash(File runDir, File imageFile) {
        if (runDir == null || imageFile == null || !isSparseImage(imageFile)) {
            return imageFile;
        }
        // 先用 root 把外部 sparse 镜像拷进工作目录，再本地转 raw（转换逻辑要在进程内读取）。
        File localSrc = imageFile;
        if (!imageFile.getAbsolutePath().startsWith(getFilesDir().getAbsolutePath())) {
            File copied = copyExternalToWorkViaRoot(imageFile.getAbsolutePath(), runDir,
                    "src_" + sanitizeFileName(imageFile.getName()));
            if (copied != null) {
                localSrc = copied;
            }
        }
        File raw = new File(runDir, "unsparse_" + sanitizeFileName(imageFile.getName()) + ".raw");
        appendWorkLog(runDir, "检测到 sparse 镜像，转换为 raw: " + imageFile.getName());
        if (convertSparseToRaw(localSrc, raw) && raw.exists() && raw.length() > 0) {
            if (localSrc != imageFile && !localSrc.equals(raw)) {
                localSrc.delete();
            }
            return raw;
        }
        appendWorkLog(runDir, "sparse 转 raw 失败，停止刷写（不回退刷原 sparse 以免损坏分区）");
        if (raw.exists()) {
            raw.delete();
        }
        if (localSrc != imageFile) {
            localSrc.delete();
        }
        return null;
    }

    private boolean convertSparseToRaw(File src, File dest) {
        if (src == null || dest == null) {
            return false;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            in.mark(64);
            int magic = readIntLE(in);
            if (magic != SPARSE_HEADER_MAGIC) {
                in.reset();
                copyStream(in, out);
                return true;
            }
            return writeSparseToStream(in, out);
        } catch (IOException e) {
            return false;
        }
    }

    private File resolveLpmakeTool(File imagesDir) {
        File bin = new File(getRootEdlBinDir(), "lpmake");
        if (bin.exists()) {
            return bin;
        }
        File binExe = new File(getRootEdlBinDir(), "lpmake.exe");
        if (binExe.exists()) {
            return binExe;
        }
        if (imagesDir != null) {
            File local = new File(imagesDir, "lpmake");
            if (local.exists()) {
                return local;
            }
            File localExe = new File(imagesDir, "lpmake.exe");
            if (localExe.exists()) {
                return localExe;
            }
        }
        return null;
    }

    private SuperDefConfig parseSuperDefJson(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists()) {
            return null;
        }
        String text = readFileText(jsonFile);
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(text);
            String metaSize = null;
            String blockSize = null;
            String alignment = null;
            boolean virtualAb = false;
            String superName = null;
            JSONObject meta = root.optJSONObject("super_meta");
            if (meta != null) {
                metaSize = meta.optString("size", "65536");
                String bs = meta.optString("block_size", "");
                if (!bs.isEmpty()) {
                    blockSize = bs;
                }
                String al = meta.optString("alignment", "");
                if (!al.isEmpty()) {
                    alignment = al;
                }
                // 兼容 virtual_ab 为布尔或 "1"/"true" 字符串
                virtualAb = meta.optBoolean("virtual_ab", false)
                        || "1".equals(meta.optString("virtual_ab", ""))
                        || "true".equalsIgnoreCase(meta.optString("virtual_ab", ""));
                String sn = meta.optString("super_name", "");
                if (!sn.isEmpty()) {
                    superName = sn;
                }
            }
            List<SuperBlockDevice> devices = new ArrayList<>();
            JSONArray devs = root.optJSONArray("block_devices");
            if (devs != null) {
                for (int i = 0; i < devs.length(); i++) {
                    JSONObject dev = devs.optJSONObject(i);
                    if (dev == null) {
                        continue;
                    }
                    String name = dev.optString("name", "super");
                    String size = dev.optString("size", "");
                    devices.add(new SuperBlockDevice(name, size,
                            dev.optString("block_size", ""), dev.optString("alignment", "")));
                }
            }
            List<SuperGroup> groups = new ArrayList<>();
            JSONArray groupsJson = root.optJSONArray("groups");
            if (groupsJson != null) {
                for (int i = 0; i < groupsJson.length(); i++) {
                    JSONObject grp = groupsJson.optJSONObject(i);
                    if (grp == null) {
                        continue;
                    }
                    String name = grp.optString("name", "");
                    String maxSize = grp.optString("maximum_size", "");
                    groups.add(new SuperGroup(name, maxSize));
                }
            }
            List<SuperPartition> parts = new ArrayList<>();
            JSONArray partsJson = root.optJSONArray("partitions");
            if (partsJson != null) {
                for (int i = 0; i < partsJson.length(); i++) {
                    JSONObject part = partsJson.optJSONObject(i);
                    if (part == null) {
                        continue;
                    }
                    String name = part.optString("name", "");
                    String path = part.optString("path", "");
                    String size = part.optString("size", "");
                    String groupName = part.optString("group_name", "");
                    if (!name.isEmpty()) {
                        parts.add(new SuperPartition(name, path, size, groupName));
                    }
                }
            }
            SuperDefConfig cfg = new SuperDefConfig(metaSize, devices, groups, parts);
            cfg.blockSize = blockSize;
            cfg.alignment = alignment;
            cfg.virtualAb = virtualAb;
            // 全局 --block-size/--alignment：super_meta 显式给出则优先(向后兼容)，否则取首个块设备的几何
            // (OPLUS schema 把几何放在 block_devices[0]，对齐 super_image_creater)，否则恒为空致 lpmake 用默认错位
            if ((cfg.blockSize == null || cfg.blockSize.isEmpty()) && !devices.isEmpty()
                    && devices.get(0).blockSize != null && !devices.get(0).blockSize.isEmpty()) {
                cfg.blockSize = devices.get(0).blockSize;
            }
            if ((cfg.alignment == null || cfg.alignment.isEmpty()) && !devices.isEmpty()
                    && devices.get(0).alignment != null && !devices.get(0).alignment.isEmpty()) {
                cfg.alignment = devices.get(0).alignment;
            }
            // super-name 优先用 super_meta.super_name；否则用首个块设备名(通常即 "super")
            cfg.superName = superName;
            if ((cfg.superName == null || cfg.superName.isEmpty()) && !devices.isEmpty()
                    && devices.get(0).name != null && !devices.get(0).name.isEmpty()) {
                cfg.superName = devices.get(0).name;
            }
            return cfg;
        } catch (JSONException e) {
            return null;
        }
    }

    private boolean isSpecialRawprogramFile(File rawprogram) {
        return resolveSpecialRawprogramLun(rawprogram) >= 0;
    }

    private int resolveSpecialRawprogramLun(File rawprogram) {
        if (rawprogram == null) {
            return -1;
        }
        // 按 LUN 索引识别，兼容 rawprogramN.xml 与 rawprogram_unsparseN.xml(否则只有 unsparse 变体时
        // 会绕过 LUN0 主 GPT 重映射/LUN5 保留等 special 处理)；仅 LUN0/LUN5 需要 special 处理。
        int idx = parseXmlIndex(rawprogram.getName(), RAWPROGRAM_FILE_PATTERN);
        return (idx == 0 || idx == 5) ? idx : -1;
    }

    private boolean isPersistProgram(String label, String filename) {
        String lowerLabel = safeLower(label);
        if ("persist".equals(lowerLabel)) {
            return true;
        }
        if (filename == null) {
            return false;
        }
        String lowerFile = filename.trim().toLowerCase(Locale.US);
        return lowerFile.equals("persist.img") || lowerFile.equals("persist.bin");
    }

    // 从 input.qfilSkip(前端取消勾选的 "xml名#lun:start_sector:filename")构造跳过集；空集表示全刷
    private Set<String> buildQfilSkipSet() {
        Set<String> set = new LinkedHashSet<>();
        if (input != null && input.qfilSkip != null) {
            for (String k : input.qfilSkip) {
                if (k != null && !k.trim().isEmpty()) {
                    set.add(k.trim());
                }
            }
        }
        return set;
    }

    // program 稳定唯一标识：lun:start_sector:filename(lun 缺省 0)。预览与三处 skip 匹配共用此函数，
    // 保证键一致——避免同 XML 内归一化后同名(foo.img/foo.bin、super 各分片)的项被一并误删。
    private String qfilProgramUid(String lun, String startSector, String filename) {
        String l = (lun == null || lun.trim().isEmpty()) ? "0" : lun.trim();
        String s = startSector == null ? "" : startSector.trim();
        String f = filename == null ? "" : filename.trim();
        return l + ":" + s + ":" + f;
    }

    private String qfilSkipKey(String rawprogramName, String lun, String startSector, String filename) {
        return rawprogramName + "#" + qfilProgramUid(lun, startSector, filename);
    }

    // 与 QFIL 预览(buildPreviewPartition)一致的"可刷分区"判定：排除无镜像布局项、disk 与
    // GPT 表项(gpt_main/gpt_backup 或 GPT 元数据 label)。GPT 是分区表基础设施、预览里不可勾选，
    // 故不作为"是否还有待刷内容"的依据；但它仍随被发送的 XML 正常写入(下方刷写循环不排除它)。
    private boolean isFlashableProgramEntry(ProgramEntry entry) {
        if (entry == null) {
            return false;
        }
        String filename = entry.filename == null ? "" : entry.filename.trim();
        if (filename.isEmpty() || "disk".equalsIgnoreCase(filename)) {
            return false;
        }
        String fnLower = filename.toLowerCase(Locale.US);
        if (fnLower.startsWith("gpt_main") || fnLower.startsWith("gpt_backup")) {
            return false;
        }
        return !isGptMetaEntry(resolveProgramLabel(entry));
    }

    // rawprogram 是否含至少一个有镜像可刷的 program；全被 skip/仅剩 GPT 项时返回 false，
    // 调用方应跳过该 XML 而非中止整个 QFIL（避免向 qdl 发空/纯 GPT data，并与预览"将刷 0/N"一致）
    private boolean rawprogramHasFlashable(File xml) {
        if (xml == null || !rootExists(xml.getAbsolutePath(), false)) {
            return false;
        }
        for (ProgramEntry entry : parseRawprogramPrograms(xml)) {
            if (isFlashableProgramEntry(entry)) {
                return true;
            }
        }
        return false;
    }

    private File prepareRawprogramXml(File rawprogram, File imagesDir, File runDir, boolean skipPersist,
                                      Map<String, GptEntry> deviceGpt) {
        if (rawprogram == null || runDir == null) {
            return rawprogram;
        }
        boolean special = isSpecialRawprogramFile(rawprogram);
        Set<String> qfilSkip = buildQfilSkipSet();
        boolean hasSkip = !qfilSkip.isEmpty();
        // 无需 persist/uid 跳过时直接原样返回(纯 verbatim)：含 special(LUN0/5)也直传原始 XML，由 qdl 原生
        // 解析包内 PrimaryGPT/BackupGPT 与 NUM_DISK_SECTORS-N 备份 GPT 寻址，避免 app 侧重建 GPT 算错几何。
        if (!skipPersist && !hasSkip) {
            return rawprogram;
        }
        // deviceGpt 恒空(调用方按 verbatim 传空)：useDeviceGpt=false → special 分支回退读【包内】gpt_main{N}.bin
        // 取真实地址、并照刷包内 GPT，全程以包为权威(与 bkerler/edl、qdl、OplusEdlTool 一致),绝不用设备当前 GPT。
        boolean useDeviceGpt = deviceGpt != null && !deviceGpt.isEmpty();
        int lun = resolveSpecialRawprogramLun(rawprogram);
        int sectorSize = resolveGptSectorSize();
        Map<String, GptEntry> gptMap = new HashMap<>();
        if (useDeviceGpt) {
            gptMap = deviceGpt;
            for (GptEntry entry : deviceGpt.values()) {
                int ss = entry == null ? 0 : parseIntSafe(entry.sectorSize, 0);
                if (ss > 0) {
                    sectorSize = ss;
                    break;
                }
            }
        } else if (special) {
            // 回退：无设备 GPT 时仍尝试用包内 gpt_main 重映射
            File gptFile = null;
            if (imagesDir != null) {
                gptFile = new File(imagesDir, "gpt_main" + lun + ".bin");
            }
            if (gptFile == null || !rootExists(gptFile.getAbsolutePath(), false)) {
                File parent = rawprogram.getParentFile();
                if (parent != null) {
                    gptFile = new File(parent, "gpt_main" + lun + ".bin");
                }
            }
            if (gptFile != null && rootExists(gptFile.getAbsolutePath(), false)) {
                List<GptEntry> gptEntries = parseGptMainFile(gptFile, sectorSize, lun);
                if (!gptEntries.isEmpty()) {
                    sectorSize = parseIntSafe(gptEntries.get(0).sectorSize, sectorSize);
                }
                for (GptEntry entry : gptEntries) {
                    if (entry != null && entry.name != null) {
                        gptMap.put(parseIntSafe(entry.partition, lun) + ":" + entry.name.trim().toLowerCase(Locale.US), entry);
                    }
                }
            }
        }
        try {
            byte[] rawXml = rootReadBytes(rawprogram.getAbsolutePath());
            if (rawXml == null || rawXml.length == 0) {
                return rawprogram;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(rawXml));
            Element data = doc.getDocumentElement();
            if (data == null) {
                return rawprogram;
            }
            NodeList list = data.getElementsByTagName("program");
            List<Element> programs = new ArrayList<>();
            Element gptMainProgram = null;
            Element gptBackupProgram = null;
            for (int i = 0; i < list.getLength(); i++) {
                Node node = list.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                Element program = (Element) node;
                String label = getAttrIgnoreCase(program, "label");
                String filename = getAttrIgnoreCase(program, "filename");
                if (skipPersist && isPersistProgram(label, filename)) {
                    continue;
                }
                // 用户在分区表中取消勾选的分区：从 rawprogram 移除，不刷写(按 uid 精确匹配，
                // start_sector 此处仍是包内原值——重映射在下方，与预览一致)
                if (hasSkip && qfilSkip.contains(qfilSkipKey(rawprogram.getName(),
                        getAttrIgnoreCase(program, "physical_partition_number"),
                        getAttrIgnoreCase(program, "start_sector"), filename))) {
                    continue;
                }
                if (special) {
                    if ("PrimaryGPT".equalsIgnoreCase(label)
                            || ("gpt_main" + lun + ".bin").equalsIgnoreCase(filename)) {
                        gptMainProgram = program;
                        continue;
                    }
                    if ("BackupGPT".equalsIgnoreCase(label)
                            || ("gpt_backup" + lun + ".bin").equalsIgnoreCase(filename)) {
                        gptBackupProgram = program;
                        continue;
                    }
                    GptEntry info = gptMap.get(lun + ":" + safeLower(label));
                    if (info == null) {
                        // label 为空或不匹配时回退按文件名(去扩展名)查表，与预览 resolveProgramLabel 的
                        // filename 回退一致，避免仅给 filename(无 label)的 program 漏重映射
                        String fnKey = safeLower(stripImageExtension(filename));
                        if (!fnKey.isEmpty()) {
                            info = gptMap.get(lun + ":" + fnKey);
                        }
                    }
                    if (info != null) {
                        long start = parseLongSafe(info.startSector, -1L);
                        long num = parseLongSafe(info.numSectors, -1L);
                        if (start >= 0 && num > 0) {
                            // 设备 GPT 为布局权威：包内 start_sector 与设备不一致时按设备地址重映射。
                            // 这正是解除 OPlus "X:Y is unmatch on label(devStart:devNum)" 的手段——OPlus VIP
                            // 为会话级一次性授权(digest/sign 在认证期作裸二进制发送、不逐包校验 XML)，故重映射
                            // 与 VIP 不冲突(实证见 run.log)，与 OplusEdlTool RawProgramXmlProcessor 做法一致。
                            long origStart = parseLongSafe(getAttrIgnoreCase(program, "start_sector"), -1L);
                            if (origStart >= 0 && origStart != start) {
                                cb.onLog("提示: 分区 " + label + " 包内 start_sector=" + origStart
                                        + " 与设备 GPT=" + start + " 不一致，已按设备地址重映射");
                            }
                            setAttr(program, "start_sector", Long.toString(start));
                            setAttr(program, "num_partition_sectors", Long.toString(num));
                            long startByte = start * (long) sectorSize;
                            setAttr(program, "start_byte_hex", "0x" + Long.toHexString(startByte));
                            double sizeKb = (num * (double) sectorSize) / 1024.0;
                            setAttr(program, "size_in_KB", String.format(Locale.US, "%.1f", sizeKb));
                            setAttr(program, "SECTOR_SIZE_IN_BYTES", Integer.toString(sectorSize));
                        }
                    } else if (useDeviceGpt) {
                        // 设备 GPT 权威模式下该分区在设备表无对应：不静默用包内地址盲刷(会触发 is unmatch
                        // 致整次刷写终止)，给出显著告警便于定位(多为固件与机型不符或设备 GPT 部分读取失败)。
                        cb.onLog("警告: 分区 " + label + " 在设备 GPT 中无对应项，start_sector 未重映射，刷写可能 unmatch");
                        appendWorkLog(runDir, "分区 " + label + " 在设备 GPT 无对应，未重映射(包内地址原样保留)");
                    }
                    if ((lun == 0 && ("super".equalsIgnoreCase(label) || "userdata".equalsIgnoreCase(label)))
                            || (lun == 5 && "oplusreserve2".equalsIgnoreCase(label))) {
                        // 按镜像实际格式(sparse 魔数)设 sparse 属性，而非硬编码 false——super 经本工具
                        // 合并为 raw 会判 false；userdata/oplusreserve2 等包内 sparse 容器保持 true，
                        // 避免把 sparse 数据按 raw 刷入损坏分区(maybePrepareSuperImage 只 unsparse super)。
                        String filenameAttr = getAttrIgnoreCase(program, "filename");
                        File imgFile = resolveProgramImageFile(filenameAttr, imagesDir, rawprogram.getParentFile());
                        if (imgFile != null && rootExists(imgFile.getAbsolutePath(), false)) {
                            setAttr(program, "sparse", isSparseImage(imgFile) ? "true" : "false");
                        }
                    }
                }
                programs.add(program);
            }
            if (special) {
                // 用设备真实 GPT 时绝不重刷包内（可能紧凑的）GPT，保留设备出厂分区表
                if (!useDeviceGpt) {
                    if (gptMainProgram == null) {
                        gptMainProgram = doc.createElement("program");
                        setAttr(gptMainProgram, "SECTOR_SIZE_IN_BYTES", Integer.toString(sectorSize));
                        setAttr(gptMainProgram, "file_sector_offset", "0");
                        setAttr(gptMainProgram, "filename", "gpt_main" + lun + ".bin");
                        setAttr(gptMainProgram, "label", "PrimaryGPT");
                        setAttr(gptMainProgram, "num_partition_sectors", "6");
                        setAttr(gptMainProgram, "partofsingleimage", "true");
                        setAttr(gptMainProgram, "physical_partition_number", Integer.toString(lun));
                        setAttr(gptMainProgram, "readbackverify", "false");
                        setAttr(gptMainProgram, "size_in_KB", String.format(Locale.US, "%.1f", (6.0 * sectorSize) / 1024.0));
                        setAttr(gptMainProgram, "sparse", "false");
                        setAttr(gptMainProgram, "start_byte_hex", "0x0");
                        setAttr(gptMainProgram, "start_sector", "0");
                    } else {
                        setAttr(gptMainProgram, "SECTOR_SIZE_IN_BYTES", Integer.toString(sectorSize));
                        setAttr(gptMainProgram, "start_sector", "0");
                        setAttr(gptMainProgram, "start_byte_hex", "0x0");
                        setAttr(gptMainProgram, "num_partition_sectors", "6");
                        setAttr(gptMainProgram, "size_in_KB", String.format(Locale.US, "%.1f", (6.0 * sectorSize) / 1024.0));
                    }
                }
                while (data.hasChildNodes()) {
                    data.removeChild(data.getFirstChild());
                }
                data.appendChild(doc.createComment("NOTE: This is an ** Autogenerated file **"));
                data.appendChild(doc.createComment("NOTE: Sector size is " + sectorSize + "bytes"));
                data.appendChild(doc.createComment("NOTE: Modified by OPLUS EDL Tool for rawprogram" + lun + ".xml"));
                if (!useDeviceGpt && gptMainProgram != null) {
                    data.appendChild(gptMainProgram);
                }
                for (Element program : programs) {
                    data.appendChild(program);
                }
                if (!useDeviceGpt && gptBackupProgram != null) {
                    data.appendChild(gptBackupProgram);
                }
            } else {
                // 非 special：从原 DOM 移除 persist(skipPersist) 与取消勾选(skip) 的 program。
                // 倒序遍历 live NodeList，从末尾删除不影响前面索引。从 program 自身的父节点删除
                // (而非硬编码 data)：getElementsByTagName 返回任意层级后代，若某些刷机包的 program
                // 不是 <data> 的直接子节点，data.removeChild 会抛 NOT_FOUND DOMException 致整次刷写中止。
                for (int i = list.getLength() - 1; i >= 0; i--) {
                    Node node = list.item(i);
                    if (!(node instanceof Element)) {
                        continue;
                    }
                    Element program = (Element) node;
                    String label = getAttrIgnoreCase(program, "label");
                    String filename = getAttrIgnoreCase(program, "filename");
                    if (skipPersist && isPersistProgram(label, filename)) {
                        removeNodeFromParent(program);
                    } else if (hasSkip && qfilSkip.contains(qfilSkipKey(rawprogram.getName(),
                            getAttrIgnoreCase(program, "physical_partition_number"),
                            getAttrIgnoreCase(program, "start_sector"), filename))) {
                        removeNodeFromParent(program);
                    }
                }
            }
            File outFile = new File(runDir, "modified_" + rawprogram.getName());
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.transform(new DOMSource(doc), new StreamResult(outFile));
            return outFile;
        } catch (IOException | ParserConfigurationException | SAXException | TransformerException e) {
            appendWorkLog(runDir, "处理 rawprogram 失败: " + e.getMessage());
            return rawprogram;
        } catch (RuntimeException e) {
            // DOM 操作的运行时异常(如 DOMException)不能让整次刷写崩成"权限不足"；记录后回退原 XML。
            appendWorkLog(runDir, "处理 rawprogram 运行时异常，回退原始 XML: " + e);
            return rawprogram;
        }
    }

    // 从节点自身的父节点安全删除：避免硬编码父节点导致 NOT_FOUND DOMException。
    private void removeNodeFromParent(Node node) {
        if (node == null) {
            return;
        }
        Node parent = node.getParentNode();
        if (parent != null) {
            parent.removeChild(node);
        }
    }

    // 把 OPlus/OPPO MsmDownloadTool 的工程配置(根<Setting>)转换为标准高通 rawprogram。
    // 该格式特征：<program> 分组在 <Program0..N> 下(非<data>直接子节点)，镜像文件名/sparse 标志
    // 在每个 <program> 内嵌的 <Image filename="..." sparse="..."> 里(而非 program 自身属性)。
    // 转换：扁平化所有 <program>，把内嵌 Image 的 filename/sparse 提到 program 属性上，输出
    // <data><program .../></data>。非 Setting 格式或解析失败时原样返回，保持对标准包零影响。
    private File convertOplusSettingXml(File rawprogram, File runDir) {
        if (rawprogram == null || runDir == null || !rootExists(rawprogram.getAbsolutePath(), false)) {
            return rawprogram;
        }
        try {
            byte[] xml = rootReadBytes(rawprogram.getAbsolutePath());
            if (xml == null || xml.length == 0) {
                return rawprogram;
            }
            // 轻量判定:标准高通 rawprogram(绝大多数包)根不是<Setting>,直接原样返回,省一次全量 DOM 解析
            String head = new String(xml, 0, Math.min(xml.length, 1024), StandardCharsets.UTF_8);
            if (!head.contains("<Setting")) {
                return rawprogram;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml));
            Element root = doc.getDocumentElement();
            if (root == null || !"Setting".equalsIgnoreCase(root.getTagName())) {
                return rawprogram;
            }
            Document out = builder.newDocument();
            Element data = out.createElement("data");
            out.appendChild(data);
            NodeList programs = root.getElementsByTagName("program");
            int converted = 0;
            for (int i = 0; i < programs.getLength(); i++) {
                Node node = programs.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                Element program = (Element) node;
                String filename = "";
                String sparse = "";
                NodeList images = program.getElementsByTagName("Image");
                if (images.getLength() > 0 && images.item(0) instanceof Element) {
                    Element img = (Element) images.item(0);
                    filename = getAttrIgnoreCase(img, "filename");
                    sparse = getAttrIgnoreCase(img, "sparse");
                }
                Element np = out.createElement("program");
                NamedNodeMap attrs = program.getAttributes();
                for (int j = 0; j < attrs.getLength(); j++) {
                    Node a = attrs.item(j);
                    if (a == null) {
                        continue;
                    }
                    np.setAttribute(a.getNodeName(), a.getNodeValue() == null ? "" : a.getNodeValue());
                }
                np.setAttribute("filename", filename);
                if (sparse != null && !sparse.isEmpty()) {
                    np.setAttribute("sparse", sparse);
                }
                data.appendChild(np);
                converted++;
            }
            File convDir = new File(runDir, "settingconv");
            if (!convDir.exists()) {
                convDir.mkdirs();
            }
            // 保留原文件名，使下游 parseXmlIndex/resolveSpecialRawprogramLun 的索引/LUN 判定不变
            File outFile = new File(convDir, rawprogram.getName());
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.transform(new DOMSource(out), new StreamResult(outFile));
            appendWorkLog(runDir, "检测到 OPlus <Setting> 工程格式，已转换 " + converted
                    + " 个分区为标准 rawprogram");
            return outFile;
        } catch (Exception e) {
            appendWorkLog(runDir, "Setting 格式转换失败，按原文件处理: " + e);
            return rawprogram;
        }
    }

    // 从 OPlus <Setting> 工程文件提取内联的 <patch> 元素,拼成标准 <data><patch.../></data> 写出,供下游
    // 按 program→patch 顺序应用。这些 patch 用真实磁盘扇区数(NUM_DISK_SECTORS)修正主/备份 GPT 头的
    // LastUsableLBA、CRC 与末分区 userdata 实际大小;.ops 售后包无独立 patch*.xml,patch 全内联在 settings.xml。
    // 非 <Setting> 或无 <patch> 返回 null。属性原样保留(含 NUM_DISK_SECTORS-N,由设备 firehose 解析)。
    private File convertOplusSettingPatch(File settingsXml, File runDir) {
        if (settingsXml == null || runDir == null || !rootExists(settingsXml.getAbsolutePath(), false)) {
            return null;
        }
        try {
            byte[] xml = rootReadBytes(settingsXml.getAbsolutePath());
            if (xml == null || xml.length == 0) {
                return null;
            }
            String head = new String(xml, 0, Math.min(xml.length, 1024), StandardCharsets.UTF_8);
            if (!head.contains("<Setting")) {
                return null;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml));
            Element root = doc.getDocumentElement();
            if (root == null || !"Setting".equalsIgnoreCase(root.getTagName())) {
                return null;
            }
            NodeList patches = root.getElementsByTagName("patch");
            if (patches.getLength() == 0) {
                return null;
            }
            Document out = builder.newDocument();
            Element data = out.createElement("data");
            out.appendChild(data);
            int count = 0;
            for (int i = 0; i < patches.getLength(); i++) {
                Node node = patches.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                Element np = out.createElement("patch");
                NamedNodeMap attrs = ((Element) node).getAttributes();
                for (int j = 0; j < attrs.getLength(); j++) {
                    Node a = attrs.item(j);
                    if (a != null) {
                        np.setAttribute(a.getNodeName(), a.getNodeValue() == null ? "" : a.getNodeValue());
                    }
                }
                data.appendChild(np);
                count++;
            }
            if (count == 0) {
                return null;
            }
            File convDir = new File(runDir, "settingconv");
            if (!convDir.exists()) {
                convDir.mkdirs();
            }
            File outFile = new File(convDir, "patch_" + settingsXml.getName());
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.transform(new DOMSource(out), new StreamResult(outFile));
            appendWorkLog(runDir, "已从 <Setting> 提取 " + count + " 个 patch(修正 GPT 头/CRC/末分区大小)");
            return outFile;
        } catch (Exception e) {
            appendWorkLog(runDir, "提取 <Setting> patch 失败: " + e);
            return null;
        }
    }

    private String getAttrIgnoreCase(Element element, String name) {
        if (element == null || name == null) {
            return "";
        }
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            if (attr == null) {
                continue;
            }
            if (name.equalsIgnoreCase(attr.getNodeName())) {
                String value = attr.getNodeValue();
                return value == null ? "" : value;
            }
        }
        return "";
    }

    private void setAttr(Element element, String name, String value) {
        if (element == null || name == null || value == null) {
            return;
        }
        element.setAttribute(name, value);
    }

    private String validateQfilInputs(String rawprogramPath, String patchPath, String imageDirPath, File runDir) {
        // patch.xml 可选：很多高通刷机包没有 patch（它只写 GPT 备份头/CRC、把末尾分区扩到盘末），
        // 官方 qdl/fh_loader/qdlrs 与 edl 均不强制。这里只要求 rawprogram 与镜像目录。
        if (rawprogramPath == null || rawprogramPath.isEmpty()
                || imageDirPath == null || imageDirPath.isEmpty()) {
            return "QFIL 参数不完整";
        }
        File rawprogram = new File(rawprogramPath);
        File imageDir = new File(imageDirPath);
        if (!rootExists(rawprogram.getAbsolutePath(), false)) {
            return "rawprogram.xml 不存在";
        }
        // 指定了 patch 但文件缺失：仅警告并继续（下游 runFhQfilSplit 会自动跳过 patch 步骤、只刷 rawprogram）
        if (patchPath != null && !patchPath.isEmpty()
                && !rootExists(new File(patchPath).getAbsolutePath(), false)) {
            appendWorkLog(runDir, "警告: 指定的 patch.xml 不存在，将跳过 GPT 备份/扩容修补");
        }
        if (!rootExists(imageDir.getAbsolutePath(), true)) {
            return "镜像目录不存在";
        }
        byte[] rawBytes = rootReadBytes(rawprogram.getAbsolutePath());
        String content = rawBytes == null ? null : new String(rawBytes, StandardCharsets.UTF_8);
        if (content == null || content.isEmpty()) {
            return "rawprogram.xml 读取失败";
        }
        // 与预览/实际刷写共用同一跳过集：用户在分区表取消勾选的分区不参与镜像存在性校验，
        // 否则取消勾选缺图分区后整包会在校验门处秒退(与勾选裁剪语义矛盾)。
        Set<String> qfilSkip = buildQfilSkipSet();
        Matcher tagMatcher = RAWPROGRAM_TAG_PATTERN.matcher(content);
        boolean found = false;
        boolean missingSuper = false;
        Set<String> missingImages = new LinkedHashSet<>();
        while (tagMatcher.find()) {
            found = true;
            String tag = tagMatcher.group();
            Map<String, String> attrs = parseProgramAttributes(tag);
            String rawLabel = attrs.get("label");
            String label = safeLower(rawLabel);
            String filename = attrs.get("filename");
            if (filename == null) {
                filename = "";
            }
            filename = filename.trim();
            if ("disk".equalsIgnoreCase(filename)) {
                continue;
            }
            if (filename.isEmpty()) {
                // filename 为空表示该 program 只是 GPT 布局项、无镜像可刷，
                // 与 qdl(firehose.c 中 !filename 直接 return 0)/edl 一致跳过；super 为空单独标记
                if ("super".equals(label)) {
                    missingSuper = true;
                }
                continue;
            }
            // 用户取消勾选的分区跳过校验(uid 键格式与 prepareRawprogramXml/预览完全一致)
            if (!qfilSkip.isEmpty()
                    && qfilSkip.contains(qfilSkipKey(rawprogram.getName(),
                            attrs.get("physical_partition_number"), attrs.get("start_sector"), filename))) {
                continue;
            }
            // 镜像存在性用 resolveProgramImageFile(含 .img/.bin 互换)，与预览(9293)/刷写(3710)同源，
            // 避免仅扩展名不符时误判"镜像缺失"导致整包秒退
            File imageFile = resolveProgramImageFile(filename, imageDir, rawprogram.getParentFile());
            if (imageFile == null || !rootExists(imageFile.getAbsolutePath(), false)) {
                if ("super".equals(label)) {
                    missingSuper = true;
                    continue;
                }
                if (!label.isEmpty() && QFIL_SKIP_PARTITIONS.contains(label)) {
                    continue;
                }
                missingImages.add(filename);
            }
        }
        if (!found) {
            return "rawprogram.xml 未发现 program 条目";
        }
        if (!missingImages.isEmpty()) {
            return "镜像目录缺少文件: " + String.join(", ", missingImages);
        }
        if (missingSuper) {
            appendWorkLog(runDir, "警告: rawprogram 未包含 super 镜像");
        }
        return null;
    }

    private boolean rawprogramUsesSubdir(List<File> rawprograms) {
        if (rawprograms == null || rawprograms.isEmpty()) {
            return false;
        }
        for (File rawprogram : rawprograms) {
            if (rawprogram == null || !rootExists(rawprogram.getAbsolutePath(), false)) {
                continue;
            }
            byte[] b = rootReadBytes(rawprogram.getAbsolutePath());
            String content = b == null ? null : new String(b, StandardCharsets.UTF_8);
            if (content == null || content.isEmpty()) {
                continue;
            }
            Matcher tagMatcher = RAWPROGRAM_TAG_PATTERN.matcher(content);
            while (tagMatcher.find()) {
                String tag = tagMatcher.group();
                Map<String, String> attrs = parseProgramAttributes(tag);
                String filename = attrs.get("filename");
                if (filename != null && (filename.contains("/") || filename.contains("\\"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, String> parseProgramAttributes(String tag) {
        Map<String, String> attrs = new HashMap<>();
        if (tag == null) {
            return attrs;
        }
        Matcher matcher = RAWPROGRAM_ATTR_PATTERN.matcher(tag);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(3);
            if (key != null && value != null) {
                attrs.put(key.toLowerCase(Locale.US), value);
            }
        }
        return attrs;
    }

    private String safeLower(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.US);
    }

    private String readFileText(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(reader)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException ignored) {
            return null;
        }
    }

    // 外层已把 SAF 解析为真实路径，Uri 垫片直接回传该路径。
    private String resolvePathFromUri(Uri uri, boolean isTree) {
        return uri == null ? null : uri.path;
    }

    private File resolveUriToFile(Uri uri, String label) {
        String path = resolvePathFromUri(uri, false);
        if (path == null || path.isEmpty()) {
            showToast(getString(R.string.error_uri_no_path, label));
            return null;
        }
        File file = new File(path);
        if (!rootExists(path, false)) {
            showToast(getString(R.string.error_file_missing, label));
            return null;
        }
        return file;
    }

    private File copyUriToDir(Uri uri, File dir, String fallbackName) throws IOException {
        String name = getDisplayName(uri);
        if (name == null || name.trim().isEmpty()) {
            name = fallbackName;
        }
        File outFile = new File(dir, name);
        try (InputStream in = new FileInputStream(new File(uri.path));
             OutputStream out = new FileOutputStream(outFile)) {
            copyStream(in, out);
        }
        return outFile;
    }

    private File copyBuiltinLoader(File dir, String assetPath) throws IOException {
        File cached = getCachedBuiltinFile(assetPath);
        // 多镜像引导：assetPath 指向 qsahara_device_programmer.xml，它按相对路径引用
        // 同目录的多个镜像。把同级镜像也缓存到同一目录，qdl 的 decode_sahara_config
        // 以 XML 所在目录为基准解析这些相对路径，缺一不可。
        if (assetPath != null && assetPath.toLowerCase(Locale.US).endsWith(SAHARA_CONFIG_NAME)) {
            cacheSaharaConfigSiblings(assetPath);
        }
        return cached;
    }

    private void cacheSaharaConfigSiblings(String xmlAssetPath) throws IOException {
        int slash = xmlAssetPath.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String folder = xmlAssetPath.substring(0, slash);
        String[] siblings = getAssets().list(folder);
        if (siblings == null) {
            return;
        }
        for (String name : siblings) {
            if (name.equalsIgnoreCase(SAHARA_CONFIG_NAME)) {
                continue;
            }
            String childPath = folder + "/" + name;
            // 跳过子目录（assets.list 对普通文件返回空数组）
            String[] grand = getAssets().list(childPath);
            if (grand != null && grand.length > 0) {
                continue;
            }
            getCachedBuiltinFile(childPath);
        }
    }

    private File getCachedBuiltinFile(String assetPath) throws IOException {
        File baseDir = new File(getRootEdlDir(), "loader_cache");
        File outFile = new File(baseDir, assetPath);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException(getString(R.string.error_create_dir) + parent.getAbsolutePath());
        }
        if (!outFile.exists() || outFile.length() == 0) {
            try (InputStream in = getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(outFile)) {
                copyStream(in, out);
            }
        }
        chmodFile(outFile);
        return outFile;
    }

    private String getDisplayName(Uri uri) {
        if (uri == null || uri.path == null) {
            return "";
        }
        String name = new File(uri.path).getName();
        return name != null ? name : uri.path;
    }

    // 输入目录(uri.path)下的镜像拷贝到 destDir(基于普通文件系统)。
    private int copyImagesFromTree(Uri uri, File destDir) throws IOException {
        File root = new File(uri.path);
        if (!root.isDirectory()) {
            throw new IOException(getString(R.string.error_open_file));
        }
        return copyImagesFromDir(root, destDir);
    }

    private int copyImagesFromDir(File srcDir, File destDir) throws IOException {
        File[] children = srcDir.listFiles();
        if (children == null) {
            return 0;
        }
        int copied = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                copied += copyImagesFromDir(child, destDir);
                continue;
            }
            String lower = child.getName().toLowerCase(Locale.US);
            if (!lower.endsWith(".img")) {
                continue;
            }
            File outFile = new File(destDir, child.getName());
            try (InputStream in = new FileInputStream(child);
                 OutputStream out = new FileOutputStream(outFile)) {
                copyStream(in, out);
            }
            copied++;
        }
        return copied;
    }

    private void exportImagesToTree(File runDir, Uri uri) throws IOException {
        File root = new File(uri.path);
        if (!root.isDirectory()) {
            throw new IOException(getString(R.string.error_open_file));
        }
        String dirName = "signed_" + System.currentTimeMillis();
        File outDir = new File(root, dirName);
        if (!outDir.mkdirs() && !outDir.isDirectory()) {
            throw new IOException(getString(R.string.error_create_dir) + dirName);
        }
        File[] files = runDir.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".img"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            File outFile = new File(outDir, file.getName());
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = new FileOutputStream(outFile)) {
                copyStream(in, out);
            }
        }
    }

    private boolean hasPartitionImage(File runDir) {
        File[] files = runDir.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".img"));
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (!"vbmeta.img".equalsIgnoreCase(file.getName())) {
                return true;
            }
        }
        return false;
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private void cleanupWorkDir() {
        File workDir = new File(getFilesDir(), "work");
        if (!workDir.exists() || !workDir.isDirectory()) {
            return;
        }
        File[] entries = workDir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            deleteRecursive(entry);
        }
    }

    private void appendWorkLog(File dir, String text) {
        if (dir == null || text == null || text.trim().isEmpty()) {
            return;
        }
        File logFile = new File(dir, "run.log");
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(text);
            if (!text.endsWith("\n")) {
                writer.write("\n");
            }
        } catch (IOException ignored) {
        }
        appendPublicWorkLog(dir, text);
    }

    private void appendPublicWorkLog(File dir, String text) {
        if (dir == null || text == null || text.trim().isEmpty()) {
            return;
        }
        ensureDownloadDirExists();
        File publicLogFile = new File(DEFAULT_DOWNLOAD_DIR, dir.getName() + ".log");
        String line = text.endsWith("\n") ? text : text + "\n";
        String cmd = "printf %s " + shQuote(line) + " >> " + shQuote(publicLogFile.getAbsolutePath());
        try {
            runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private File writeTextFile(File dir, String name, String content) throws IOException {
        File out = new File(dir, name);
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private byte[] readFilePrefix(File file, int length) {
        if (file == null || !file.exists() || length <= 0) {
            return null;
        }
        byte[] buf = new byte[length];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(buf);
            if (read <= 0) {
                return null;
            }
            if (read < length) {
                return Arrays.copyOf(buf, read);
            }
            return buf;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void readExactly(InputStream in, byte[] buffer, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int read = in.read(buffer, off + total, len - total);
            if (read <= 0) {
                throw new IOException("Unexpected EOF");
            }
            total += read;
        }
    }

    private CommandResult runCommandWithRoot(File workDir, String innerCmd, boolean logOutput, String binDir)
            throws IOException, InterruptedException {
        return runCommandWithRoot(workDir, innerCmd, logOutput, binDir, 0L);
    }

    private CommandResult runCommandWithRoot(File workDir, String innerCmd, boolean logOutput, String binDir,
                                             long timeoutMs)
            throws IOException, InterruptedException {
        return runCommandWithRoot(workDir, innerCmd, logOutput, binDir, timeoutMs, false);
    }

    // qdl 专用入口：启用看门狗（可取消 + CONNECTED 后才起算业务超时）。
    private CommandResult runQdlCommandWithRoot(File workDir, String innerCmd, long timeoutMs)
            throws IOException, InterruptedException {
        return runCommandWithRoot(workDir, innerCmd, true, getRootEdlBinDir(), timeoutMs, true);
    }

    private CommandResult runCommandWithRoot(File workDir, String innerCmd, boolean logOutput, String binDir,
                                             long timeoutMs, boolean watchQdl)
            throws IOException, InterruptedException {
        if (logOutput && workDir != null) {
            ensureDownloadDirExists();
        }
        String fullCmd = buildRootCommand(workDir, innerCmd, binDir);
        // 轻量、无流式日志、无超时的命令走常驻 root shell，避免反复 fork su 进程造成卡顿；
        // 需要实时写 run.log 或带超时的刷写类命令仍单独起进程
        if (!watchQdl && !logOutput && timeoutMs <= 0) {
            return rootShell.exec(fullCmd);
        }
        List<String> cmd = new ArrayList<>(getSuCommandParts());
        cmd.add(fullCmd);
        File logFile = workDir != null ? new File(workDir, "run.log") : null;
        CommandResult result = runCommand(cmd, null, logOutput, logFile, null, timeoutMs, watchQdl);
        if (logOutput && workDir != null) {
            syncPublicLog(workDir);
        }
        return result;
    }

    private void syncPublicLog(File runDir) {
        if (runDir == null) {
            return;
        }
        File src = new File(runDir, "run.log");
        if (!src.exists()) {
            return;
        }
        ensureDownloadDirExists();
        File dest = new File(DEFAULT_DOWNLOAD_DIR, runDir.getName() + ".log");
        String cmd = "cat " + shQuote(src.getAbsolutePath()) + " > "
                + shQuote(dest.getAbsolutePath());
        List<String> cmdParts = new ArrayList<>(getSuCommandParts());
        cmdParts.add(cmd);
        try {
            runCommand(cmdParts, null, false, null, null);
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private String buildRootCommand(File workDir, String innerCmd, String binDir) {
        StringBuilder cmd = new StringBuilder();
        if (workDir != null) {
            cmd.append("cd ").append(shQuote(workDir.getAbsolutePath())).append(" && ");
        }
        cmd.append("export LD_LIBRARY_PATH=").append(shQuote(getRootEdlLibDir()))
                .append(":" + shQuote(getApplicationInfo().nativeLibraryDir)).append(";");
        cmd.append("export PATH=").append(shQuote(binDir)).append(":$PATH;");
        cmd.append(innerCmd);
        return cmd.toString();
    }

    private List<String> getSuCommandParts() {
        String text = suCommandInput != null ? suCommandInput.getText().toString().trim() : "";
        if (text.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            text = prefs.getString(PREF_SU_CMD, DEFAULT_SU_CMD);
            if (text == null || text.trim().isEmpty()) {
                text = DEFAULT_SU_CMD;
            }
        }
        return new ArrayList<>(Arrays.asList(text.trim().split("\\s+")));
    }

    private CommandResult runCommand(List<String> cmd, File workDir, boolean logOutput, File logFile,
                                     File publicLogFile)
            throws IOException, InterruptedException {
        return runCommand(cmd, workDir, logOutput, logFile, publicLogFile, 0L);
    }

    private CommandResult runCommand(List<String> cmd, File workDir, boolean logOutput, File logFile,
                                     File publicLogFile, long timeoutMs)
            throws IOException, InterruptedException {
        return runCommand(cmd, workDir, logOutput, logFile, publicLogFile, timeoutMs, false);
    }

    private CommandResult runCommand(List<String> cmd, File workDir, boolean logOutput, File logFile,
                                     File publicLogFile, long timeoutMs, boolean watchQdl)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) {
            pb.directory(workDir);
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();

        final int maxOutputBytes = 1024 * 1024;
        final byte[] buffer = new byte[8192];
        final java.io.ByteArrayOutputStream limitedOut = new java.io.ByteArrayOutputStream();
        final StringBuilder lineBuffer = logOutput ? new StringBuilder() : null;
        final IOException[] readerError = new IOException[1];
        Thread readerThread = new Thread(() -> {
            OutputStream fileOut = null;
            OutputStream publicFileOut = null;
            try (InputStream in = process.getInputStream()) {
                if (logFile != null) {
                    fileOut = new FileOutputStream(logFile, true);
                }
                if (publicLogFile != null) {
                    File parent = publicLogFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    publicFileOut = new FileOutputStream(publicLogFile, true);
                }
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (fileOut != null) {
                        fileOut.write(buffer, 0, read);
                    }
                    if (publicFileOut != null) {
                        publicFileOut.write(buffer, 0, read);
                    }
                    if (limitedOut.size() < maxOutputBytes) {
                        int remaining = maxOutputBytes - limitedOut.size();
                        int toWrite = Math.min(remaining, read);
                        limitedOut.write(buffer, 0, toWrite);
                    }
                    if (logOutput) {
                        String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        emitSummaryLines(text, lineBuffer);
                    }
                }
            } catch (IOException e) {
                readerError[0] = e;
            } finally {
                if (fileOut != null) {
                    try {
                        fileOut.close();
                    } catch (IOException ignored) {
                    }
                }
                if (publicFileOut != null) {
                    try {
                        publicFileOut.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "cmd-output-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished;
        if (watchQdl) {
            // qdl 命令：暴露句柄供取消，按"抓到设备才计时"的双阶段超时 join 进程退出。
            activeProcess.set(process);
            try {
                awaitQdlProcess(process, timeoutMs);
            } catch (InterruptedException | CommandCanceledException e) {
                stopProcess(process, readerThread);
                emitTrailingSummary(logOutput, lineBuffer);
                throw e;
            } finally {
                activeProcess.compareAndSet(process, null);
            }
            finished = true;
        } else if (timeoutMs > 0) {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } else {
            process.waitFor();
            finished = true;
        }
        if (!finished) {
            stopProcess(process, readerThread);
            emitTrailingSummary(logOutput, lineBuffer);
            throw new InterruptedException("Command timed out after " + timeoutMs + " ms");
        }

        readerThread.join(2000);
        if (readerThread.isAlive()) {
            readerThread.interrupt();
        }
        if (readerError[0] != null) {
            throw readerError[0];
        }
        emitTrailingSummary(logOutput, lineBuffer);

        int exitCode = process.exitValue();
        // ByteArrayOutputStream.toString(Charset) is API 33+; build the String
        // from bytes so this works on the minSdk 26 baseline.
        String output = new String(limitedOut.toByteArray(), StandardCharsets.UTF_8);
        return new CommandResult(exitCode, output);
    }

    // 等 qdl 进程退出：每 COMMAND_WATCHDOG_INTERVAL_MS join 一次（join 进程，不是轮询 USB）。
    // WAITING（qdl 还在等设备）永不计业务超时；CONNECTED（已抓到设备）后才起算 timeoutMs。
    // 收到取消信号立即抛出，让上层中止整个刷写流程。
    private void awaitQdlProcess(Process process, long timeoutMs) throws InterruptedException {
        long connectedSinceMs = -1L;
        for (;;) {
            if (commandCanceled.get()) {
                throw new CommandCanceledException("用户已取消");
            }
            if (process.waitFor(COMMAND_WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                // 进程已退出：若同一时刻收到取消（destroy 触发退出），按取消归因而非正常结束。
                if (commandCanceled.get()) {
                    throw new CommandCanceledException("用户已取消");
                }
                return;
            }
            if (timeoutMs > 0 && qdlPhase.get() == QdlPhase.CONNECTED) {
                long now = SystemClock.elapsedRealtime();
                if (connectedSinceMs < 0) {
                    connectedSinceMs = now;
                } else if (now - connectedSinceMs >= timeoutMs) {
                    throw new InterruptedException("Command timed out after " + timeoutMs + " ms");
                }
            }
        }
    }

    // destroy → 1500ms → destroyForcibly → 1500ms → join reader → interrupt 阶梯。
    private void stopProcess(Process process, Thread readerThread) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(1500, TimeUnit.MILLISECONDS);
        }
        readerThread.join(2000);
        if (readerThread.isAlive()) {
            readerThread.interrupt();
        }
    }

    private void emitTrailingSummary(boolean logOutput, StringBuilder lineBuffer) {
        if (logOutput && lineBuffer != null && lineBuffer.length() > 0) {
            emitSummaryLine(lineBuffer.toString());
            lineBuffer.setLength(0);
        }
    }

    private void prepareQdlCommandState(boolean expectFirehose, boolean expectVip) {
        expectFirehoseStep = expectFirehose;
        expectVipSteps = expectVip;
        vipDigestStarted = false;
        vipSignStarted = false;
        sawProbeReadFailure = false;
        lastFirehoseStep = null;
        summaryOnlyLog = true;
        // qdl 命令启动前：阶段从"等设备"开始，并开启输出阶段解析。
        watchingQdlOutput.set(true);
        qdlPhase.set(QdlPhase.WAITING);
    }

    private void clearQdlCommandState() {
        expectFirehoseStep = false;
        expectVipSteps = false;
        vipDigestStarted = false;
        vipSignStarted = false;
        sawProbeReadFailure = false;
        lastFirehoseStep = null;
        summaryOnlyLog = false;
        watchingQdlOutput.set(false);
        qdlPhase.set(QdlPhase.IDLE);
    }

    private void markFirehoseOk() {
        if (!expectFirehoseStep || firehoseStepLogged) {
            return;
        }
        appendStepResult("发送 Firehose", true);
        firehoseStepLogged = true;
    }

    private void markFirehoseFailure(String reason) {
        if (!expectFirehoseStep || firehoseStepLogged) {
            return;
        }
        appendStepResult("发送 Firehose", false, reason);
        firehoseStepLogged = true;
    }

    private void handleFirehoseResult(boolean ok) {
        String step = lastFirehoseStep;
        lastFirehoseStep = null;
        if (step == null || step.trim().isEmpty()) {
            return;
        }
        if (summaryOnlyLog && !"配置设备".equals(step)) {
            return;
        }
        if ("配置设备".equals(step)) {
            if (!configureStepLogged) {
                appendStepResult("配置设备", ok);
                configureStepLogged = true;
            }
            return;
        }
        appendStepResult(step, ok);
    }

    private boolean handleFirehoseLine(String trimmed, String lower) {
        if (lower.contains("value=\"ack\"")) {
            if (lower.contains("rawmode=\"true\"")) {
                return true;
            }
            handleFirehoseResult(true);
            return true;
        }
        if (lower.contains("value=\"nak\"")) {
            handleFirehoseResult(false);
            return true;
        }
        if (lower.contains("<configure")) {
            lastFirehoseStep = normalizeFirehoseStep("configure");
            return true;
        }
        if (lower.contains("<read")) {
            lastFirehoseStep = normalizeFirehoseStep("read");
            return true;
        }
        if (lower.contains("<program")) {
            lastFirehoseStep = normalizeFirehoseStep("program");
            return true;
        }
        if (lower.contains("<erase")) {
            lastFirehoseStep = normalizeFirehoseStep("erase");
            return true;
        }
        if (lower.contains("<power")) {
            lastFirehoseStep = normalizeFirehoseStep("power");
            return true;
        }
        if (lower.contains("<nop")) {
            lastFirehoseStep = normalizeFirehoseStep("nop");
            return true;
        }
        if (lower.contains("<getstorageinfo")) {
            lastFirehoseStep = normalizeFirehoseStep("getstorageinfo");
            return true;
        }
        if (lower.contains("<setbootablestoragedrive")) {
            lastFirehoseStep = normalizeFirehoseStep("setbootablestoragedrive");
            return true;
        }
        if (lower.contains("<getactiveslot")) {
            lastFirehoseStep = normalizeFirehoseStep("getactiveslot");
            return true;
        }
        if (lower.contains("<ufs")) {
            lastFirehoseStep = normalizeFirehoseStep("ufs");
            return true;
        }
        return false;
    }

    private boolean handleVipSummaryLine(String trimmed, String lower) {
        if (!expectVipSteps) {
            return false;
        }
        if (lower.contains("signed digest table")) {
            boolean isSign = lower.contains("sign.bin");
            boolean isDigest = lower.contains("digest.elf");
            if (isSign || (!isDigest && vipDigestStarted && !vipSignStarted)) {
                vipSignStarted = true;
                if (!vipSignStepLogged) {
                    appendStepResult("签名", true);
                    vipSignStepLogged = true;
                }
            } else {
                vipDigestStarted = true;
                if (!vipDigestStepLogged) {
                    appendStepResult("发送 Digest", true);
                    vipDigestStepLogged = true;
                }
            }
            return true;
        }
        if (lower.contains("usb write failed for signed digest")
                || lower.contains("signed digest rejected")
                || lower.contains("non-successful end-of-image")
                || lower.contains("request length not matching")) {
            String reason = summarizeQdlFailure(trimmed);
            if (reason == null || reason.trim().isEmpty()) {
                reason = "发送失败";
            }
            if (!vipSignStepLogged && vipSignStarted) {
                appendStepResult("签名", false, reason);
                vipSignStepLogged = true;
            } else if (!vipDigestStepLogged) {
                appendStepResult("发送 Digest", false, reason);
                vipDigestStepLogged = true;
            }
            recordErrorReason(reason);
            return true;
        }
        if (lower.contains("vip auth done") || lower.contains("vip auth success")) {
            if (!vipDigestStepLogged) {
                appendStepResult("发送 Digest", true);
                vipDigestStepLogged = true;
            }
            if (!vipSignStepLogged) {
                appendStepResult("签名", true);
                vipSignStepLogged = true;
            }
            return true;
        }
        return false;
    }

    private boolean isProbeFailureLine(String lower) {
        if (lower.contains("sector size defined in xml") && lower.contains("different")) {
            return true;
        }
        if (lower.contains("label read not exist")) {
            return true;
        }
        // qdl 的扇区探测发的是无 label 的 read，OPlus programmer 据此回 "label cannot be null"
        // 并 NAK——这是探测序列的一部分（gpt.c 注释亦确认 OPlus 拒绝无 label 读），属良性。
        // 真实 read/program/erase 在 oplus_mode 下始终带 label，不会触发此行。
        if (lower.contains("label cannot be null")) {
            return true;
        }
        // OPlus VIP programmer 在未授权/外部网络态拒绝读："read on <label>:s:n not allowed on
        // external network"，随后 not get permission + boot log dump + NAK，qdl 重试后恢复，整段
        // 属良性探测序列。原 "read on read:" 是永不出现的死串，导致这些行被误记为"权限不足"失败。
        if ((lower.contains("read on ") && lower.contains("not allowed"))
                || lower.contains("not allowed on external network")) {
            return true;
        }
        // GPT 扫描自动探测 LUN 数时会多探一个不存在的 LUN，programmer 回 "Failed to open the UFS
        // Device ... partition N" / "Failed to open device, type:UFS" 后跟一个普通 NAK——这是
        // gpt.c eof 边界探测的良性信号(真实 read/program 只针对有效 LUN)。标记为探测态，使紧随的
        // <response value="NAK"> 被豁免，并由下一条 ACK 解除探测态。
        // 小米工程 loader 在 sig 鉴权【之前】对任何命令(configure 等)回 "Only nop and sig tag can be
        // recevied before authentication" 并 NAK——这是 reactive 鉴权序列的【预期】起点：qdl 随后自动发
        // sig blob 鉴权(逐个试，前几个 blob 失败属正常)，成功后重试 configure 回 ACK 解除探测态。标记为
        // 探测态以豁免其后到首个 ACK 之间的 NAK，避免把"鉴权前的预期拒绝/鉴权过程"误判为刷写失败
        // (即"sig 一报错就显示失败")。鉴权【真】失败时 qdl 退非零，由 isCommandSuccess 的 exitCode 捕获，
        // 不靠输出扫描，故此豁免不会掩盖真实鉴权失败。
        if (lower.contains("only nop and sig") || lower.contains("before authentication")) {
            return true;
        }
        return lower.contains("failed to open the ufs device")
                || lower.contains("failed to open device, type:ufs")
                || lower.contains("open handle null and no error");
    }

    // 一加动态 token 授权握手的内部 chatter，不能凭其中的 nak/error 判刷写失败：
    //  - demacia/setprojmodel 逐机型候选验证回 <response value="NAK" model_check=.../verify_res=...>
    //    (最终成败由 qdl 退出码 + "authenticated"/"rejected" 决定，握手失败时 qdl 已退非 0)；
    //  - demacia 把设备回显的二进制 token 当 <log> 属性塞进 XML，libxml2 解析非 UTF-8 字节刷出的
    //    多行 "libxml2 fatal: ..."(qdl 已 RECOVER 跳过)。
    // 注意只判这些握手专属标记，真实 program/erase 的 NAK(不含 model_check 等)不在此列，仍计为失败。
    private boolean isOplusHandshakeNoise(String lower) {
        return lower.contains("model_check=")
                || lower.contains("auth_token_verify=")
                || lower.contains("verify_res=")
                || lower.contains("libxml2 fatal");
    }

    // 设备侧 <log> chatter 有三种出现形态：解析后的 "LOG: ..." 行、--debug 原样回显的
    // "FIREHOSE READ: ...<log value=...>" 行，以及 libxml2 多行美化输出时单独成行的
    // "<log value=... />" 续行(如 VIP 复用会话首条 configure 的 "Failed to run the last
    // command -1")。三者都只是设备日志，不能凭其中的 error/failed 字样判失败。真正的 firehose
    // 应答是 <response value="ACK|NAK">(不含 <log)，仍由 NAK 分支捕获；<log> 里的致命错误
    // (is unmatch/hash mismatch 等)由调用方先行的 isDeviceFatalLine 拦截，不受此忽略影响。
    private boolean isDeviceLogLine(String lower) {
        return lower.startsWith("log:") || lower.contains("<log");
    }

    // 设备/VIP 致命错误：即便出现在透传日志中也必须计为失败，绕过 isDeviceLogLine 整类忽略。
    private boolean isDeviceFatalLine(String lower) {
        return lower.contains("signed digest rejected")
                || lower.contains("hash mismatch")
                || lower.contains("is unmatch")
                || lower.contains("not authenticated")
                || lower.contains("authentication failed");
    }

    private boolean shouldIgnoreFailureLine(String lower) {
        // 与 shouldIgnoreFailureLineForOutput 保持一致：致命设备/VIP 错误不忽略，其余设备透传日志
        // (LOG:/<log> 回显)不作为失败原因，避免良性引导日志污染失败展示。
        if (isDeviceFatalLine(lower)) {
            return false;
        }
        if (isOplusHandshakeNoise(lower)) {
            return true;
        }
        if (isDeviceLogLine(lower)) {
            return true;
        }
        if (lower.contains("devprgrsaverify verify signature failed")) {
            return true;
        }
        if (lower.contains("begin boot log") || lower.contains("end boot log")) {
            return true;
        }
        if (lower.contains("devprg_log_dump_boot_log")) {
            return true;
        }
        if (lower.contains("log: error:") && lower.contains(" b - ")) {
            return true;
        }
        if (lower.contains("mode=") && lower.contains("invalid")) {
            return true;
        }
        if (lower.contains("enableflash not found")) {
            return true;
        }
        if (sawProbeReadFailure
                && (lower.contains("not get permission")
                || lower.contains("failed to setup reading operation"))) {
            return true;
        }
        return false;
    }

    private void emitSummaryLines(String chunk, StringBuilder lineBuffer) {
        if (lineBuffer == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\n' || c == '\r') {
                if (lineBuffer.length() > 0) {
                    emitSummaryLine(lineBuffer.toString());
                    lineBuffer.setLength(0);
                }
            } else {
                lineBuffer.append(c);
            }
        }
    }

    // 从 qdl 输出推断阶段：抓到设备(CONNECTED)的标志一旦出现就单调锁定，
    // 不因后续瞬态拔插再退回 WAITING。lower 已是小写。
    private void updateQdlPhaseFromLine(String lower) {
        if (!watchingQdlOutput.get() || lower == null) {
            return;
        }
        // usb.c 在打开设备后立即打印 "Flashing device"/"Collecting crash dump"；
        // firehose 启动打印 "waiting for Firehose programmer..."，都表示设备已抓到。
        if (lower.contains("flashing device")
                || lower.contains("collecting crash dump")
                || lower.contains("waiting for firehose programmer")) {
            markQdlConnected();
            return;
        }
        if (lower.contains("waiting for edl device") || lower.contains("none could be opened")) {
            markQdlWaiting();
        }
    }

    private void markQdlWaiting() {
        if (watchingQdlOutput.get() && qdlPhase.get() != QdlPhase.CONNECTED) {
            qdlPhase.set(QdlPhase.WAITING);
        }
    }

    private void markQdlConnected() {
        if (watchingQdlOutput.get()) {
            qdlPhase.set(QdlPhase.CONNECTED);
        }
    }

    private void emitSummaryLine(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String lower = trimmed.toLowerCase(Locale.US);
        // 阶段解析必须早于下面各 early-return，否则会被跳过。
        updateQdlPhaseFromLine(lower);
        if (lower.startsWith("cmd:")) {
            return;
        }
        if (handleVipSummaryLine(trimmed, lower)) {
            return;
        }
        if (lower.contains("firehose already active")) {
            markFirehoseOk();
            return;
        }
        if (lower.contains("waiting for programmer") || lower.contains("done status: 1")) {
            markFirehoseOk();
            return;
        }
        if (lower.contains("failed to read sahara request")) {
            String reason = "Sahara 通信失败";
            markFirehoseFailure(reason);
            recordErrorReason(reason);
            return;
        }
        if (isProbeFailureLine(lower)) {
            sawProbeReadFailure = true;
            return;
        }
        // 探测恢复：收到 ACK 即解除探测态，使其后真实失败仍能记录原因(与 outputHasFailureInternal 一致)
        if (sawProbeReadFailure && lower.contains("value=\"ack\"")) {
            sawProbeReadFailure = false;
        }
        if (shouldIgnoreFailureLine(lower)) {
            return;
        }
        if (handleFirehoseLine(trimmed, lower)) {
            return;
        }
        summarizeLogLine(trimmed);
        if (lower.contains("failed") || lower.contains("nak")
                || lower.contains("not allowed") || lower.contains("permission")) {
            if (shouldIgnoreFailureLine(lower)) {
                return;
            }
            // 探测/鉴权序列(含小米 sig 鉴权前的 "Only nop and sig" → configure NAK，及 LUN 边界探测)
            // 期间的 NAK/良性失败行属【预期】，由其后 ACK 解除探测态或 qdl 退出码定夺，不在此实时记为失败
            // 原因——与 outputHasFailureInternal 的 sawProbe NAK 豁免(只豁免 NAK，不豁免 failed/error)一致，
            // 避免"鉴权过程"被误记/误显为失败(即"sig 一报错就显示失败")。
            if (sawProbeReadFailure && (lower.contains("value=\"nak\"")
                    || lower.contains("not get permission")
                    || lower.contains("failed to setup reading operation"))) {
                return;
            }
            String reason = summarizeQdlFailure(trimmed);
            if (reason != null && !"执行失败".equals(reason)) {
                recordErrorReason(reason);
            }
        }
    }

    private String summarizeQdlFailure(String output) {
        if (output == null) {
            return null;
        }
        String lower = output.toLowerCase(Locale.US);
        if (lower.contains("usb write failed for signed digest")) {
            return "USB 写入超时";
        }
        if (lower.contains("usb write timed out")) {
            return "USB 写入超时";
        }
        if (lower.contains("usb device disconnected")) {
            return "设备已断开";
        }
        if (lower.contains("failed to send signed digest")) {
            return "Digest 发送失败";
        }
        if (lower.contains("signed digest rejected")) {
            return "Digest 被拒绝";
        }
        if (lower.contains("vip parameters missing")) {
            return "VIP 参数缺失";
        }
        if (lower.contains("error on sahara handshake")) {
            return "Sahara 握手失败";
        }
        if (lower.contains("no suitable loader found")) {
            return "Loader 不匹配";
        }
        if (lower.contains("device is in sahara error state")) {
            return "设备处于 Sahara 异常状态";
        }
        if (lower.contains("non-successful end-of-image")) {
            return "Sahara 发送失败";
        }
        if (lower.contains("failed to parse firehose response") || lower.contains("parser error")) {
            return "Firehose 响应解析失败";
        }
        if (lower.contains("failed to detect file type") || lower.contains("file type of")) {
            return "XML 不受支持";
        }
        if (lower.contains("errors while parsing") || lower.contains("failed to parse")
                || lower.contains("program_load") || lower.contains("read_op_load")
                || lower.contains("patch_load")) {
            return "XML 解析失败";
        }
        if (lower.contains("rawxml")
                && (lower.contains("only supports") || lower.contains("仅支持"))) {
            return "rawxml 仅支持 read/program/ufs";
        }
        if (lower.contains("failed to read sahara request")) {
            return "Sahara 通信失败";
        }
        if (lower.contains("failed to write") && lower.contains("sahara")) {
            return "Sahara 发送失败";
        }
        if (lower.contains("label") && lower.contains("not exist")) {
            return "分区标签不存在";
        }
        if (lower.contains("vip initialization failed")) {
            return "VIP 初始化失败";
        }
        if (lower.contains("mode=") && lower.contains("invalid")) {
            return "配置失败";
        }
        if (lower.contains("failed to setup reading operation")) {
            return "读取失败";
        }
        if (lower.contains("failed to setup programming operation")
                || lower.contains("failed to setup programming")) {
            return "写入失败";
        }
        if (lower.contains("not allowed") || lower.contains("not get permission")
                || lower.contains("permission denied")) {
            return "权限不足";
        }
        if (lower.contains("eacces")) {
            return "无写入权限";
        }
        // 扇区/无 label 探测类措辞放最后归因：它常作为良性探测序列出现，仅当无其它更确定的失败
        // 原因时才采用，避免真实失败(如 is unmatch/programming failed)被误报为"扇区大小不匹配"。
        if (lower.contains("sector size defined") && lower.contains("different")) {
            return "扇区大小不匹配";
        }
        return "执行失败";
    }

    private boolean outputHasFailure(String output) {
        return outputHasFailureInternal(output, true);
    }

    private boolean outputHasFailureForRead(String output) {
        return outputHasFailureInternal(output, true);
    }

    private boolean outputHasFailureInternal(String output, boolean allowProbe) {
        if (output == null || output.trim().isEmpty()) {
            return false;
        }
        boolean sawProbe = false;
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String lower = line.trim().toLowerCase(Locale.US);
            if (lower.isEmpty()) {
                continue;
            }
            if (allowProbe && isProbeFailureLine(lower)) {
                sawProbe = true;
                continue;
            }
            // 探测恢复点：收到 ACK 说明探测序列已结束(其间全是失败、无 ACK，故不会在序列中途误清)，
            // 此后的 NAK 应判为真实失败——避免探测态在整段输出里永久锁定而吞掉后续真实失败(假阴性)。
            if (sawProbe && lower.contains("value=\"ack\"")) {
                sawProbe = false;
            }
            if (shouldIgnoreFailureLineForOutput(lower, sawProbe)) {
                continue;
            }
            if (lower.contains("nak")) {
                if (allowProbe && sawProbe) {
                    continue;
                }
                return true;
            }
            if (lower.contains("failed")
                    || lower.contains("error")
                    || lower.contains("not allowed")
                    || lower.contains("permission denied")
                    || lower.contains("not get permission")
                    || lower.contains("usb write failed")
                    || lower.contains("signed digest rejected")
                    || lower.contains("non-successful end-of-image")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldIgnoreFailureLineForOutput(String lower, boolean sawProbe) {
        // 设备/VIP 致命错误即使出现在透传日志里也必须计为失败，不能被下面的设备日志整类忽略吞掉。
        if (isDeviceFatalLine(lower)) {
            return false;
        }
        // 一加 token 授权握手内部 chatter(逐机型候选 NAK / demacia 二进制回显 libxml2 噪声)非刷写失败
        if (isOplusHandshakeNoise(lower)) {
            return true;
        }
        // 设备透传日志只是设备侧 chatter，不能作为失败权威：含 error/failed/nak 的良性引导日志(boot
        // log 正文、含 NAK 文本的 <log>)会把成功刷写误判为失败。这类日志有两种出现形态——解析后的
        // "LOG: ..." 行，以及 --debug 原样回显的 "FIREHOSE READ: ...<log value=...>"。两者都跳过；
        // 真正的 firehose NAK 应答是 "FIREHOSE READ: ...<response value=\"NAK\">"(不含 <log)仍被捕获。
        if (isDeviceLogLine(lower)) {
            return true;
        }
        if (lower.contains("devprgrsaverify verify signature failed")) {
            return true;
        }
        if (lower.contains("begin boot log") || lower.contains("end boot log")) {
            return true;
        }
        if (lower.contains("devprg_log_dump_boot_log")) {
            return true;
        }
        if (lower.contains("log: error:") && lower.contains(" b - ")) {
            return true;
        }
        if (lower.contains("mode=") && lower.contains("invalid")) {
            return true;
        }
        if (lower.contains("enableflash not found")) {
            return true;
        }
        if (sawProbe && (lower.contains("not get permission")
                || lower.contains("failed to setup reading operation"))) {
            return true;
        }
        return false;
    }

    private boolean isCommandSuccess(CommandResult result) {
        return result != null && result.exitCode == 0 && !outputHasFailure(result.output);
    }

    private String summarizeLogLine(String line) {
        if (line == null) {
            return null;
        }
        String lower = line.toLowerCase(Locale.US);
        int idx = lower.indexOf("calling handler for ");
        if (idx >= 0) {
            String cmd = line.substring(idx + "calling handler for ".length()).trim();
            if (!cmd.isEmpty()) {
                String normalized = normalizeFirehoseStep(cmd);
                if (normalized != null && !normalized.isEmpty()) {
                    lastFirehoseStep = normalized;
                }
                return null;
            }
        }
        return null;
    }

    private String formatFirehoseResult(boolean ok) {
        String step = lastFirehoseStep;
        if (step == null || step.trim().isEmpty()) {
            return ok ? "OK" : "ERROR";
        }
        String text = step + " " + (ok ? "OK" : "ERROR");
        lastFirehoseStep = null;
        return text;
    }

    private String normalizeFirehoseStep(String step) {
        if (step == null) {
            return "";
        }
        String lower = step.trim().toLowerCase(Locale.US);
        switch (lower) {
            case "configure":
                return "配置设备";
            case "transfercfg":
            case "verify":
            case "sha256init":
                return "";
            case "read":
                return "读取";
            case "program":
                return "写入";
            case "erase":
                return "擦除";
            case "power":
                return "电源";
            case "nop":
                return "NOP";
            case "getstorageinfo":
                return "获取存储信息";
            case "setbootablestoragedrive":
                return "设置启动盘";
            case "getactiveslot":
                return "读取槽位";
            case "ufs":
                return "UFS";
            default:
                return step;
        }
    }

    private String joinArgs(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(shQuote(arg));
        }
        return sb.toString();
    }

    private String shQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    // 外部/所选路径（/storage 等）受 scoped storage 限制，app 进程的 File API 会被拦；
    // 这些操作一律走 root（su），不回退系统 API。调用方须在后台线程执行。
    private boolean rootExists(String path, boolean isDir) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        // 用唯一标记判断，避免 su banner/拒权输出导致误判
        String cmd = "if test " + (isDir ? "-d " : "-e ") + shQuote(path.trim())
                + "; then echo __EDL_OK__; fi";
        try {
            CommandResult r = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            return r != null && r.exitCode == 0 && r.output != null && r.output.contains("__EDL_OK__");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private List<String> rootListNames(String dirPath) {
        List<String> names = new ArrayList<>();
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return names;
        }
        String cmd = "ls -1 " + shQuote(dirPath.trim());
        try {
            CommandResult r = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            if (r == null || r.output == null) {
                return names;
            }
            for (String line : r.output.split("\\n")) {
                String n = line.trim();
                if (!n.isEmpty()) {
                    names.add(n);
                }
            }
        } catch (IOException | InterruptedException ignored) {
        }
        return names;
    }

    private long rootFileSize(String path) {
        if (path == null || path.trim().isEmpty()) {
            return -1L;
        }
        String q = shQuote(path.trim());
        String cmd = "stat -c %s " + q + " 2>/dev/null || wc -c < " + q;
        try {
            CommandResult r = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            if (r == null || r.exitCode != 0 || r.output == null) {
                return -1L;
            }
            // 输出可能多行/带 banner，取最后一个纯数字 token
            long size = -1L;
            for (String tok : r.output.trim().split("\\s+")) {
                if (tok.matches("\\d+")) {
                    size = Long.parseLong(tok);
                }
            }
            return size;
        } catch (IOException | InterruptedException | NumberFormatException e) {
            return -1L;
        }
    }

    // 把外部/所选文件用 root 拷进 app 工作目录，供本地 DOM 解析 / sparse 转换读取。
    private File copyExternalToWorkViaRoot(String externalPath, File workDir, String name) {
        if (externalPath == null || externalPath.trim().isEmpty() || workDir == null) {
            return null;
        }
        if (name == null || name.trim().isEmpty()) {
            name = new File(externalPath).getName();
        }
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        File dest = new File(workDir, sanitizeFileName(name));
        String cmd = "cp -f " + shQuote(externalPath.trim()) + " " + shQuote(dest.getAbsolutePath())
                + " && chmod 644 " + shQuote(dest.getAbsolutePath());
        try {
            CommandResult r = runCommandWithRoot(null, cmd, false, getRootEdlBinDir());
            return (r != null && r.exitCode == 0 && dest.exists()) ? dest : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    // 用 root 直接读取目标文件的原始字节（su cat），二进制安全、不落地拷贝。
    // 仅用于小文件（XML / GPT 头几扇区）。
    private byte[] rootReadBytes(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        java.io.InputStream in = rootOpenStream(path);
        if (in == null) {
            return null;
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    // 以 root 打开目标文件的读取流（su -c cat <path>）——直接用目标，不拷贝。
    // 调用方负责 close()（会顺带回收子进程）。
    private java.io.InputStream rootOpenStream(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        List<String> cmd = new ArrayList<>(getSuCommandParts());
        cmd.add("cat " + shQuote(path.trim()));
        try {
            final Process proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            // 后台吞掉 stderr，避免 stderr 管道写满阻塞子进程
            drainQuietly(proc.getErrorStream());
            final java.io.InputStream raw = proc.getInputStream();
            return new java.io.FilterInputStream(raw) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        // 若调用方未读完（如分片合并中途失败），cat 可能卡在写管道；
                        // 有限等待后强杀，绝不无限阻塞 executor。
                        try {
                            if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                                proc.destroy();
                                if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                                    proc.destroyForcibly();
                                    proc.waitFor(500, TimeUnit.MILLISECONDS);
                                }
                            }
                        } catch (InterruptedException ignored) {
                            proc.destroyForcibly();
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            };
        } catch (IOException e) {
            return null;
        }
    }

    private void drainQuietly(final java.io.InputStream stream) {
        if (stream == null) {
            return;
        }
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                while (stream.read(buf) > 0) {
                    // discard
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void appendLog(String message) {
        appendLogInternal(message, false);
    }

    private void appendSummaryLog(String message) {
        appendLogInternal(message, true);
    }

    private void showToast(String message) {
        cb.onToast(message);
    }

    private void appendLogInternal(String message, boolean allowInSummary) {
        String safe = sanitizeLogLine(message);
        if (summaryOnlyLog && !allowInSummary) {
            return;
        }
        cb.onLog(safe);
    }

    // 在主线程把缓冲里的日志行一次性 append，减少高频日志下的 UI 调度与整树 relayout。

    // 日志追加后滚到底，跟随最新输出（独立于外层页面滚动）。

    private void appendStepResult(String step, boolean ok) {
        appendStepResult(step, ok, null);
    }

    private boolean finishStep(String step, boolean ok) {
        return finishStep(step, ok, null);
    }

    private void appendStepResult(String step, boolean ok, String reason) {
        String label = step == null ? "" : step.trim();
        if (label.isEmpty()) {
            appendSummaryLog(ok ? "OK" : "失败");
            return;
        }
        if (!ok && (reason == null || reason.trim().isEmpty())) {
            reason = consumeErrorReason();
        }
        if (ok) {
            appendSummaryLog(label + " OK");
            return;
        }
        String msg = label + " 失败";
        String detail = reason == null ? "" : reason.trim();
        if (!detail.isEmpty()) {
            msg = msg + ": " + detail;
        }
        appendSummaryLog(msg);
    }

    private boolean finishStep(String step, boolean ok, String reason) {
        if (!ok && (reason == null || reason.trim().isEmpty())) {
            reason = consumeErrorReason();
        }
        appendStepResult(step, ok, reason);
        return ok;
    }

    private void recordErrorReason(String reason) {
        if (reason == null) {
            return;
        }
        String trimmed = reason.trim();
        if (!trimmed.isEmpty()) {
            lastErrorReason = trimmed;
        }
    }

    private String consumeErrorReason() {
        String reason = lastErrorReason;
        lastErrorReason = null;
        return reason;
    }

    private String sanitizeLogLine(String message) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c >= 0x20) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private void clearLog() {
        lastFirehoseStep = null;
        logLineCount = 0;
        logSession.incrementAndGet();
        firehoseStepLogged = false;
        configureStepLogged = false;
        vipDigestStepLogged = false;
        vipSignStepLogged = false;
        expectFirehoseStep = false;
        expectVipSteps = false;
        vipDigestStarted = false;
        vipSignStarted = false;
        sawProbeReadFailure = false;
        summaryOnlyLog = false;
        lastErrorReason = null;
        synchronized (pendingLogBatch) {
            pendingLogBatch.clear();
            pendingLogSession = logSession.get();
        }
        cb.onLog(null);
    }

    private void startProgress(String label) {
        stopLogProgressMonitor();
        resetProgressMetrics();
        cb.onProgress(-1, label == null ? "" : label);
    }

    private void startLogProgressMonitor(File logFile, String label) {
        stopLogProgressMonitor();
        resetProgressMetrics();
        progressLogFile = logFile;
        progressLabel = label;
        progressRunning = true;
        progressHasValue = false;
        progressSpeed = null;
        int token = progressSeq.incrementAndGet();
        cb.onProgress(-1, label == null ? "" : label);
        progressFuture = progressExecutor.scheduleAtFixedRate(() -> {
            if (!progressRunning || token != progressSeq.get()) {
                return;
            }
            String tail = readLogTail(progressLogFile, 16384);
            int value = extractProgressFromText(tail);
            String speed = extractSpeedFromText(tail);
            if (speed != null) {
                progressSpeed = speed;
            }
            if (!progressRunning || token != progressSeq.get()) {
                return;
            }
            if (value >= 0) {
                if (speed == null) {
                    updateProgressSpeedFromPercent(value);
                }
                progressHasValue = true;
                String text = buildProgressStatusText(value);
                setProgressValue(value, text);
            } else if (progressSpeed != null) {
                String text = buildProgressStatusText(null);
                cb.onProgress(-1, text);
            } else if (!progressHasValue) {
                cb.onProgress(-1, progressLabel == null ? "" : progressLabel);
            }
        }, 200, 400, TimeUnit.MILLISECONDS);
    }

    private void stopLogProgressMonitor() {
        progressRunning = false;
        progressSeq.incrementAndGet();
        progressRunnable = null;
        if (progressFuture != null) {
            progressFuture.cancel(true);
            progressFuture = null;
        }
    }

    private void setProgressValue(int value, String label) {
        int normalized = Math.max(0, Math.min(100, value));
        cb.onProgress(normalized, label == null ? (normalized + "%") : label);
    }

    private void resetProgressMetrics() {
        progressTotalBytes = -1L;
        progressLastBytes = 0L;
        progressLastTimeMs = SystemClock.elapsedRealtime();
        progressSpeed = null;
    }

    private void setProgressTotalBytes(long totalBytes) {
        if (totalBytes > 0) {
            progressTotalBytes = totalBytes;
        } else {
            progressTotalBytes = -1L;
        }
        progressLastBytes = 0L;
        progressLastTimeMs = SystemClock.elapsedRealtime();
    }

    private void updateProgressSpeedFromPercent(int percent) {
        if (progressTotalBytes <= 0 || percent < 0) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long bytesDone = (progressTotalBytes * percent) / 100L;
        long deltaBytes = bytesDone - progressLastBytes;
        long deltaMs = now - progressLastTimeMs;
        if (deltaMs <= 0 || deltaBytes <= 0) {
            return;
        }
        double speed = (double) deltaBytes / (deltaMs / 1000.0) / (1024.0 * 1024.0);
        if (speed > 0) {
            progressSpeed = formatSpeed(speed);
            progressLastBytes = bytesDone;
            progressLastTimeMs = now;
        }
    }

    private String formatSpeed(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            return null;
        }
        return String.format(Locale.US, "%.1f", speed);
    }

    private void finishProgress(boolean success) {
        lastCommandSuccess = success;
        stopLogProgressMonitor();
        int value = success ? 100 : 0;
        String label = success ? "完成" : "失败";
        setProgressValue(value, label);
    }

    private int extractProgressFromText(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        int percentValue = -1;
        Matcher percentMatcher = PERCENT_PATTERN.matcher(text);
        while (percentMatcher.find()) {
            try {
                float valueFloat = Float.parseFloat(percentMatcher.group(1));
                int value = Math.round(valueFloat);
                if (value >= 0 && value <= 100) {
                    percentValue = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (percentValue >= 0) {
            return percentValue;
        }
        Matcher sectorMatcher = SECTOR_PROGRESS_PATTERN.matcher(text);
        while (sectorMatcher.find()) {
            try {
                long current = Long.parseLong(sectorMatcher.group(1), 16);
                long total = Long.parseLong(sectorMatcher.group(2), 16);
                if (total > 0) {
                    int value = (int) Math.min(100, Math.max(0, (current * 100L) / total));
                    percentValue = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return percentValue;
    }

    private String extractSpeedFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String speed = null;
        Matcher matcher = SPEED_PATTERN.matcher(text);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2);
                if (unit != null && unit.equalsIgnoreCase("kB")) {
                    value /= 1024.0;
                }
                speed = String.format(Locale.US, "%.2f", value);
            } catch (NumberFormatException ignored) {
            }
        }
        return speed;
    }

    private String buildProgressStatusText(Integer percent) {
        StringBuilder sb = new StringBuilder();
        if (progressLabel != null && !progressLabel.trim().isEmpty()) {
            sb.append(progressLabel.trim());
        }
        if (percent != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(percent).append('%');
        }
        if (progressSpeed != null && !progressSpeed.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(progressSpeed.trim()).append(" MB/s");
        }
        return sb.toString();
    }

    private String readLogTail(File logFile, int maxBytes) {
        if (logFile == null || !logFile.exists() || maxBytes <= 0) {
            return null;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
            long length = raf.length();
            if (length <= 0) {
                return null;
            }
            int size = (int) Math.min(maxBytes, length);
            long start = length - size;
            raf.seek(start);
            byte[] buffer = new byte[size];
            raf.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    private byte[] extractPeekBytesFromLog(File logFile) {
        if (logFile == null || !logFile.exists()) {
            return null;
        }
        String text = readLogTail(logFile, 4 * 1024 * 1024);
        if (text == null || text.isEmpty()) {
            return null;
        }
        List<Byte> bytes = new ArrayList<>();
        Pattern logValuePattern = Pattern.compile("log value=\\\"([^\\\"]+)\\\"");
        Matcher matcher = logValuePattern.matcher(text);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            collectHexBytes(matcher.group(1), bytes);
        }
        if (!found) {
            collectHexBytes(text, bytes);
        }
        if (bytes.isEmpty()) {
            return null;
        }
        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            out[i] = bytes.get(i);
        }
        return out;
    }

    private void collectHexBytes(String text, List<Byte> out) {
        if (text == null || out == null) {
            return;
        }
        Pattern hexPattern = Pattern.compile("0x([0-9a-fA-F]{2})");
        Matcher matcher = hexPattern.matcher(text);
        while (matcher.find()) {
            String hex = matcher.group(1);
            try {
                int value = Integer.parseInt(hex, 16);
                out.add((byte) value);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static class BuiltinAuthFiles {
        final String devprg;
        final String digest;
        final String signature;

        BuiltinAuthFiles(String devprg, String digest, String signature) {
            this.devprg = devprg;
            this.digest = digest;
            this.signature = signature;
        }
    }

    private static class CommandSpec {
        final String arg1Hint;
        final String arg2Hint;
        final String arg3Hint;
        final int requiredArgs;
        final int outputArgIndex;
        final ArgType arg1Type;
        final ArgType arg2Type;
        final ArgType arg3Type;

        CommandSpec(String arg1Hint, ArgType arg1Type, String arg2Hint, ArgType arg2Type,
                    String arg3Hint, ArgType arg3Type, int requiredArgs, int outputArgIndex) {
            this.arg1Hint = arg1Hint;
            this.arg2Hint = arg2Hint;
            this.arg3Hint = arg3Hint;
            this.requiredArgs = requiredArgs;
            this.outputArgIndex = outputArgIndex;
            this.arg1Type = arg1Type;
            this.arg2Type = arg2Type;
            this.arg3Type = arg3Type;
        }

        boolean hasRequiredArgs(String arg1, String arg2, String arg3) {
            if (requiredArgs >= 1 && (arg1 == null || arg1.isEmpty())) {
                return false;
            }
            if (requiredArgs >= 2 && (arg2 == null || arg2.isEmpty())) {
                return false;
            }
            if (requiredArgs >= 3 && (arg3 == null || arg3.isEmpty())) {
                return false;
            }
            return true;
        }
    }

    private static class ParsedOutput {
        final File outputFile;
        final QualcommTables.MemRange range;

        ParsedOutput(File outputFile, QualcommTables.MemRange range) {
            this.outputFile = outputFile;
            this.range = range;
        }
    }

    private static class VipTablePaths {
        final File signed;
        final File chain;

        VipTablePaths(File signed, File chain) {
            this.signed = signed;
            this.chain = chain;
        }
    }

    private static class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    // 常驻 root shell：启动时申请一次 su，之后所有轻量文本命令复用同一进程，
    // 避免每次都 fork 一个 su 进程导致界面卡顿。二进制读取与流式刷写仍各自起进程。
    private final class RootShell {
        private Process process;
        private OutputStream stdin;
        private BufferedReader stdout;
        private int seq;

        synchronized boolean open() {
            close();
            List<String> parts = new ArrayList<>(getSuCommandParts());
            // 交互式 shell 不带 -c
            if (!parts.isEmpty() && "-c".equals(parts.get(parts.size() - 1))) {
                parts.remove(parts.size() - 1);
            }
            if (parts.isEmpty()) {
                return false;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(parts);
                pb.redirectErrorStream(true);
                process = pb.start();
                stdin = process.getOutputStream();
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        synchronized boolean isAlive() {
            return process != null && process.isAlive();
        }

        // 把命令写入常驻 shell，并用唯一标记回读输出与退出码
        synchronized CommandResult exec(String cmd) {
            if (!isAlive() && !open()) {
                return new CommandResult(-1, "");
            }
            String marker = "__EDL_END_" + (++seq) + "__";
            try {
                stdin.write((cmd + "\necho " + marker + ":$?\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                close();
                return new CommandResult(-1, "");
            }
            StringBuilder out = new StringBuilder();
            try {
                String line;
                while ((line = stdout.readLine()) != null) {
                    int idx = line.indexOf(marker + ":");
                    if (idx >= 0) {
                        if (idx > 0) {
                            out.append(line, 0, idx);
                        }
                        int exit = parseIntSafe(line.substring(idx + marker.length() + 1).trim(), -1);
                        return new CommandResult(exit, out.toString());
                    }
                    out.append(line).append('\n');
                }
            } catch (IOException ignored) {
            }
            // 读到 EOF：shell 已退出
            close();
            return new CommandResult(-1, out.toString());
        }

        synchronized void close() {
            if (process != null) {
                try {
                    if (stdin != null) {
                        stdin.write("exit\n".getBytes(StandardCharsets.UTF_8));
                        stdin.flush();
                    }
                } catch (IOException ignored) {
                }
                process.destroy();
            }
            process = null;
            stdin = null;
            stdout = null;
        }
    }

    private final RootShell rootShell = new RootShell();

    private static class VipSpoofProfile {
        final String deviceLabel;
        final String deviceFilename;

        VipSpoofProfile(String deviceLabel, String deviceFilename) {
            this.deviceLabel = deviceLabel == null ? "" : deviceLabel;
            this.deviceFilename = deviceFilename == null ? "" : deviceFilename;
        }
    }

    private static class VipRangeSlice {
        final long startSector;
        final long numSectors;
        final String deviceLabel;
        final String deviceFilename;
        final long fileOffsetBytes;

        VipRangeSlice(long startSector, long numSectors, String deviceLabel,
                      String deviceFilename, long fileOffsetBytes) {
            this.startSector = startSector;
            this.numSectors = numSectors;
            this.deviceLabel = deviceLabel == null ? "" : deviceLabel;
            this.deviceFilename = deviceFilename == null ? "" : deviceFilename;
            this.fileOffsetBytes = fileOffsetBytes;
        }
    }

    private static class PortId {
        final String vid;
        final String pid;

        PortId(String vid, String pid) {
            this.vid = vid;
            this.pid = pid;
        }
    }

    private static class FhContext {
        final File loaderFile;
        final String portPath;
        final String memory;
        final int sectorSize;
        final String rwMode;

        FhContext(File loaderFile, String portPath, String memory, int sectorSize, String rwMode) {
            this.loaderFile = loaderFile;
            this.portPath = portPath;
            this.memory = memory;
            this.sectorSize = sectorSize;
            this.rwMode = rwMode;
        }
    }

    // 把当前所有 USB 设备的接口/端点布局写入运行日志（appendWorkLog → run.log），
    // 用于排查 qdl 选错接口/端点导致 bulk 双向静默（小米 SDM845 等）。
    private void logUsbDescriptorToWork(File runDir) {
        if (runDir == null) {
            return;
        }
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            appendWorkLog(runDir, "USB 诊断: UsbManager 不可用");
            return;
        }
        Map<String, UsbDevice> devices = manager.getDeviceList();
        appendWorkLog(runDir, "USB 诊断: 设备数=" + devices.size());
        for (UsbDevice device : devices.values()) {
            appendWorkLog(runDir, String.format(Locale.US,
                    "USB %04x:%04x class=%d/%d/%d 接口数=%d",
                    device.getVendorId(), device.getProductId(),
                    device.getDeviceClass(), device.getDeviceSubclass(),
                    device.getDeviceProtocol(), device.getInterfaceCount()));
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.US, "  接口#%d cls=%d/%d/%d alt=%d:",
                        intf.getId(), intf.getInterfaceClass(),
                        intf.getInterfaceSubclass(), intf.getInterfaceProtocol(),
                        intf.getAlternateSetting()));
                for (int j = 0; j < intf.getEndpointCount(); j++) {
                    UsbEndpoint ep = intf.getEndpoint(j);
                    sb.append(" [ep=0x").append(String.format(Locale.US, "%02x", ep.getAddress()))
                            .append(" ").append(usbDirName(ep.getDirection()))
                            .append(" ").append(usbTypeName(ep.getType()))
                            .append(" max=").append(ep.getMaxPacketSize()).append("]");
                }
                appendWorkLog(runDir, sb.toString());
            }
        }
    }

    private boolean isTargetUsbDevice(UsbDevice device, PortId target) {
        if (device == null || target == null) {
            return false;
        }
        try {
            int vid = Integer.parseInt(target.vid, 16);
            int pid = Integer.parseInt(target.pid, 16);
            return device.getVendorId() == vid && device.getProductId() == pid;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String usbDirName(int direction) {
        if (direction == UsbConstants.USB_DIR_IN) {
            return "in";
        }
        if (direction == UsbConstants.USB_DIR_OUT) {
            return "out";
        }
        return "unknown";
    }

    private String usbTypeName(int type) {
        if (type == UsbConstants.USB_ENDPOINT_XFER_CONTROL) {
            return "control";
        }
        if (type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            return "isoc";
        }
        if (type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
            return "bulk";
        }
        if (type == UsbConstants.USB_ENDPOINT_XFER_INT) {
            return "int";
        }
        return "unknown";
    }

    private String safeUsbString(UsbStringSupplier supplier) {
        try {
            String value = supplier.get();
            return value == null || value.isEmpty() ? "-" : value;
        } catch (SecurityException e) {
            return "no-permission";
        }
    }

    private String nullToDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private void logUsb(String message) {
        appendLog(message);
        if (usbTraceFile == null) {
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(usbTraceFile, true))) {
            writer.write(message);
            writer.write("\n");
        } catch (IOException ignored) {
        }
    }

    private interface UsbStringSupplier {
        String get();
    }
}
