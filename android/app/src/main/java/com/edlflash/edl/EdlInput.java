package com.edlflash.edl;

import java.util.List;

/**
 * 输入快照：取代原 MainActivity 对全部 UI 控件的读取（getText/isChecked/getSelectedItemPosition）。
 * 由 RN 原生模块从 JS 传来的 run-spec 构造，引擎内的 UI 垫片据此回填，使 180+ 业务方法逐字保留。
 * 字段与原工程一一对应（仅列真正被消费的有效输入；死字段不含）。
 */
public class EdlInput {
    // ---- 命令与参数（文本）----
    public String command = "gpt";   // 对应 COMMANDS[]，runEdl 入口转回 index
    public String arg1 = "";
    public String arg2 = "";
    public String arg3 = "";
    public String outputName = "";
    public String deviceModel = "";   // 一加 projid (ModelVerifyPrjName)，动态 token 授权用
    public String oplusSerial = "";   // 一加授权的 SoC 序列号，留空则 qdl 取默认值
    public String lun = "";
    public String skipPartitions = "";
    public String maxPayload = "";
    public String partitions = "";
    public String tcpPort = "";
    public String rawXml = "";
    public String resetMode = "";
    public String sectorSize = "";
    public String memory = "ufs";
    public String signPartitions = "";
    public String pid = "";
    public String port = "";
    public String suCommand = "";
    public String vid = "";

    // ---- 开关 ----
    public boolean skipStorageInit;
    public boolean signRegenSalt;
    public boolean signVerify;
    public boolean signChain;
    public boolean fastMode;
    public boolean autoReboot;
    public boolean qfilSplit;
    public boolean protectLun5;
    public boolean mergeSuper = true; // 是否在缺 super.img 时由分片/组件合并生成(默认开，保持原行为)
    public boolean dryRun;          // 演练：映射 qdl --dry-run，仅模拟不落盘
    public boolean oplusTokenAuth;  // 启用一加动态 token 授权(demacia/setprojmodel/setswprojmodel)

    // ---- 文件路径（SAF 已由 JS/原生侧解析为可用路径）----
    public String loaderPath;
    public String digestPath;
    public String signPath;
    public String arg1Path;
    public String arg2Path;
    public String arg3Path;
    public String edlPackagePath;
    public String signInputDir;
    public String signOutputDir;
    public String signKeyPath;
    public List<String> arg1Paths;
    public List<String> arg2Paths;

    // ---- 内置 loader（headless 直接给定厂商/芯片/devprg 文件名，三件套由引擎 findBuiltinAuthFiles 派生）----
    public boolean builtinSelected;
    public String builtinVendorDir;
    public String builtinChip;
    public String builtinDevprgFileName;

    // ---- 分区选择（取代 RecyclerView 选中态）----
    public String selectedPartition;
    public List<String> multiReadPartitions;
    // QFIL 取消勾选的分区（"xml名:分区名"），刷写时从 rawprogram 移除
    public List<String> qfilSkip;
}
