export const meta = {
  name: 'edlflash-deep-audit',
  description: 'EdlFlash 三层深度审计：22 子系统对齐参考项目发现 + 逐发现对抗验证',
  phases: [
    { title: 'Discover', detail: '22 子系统并行发现，对齐权威参考' },
    { title: 'Verify', detail: '逐发现独立对抗验证' },
  ],
}

const CONTEXT = [
  '# EdlFlash 高通 EDL 刷机工具 — 三层架构',
  '1. RN/TS UI: /root/EdlFlash/src/ (FlashScreen.tsx, components/PartitionGroup.tsx, state/app.tsx, native/bridge.ts)',
  '2. Java 编排层: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java (13146 行)',
  '3. 原生 qdl C: /root/edl_flash_app/third_party/qdl/*.c (经 EdlFlash gradle 直接引用编译进 APK)',
  '',
  '# 权威参考项目(本地)',
  '- 通用 EDL: /root/edl/edlclient/Library/*.py (bkerler edl, Python 权威: firehose.py gpt.py sparse.py sahara.py)',
  '- qdl C: /root/edl_refs/qdl/*.c (linux-msm 上游)',
  '- fh_loader: /root/edl_flash_app/third_party/fh_loader.c, /root/edl_refs/Fh-loader/fh_loader/fh_loader.c',
  '- OPlus 专用: /root/edl_refs/OplusEdlTool/Services/*.cs (RawProgramXmlProcessor/OpsDecryptor/OfpDecryptor/GptParser/SuperMergeService)',
  '',
  '# 已知背景(前 6 轮已修, 勿重复报告这些已修项)',
  '- super 多分片应叠加(overlaySuperSegments)非拼接; GPT CRC poly 必须 0xedb88320; 扇区探针已移除改 storage seed; sparse CRC32 chunk(0xCAC4)已支持; OPlus VIP 是 auth-only(认证后无逐包 hash)。',
  '- 真机日志在 /root/EdlFlash/log/ (vip_*.log run_*.log gpt_*.log rawprogram0.xml readback.xml partitions.txt)。设备=OPlus SM8650 8Gen3, UFS 4096 字节扇区, 6 LUN。',
  '',
  '# 你的任务',
  '对齐指定参考, 找出你负责子系统的隐性 bug / 与参考逻辑不一致 / 缺失的健壮性, 以及值得移植的增强点。',
  '必须真实 Read 代码与参考再下结论, 给出 file:line。严禁臆测。聚焦能影响真机刷写正确性/可靠性的实质问题, 不要风格挑剔。',
].join('\n');

const FINDING_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    subsystem: { type: 'string' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          id: { type: 'string' },
          type: { type: 'string', enum: ['bug', 'enhancement'] },
          title: { type: 'string' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          file: { type: 'string' },
          line: { type: 'string' },
          current: { type: 'string' },
          reference: { type: 'string' },
          fix: { type: 'string' },
          confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
        },
        required: ['id', 'type', 'title', 'severity', 'file', 'current', 'reference', 'fix', 'confidence'],
      },
    },
  },
  required: ['subsystem', 'findings'],
};

const VERDICT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    verdict: { type: 'string', enum: ['confirmed', 'rejected', 'uncertain'] },
    severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
    reasoning: { type: 'string' },
    refinedFix: { type: 'string' },
    regressionRisk: { type: 'string' },
  },
  required: ['verdict', 'severity', 'reasoning', 'refinedFix', 'regressionRisk'],
};

const TASKS = [
  { key: 'qdl-firehose-configure', prompt: '审计 qdl firehose.c 的 configure/sector-size/seed/会话握手。重点: firehose_detect_and_configure(1473) 在 VIP 复用会话首条 configure 收到 "ERROR: Failed to run the last command -1" 的重试是否健壮; firehose_check_sector_size(551); --no-sahara 复用会话 configure 时序。对齐 /root/edl/edlclient/Library/firehose.py 与 /root/edl_refs/qdl/firehose.c。文件: /root/edl_flash_app/third_party/qdl/firehose.c' },
  { key: 'qdl-firehose-ops', prompt: '审计 qdl firehose.c 的 program/erase/patch 命令构造与 ACK 处理(firehose_program/firehose_erase/firehose_patch/firehose_execute_ops)。对齐 /root/edl/edlclient/Library/firehose.py 与 /root/edl_refs/qdl/firehose.c 与 /root/edl_flash_app/third_party/fh_loader.c。文件: /root/edl_flash_app/third_party/qdl/firehose.c' },
  { key: 'qdl-gpt', prompt: '审计 qdl gpt.c 的 GPT 解析/CRC32/多 LUN 扫描/分区表读取。重点: gpt_crc32_update(poly 须 0xedb88320)、header/数组 CRC、part_entry_size 边界、gpt_load_tables 有界扫描、脏分区隔离。对齐 /root/edl/edlclient/Library/gpt.py 与 /root/edl_refs/qdl/gpt.c。文件: /root/edl_flash_app/third_party/qdl/gpt.c' },
  { key: 'qdl-sparse', prompt: '审计 qdl sparse.c 的 sparse→raw: chunk 类型(RAW/FILL/DONT_CARE/CRC32 0xCAC4)、越界守卫、file_hdr/chunk_hdr 长度校验、out_sectors 越界。对齐 AOSP libsparse 与 /root/edl/edlclient/Library/sparse.py 与 /root/edl_refs/qdl/sparse.c。文件: /root/edl_flash_app/third_party/qdl/sparse.c' },
  { key: 'qdl-program', prompt: '审计 qdl program.c 的 program tag 加载/raw 回退/super 处理/sparse 判定/未刷死循环防护。对齐 /root/edl_refs/qdl/program.c。文件: /root/edl_flash_app/third_party/qdl/program.c' },
  { key: 'qdl-read', prompt: '审计 qdl read.c 的 readback 命令/缓冲/越界。对齐 /root/edl/edlclient/Library/firehose.py cmd_read 与 /root/edl_refs/qdl/read.c。文件: /root/edl_flash_app/third_party/qdl/read.c' },
  { key: 'qdl-sahara', prompt: '审计 qdl sahara.c 的 Sahara 协议(HELLO/READ64/END/DONE)与 OPlus skip-sahara 路径、unsolicited HELLO nudge。对齐 /root/edl/edlclient/Library/sahara.py 与 /root/edl_refs/qdl/sahara.c。文件: /root/edl_flash_app/third_party/qdl/sahara.c' },
  { key: 'qdl-vip', prompt: '审计 qdl vip.c + oplus_vip.c 的 OPlus VIP 认证: digest.elf/sign.bin 裸二进制发送、drain_response 仅扫 ACK/NAK 是否漏检 ERROR、transfercfg/verify/sha256init 时序。对齐 /root/edl_refs/OplusEdlTool 与 /root/edl/oplus12r-edl。文件: /root/edl_flash_app/third_party/qdl/vip.c /root/edl_flash_app/third_party/qdl/oplus_vip.c' },
  { key: 'qdl-main', prompt: '审计 qdl.c 主流程: 参数解析、env 契约(EDL_VIP_*/QDL_USB_PATH/QDL_AUTO_RESET)、--no-sahara 复用、reset 守卫、storage seed 调用点。对齐 /root/edl_refs/qdl/qdl.c。文件: /root/edl_flash_app/third_party/qdl/qdl.c /root/edl_flash_app/third_party/qdl/qdl.h' },
  { key: 'qdl-io', prompt: '审计 qdl file.c/file.h/util.c/usb.c 的 IO 正确性: read_exact 区分 EOF/错误不零填充、qdl_file_load 读满校验、usb refcount/超时。对齐 /root/edl_refs/qdl/file.c /root/edl_refs/qdl/util.c。文件: /root/edl_flash_app/third_party/qdl/file.c /root/edl_flash_app/third_party/qdl/util.c /root/edl_flash_app/third_party/qdl/usb.c' },
  { key: 'java-gpt', prompt: '审计 FlashEngine.java 的 GPT 解析与分区表读取: parseGptMainFile/扇区大小自适应(512/4096)、多 LUN 单会话读、estimateGptMainProbeSectors、备份 GPT。对齐 qdl gpt.c 与 /root/edl/edlclient/Library/gpt.py 与 /root/edl_refs/OplusEdlTool/Services/GptParser.cs。关注 /root/EdlFlash/log/gpt_*.log。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java' },
  { key: 'java-super', prompt: '审计 FlashEngine.java 的 super 合并: shouldOverlaySuperSegments/overlaySuperSegments(多分片带洞 sparse 叠加)、concatSuperSegments、buildSuperFromDef(lpmake super-name/block-size/alignment/virtual-ab)、missingDeclaredImage 缺图失败、空占位跳过。对齐 AOSP simg2img/lpmake 与 /root/edl_refs/OplusEdlTool/Services/SuperMergeService.cs。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java' },
  { key: 'java-ops', prompt: '审计 FlashEngine.java OpsDecryptor(.ops 解密): SBOX(2048字节)/MBOX(62字节)字节模型、轮数 asbox[0x3c]、key_custom ceil(L/16) 末块零填充、directChildElements。字节级对齐 /root/edl 的 opscrypto.py(先 find /root/edl -name "opscrypto*") 与 /root/edl_refs/OplusEdlTool/Services/OpsDecryptor.cs。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java' },
  { key: 'java-ofp', prompt: '审计 FlashEngine.java OfpDecryptor(.ofp 解密): extractZip Zip Slip 防护、pagesize 探测(0x200/0x1000)、AES key/iv 派生、MD5。对齐 /root/edl_refs/OplusEdlTool/Services/OfpDecryptor.cs 与 bkerler oppo_decrypt(find /root/edl -name "*ofp*")。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java' },
  { key: 'java-vip-remap', prompt: '审计 FlashEngine.java 的 VIP 签名 digest 表 + 设备 GPT 重映射: deviceGpt 复合键 lun:name、per-LUN 覆盖 fail-closed、useDeviceGpt 时跳过包内 patch.xml、重映射 start_sector/num 与签名表一致。对齐 /root/edl_refs/OplusEdlTool/Services/RawProgramXmlProcessor.cs。关注 /root/EdlFlash/log/partitions.txt 与 rawprogram0.xml。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java' },
  { key: 'java-read', prompt: '审计 FlashEngine.java 的 read/readback/skip: writeQdlReadXml(requireLabel)、runFhReadAll best-effort、parseSkipPartitions glob、runFhReadFull 两段式 _lunN。对齐 /root/edl/edlclient/Library/firehose.py cmd_read/rl 与 qdl read.c。关注 /root/EdlFlash/log/readback.xml。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java' },
  { key: 'java-failure-probe', prompt: '重点审计 FlashEngine.java 失败检测/健康探针/设备日志处理(用户主 bug 区)。outputHasFailureInternal(11857)/isDeviceLogLine(11592)/isDeviceFatalLine(11598)/isProbeFailureLine(11569)/shouldIgnoreFailureLineForOutput(11903)/probeVipSessionHealth(5522)。已知根因: qdl --debug 多行 XML 把 <log value="ERROR:..."/> 单独成行, 不以 firehose read:/log: 开头 → isDeviceLogLine 漏判 → 含 error 误判失败 → nop 探针误失败。验证此根因并找出所有类似续行泄漏(<response> NAK 续行、其它 <log> chatter)。比对 /root/EdlFlash/log/vip_*.log line 161-343。确保修复不吞真正 <response value="NAK">。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java' },
  { key: 'java-slot', prompt: '审计 FlashEngine.java 的 setactiveslot/setbootablestoragedrive/reset/A-B 槽: AB_FLAG_OFFSET=6/SLOT_ACTIVE、applySlotToLun per-LUN 幂等 isLunOnSlot、GPT 属性 flags 改写+CRC 回写主/备份、setbootable 错误传播。对齐 /root/edl/edlclient/Library/firehose.py cmd_setactiveslot/getactiveslot。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java' },
  { key: 'java-qfil', prompt: '审计+增强 FlashEngine.java 的 QFIL XML 解析/预览/待刷分区: parseRawProgram/dedupeRawprogramVariants/resolveQfilInputs/buildQfilSkipSet/QFIL uid(lun:start_sector:filename)/缺图默认跳过。增强点(用户要求): 解析 XML 后将待刷分区列入分区表可勾选、列出已解析 XML。对齐 /root/edl_refs/OplusEdlTool/Services/RawProgramXmlProcessor.cs。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java + /root/EdlFlash/src/native/bridge.ts' },
  { key: 'rn-ui', prompt: '审计+增强 RN/TS UI: FlashScreen.tsx/PartitionGroup.tsx/state/app.tsx/native/bridge.ts。重点: QFIL 缺图默认跳过、批量勾选、分区名搜索、待刷分区勾选列表、已解析 XML 列出、错误展示是否把良性"扇区大小不匹配/发送xml失败"暴露给用户。文件: /root/EdlFlash/src/screens/FlashScreen.tsx /root/EdlFlash/src/components/PartitionGroup.tsx /root/EdlFlash/src/state/app.tsx /root/EdlFlash/src/native/bridge.ts' },
  { key: 'feature-mining', prompt: '特性挖掘: 通读所有参考项目, 列出 EdlFlash 未实现但值得移植的实用功能(全部 type=enhancement)。重点扫 /root/edl_refs/OplusEdlTool/Services/*.cs + MainWindow.axaml.cs、/root/edl/edlclient/Library/firehose_client.py、/root/edl_refs/qdl/*.c、/root/edl_flash_app/third_party/fh_loader.c。例: 分区表勾选刷写、已解析 XML 列表、读单分区、fixgpt、erase/format、provision、xml batch、进度/校验展示。每项给参考 file:line 与价值。' },
  { key: 'log-forensics', prompt: '日志取证: 精读 /root/EdlFlash/log/ 全部日志, 每个异常对应回代码路径, 判断良性 chatter vs 真实 bug。重点: "ERROR: Failed to run the last command -1"(首 configure)、"Mode= Invalid value"、"EnableFlash not found"、"DevprgRSAVerify verify signature failed"、"VIP 会话健康探针未通过"、run 双 configure、gpt 读取完整性。判断 OPlus 首 configure -1 是否可在 qdl 源头消除(参考 OplusEdlTool 是否同会话 auth+flash)。文件: /root/EdlFlash/android/app/src/main/java/com/edlflash/edl/FlashEngine.java + /root/edl_flash_app/third_party/qdl/firehose.c + /root/EdlFlash/log/' },
];

function isVerifiable(f) {
  return f.type === 'bug' && (f.severity === 'critical' || f.severity === 'high' || f.severity === 'medium');
}

function sevRankOf(s) {
  if (s === 'critical') return 0;
  if (s === 'high') return 1;
  if (s === 'medium') return 2;
  return 3;
}

phase('Discover');
log('启动 ' + TASKS.length + ' 个子系统发现 agent');

const results = await pipeline(
  TASKS,
  function (t) {
    return agent(CONTEXT + '\n\n## 你负责的子系统\n' + t.prompt, {
      label: 'discover:' + t.key,
      phase: 'Discover',
      model: 'sonnet',
      schema: FINDING_SCHEMA,
    });
  },
  function (discovery, t) {
    if (!discovery || !discovery.findings || !discovery.findings.length) {
      return { task: t.key, verified: [], enhancements: [] };
    }
    const toVerify = discovery.findings.filter(isVerifiable);
    const passthrough = discovery.findings.filter(function (f) { return !isVerifiable(f); });
    return parallel(toVerify.map(function (f) {
      return function () {
        const vp = CONTEXT + '\n\n## 对抗验证以下发现(独立 Read 实际代码+参考, 默认怀疑)\n'
          + '子系统: ' + discovery.subsystem + '\n标题: ' + f.title + '\n严重度: ' + f.severity
          + '\n位置: ' + f.file + ':' + (f.line || '?') + '\n当前行为: ' + f.current
          + '\n参考做法: ' + f.reference + '\n建议修复: ' + f.fix
          + '\n\n请验证: (1) 真 bug 吗? Read ' + f.file + ' 确认当前行为; (2) 参考真的不同吗? Read 参考确认; (3) 修复会否回归? 给精确 file:line 修复方案。证伪给 rejected。';
        return agent(vp, {
          label: 'verify:' + t.key + ':' + f.id,
          phase: 'Verify',
          model: 'sonnet',
          schema: VERDICT_SCHEMA,
        }).then(function (v) { return { finding: f, verdict: v }; }).catch(function () { return null; });
      };
    })).then(function (verdicts) {
      return { task: t.key, subsystem: discovery.subsystem, verified: verdicts.filter(Boolean), enhancements: passthrough };
    });
  }
);

const confirmed = [];
const uncertain = [];
const enhancements = [];
for (const r of results.filter(Boolean)) {
  if (r.enhancements) {
    for (const e of r.enhancements) enhancements.push(Object.assign({ task: r.task }, e));
  }
  for (const v of (r.verified || [])) {
    if (!v || !v.verdict) continue;
    const item = Object.assign({ task: r.task, subsystem: r.subsystem }, v.finding, {
      verdict: v.verdict.verdict,
      finalSeverity: v.verdict.severity,
      reasoning: v.verdict.reasoning,
      refinedFix: v.verdict.refinedFix,
      regressionRisk: v.verdict.regressionRisk,
    });
    if (v.verdict.verdict === 'confirmed') confirmed.push(item);
    else if (v.verdict.verdict === 'uncertain') uncertain.push(item);
  }
}

confirmed.sort(function (a, b) { return sevRankOf(a.finalSeverity) - sevRankOf(b.finalSeverity); });

log('审计完成: confirmed=' + confirmed.length + ' uncertain=' + uncertain.length + ' enhancements=' + enhancements.length);
return { confirmed: confirmed, uncertain: uncertain, enhancements: enhancements };
