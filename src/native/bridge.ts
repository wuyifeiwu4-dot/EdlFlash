/**
 * EDL 引擎桥接层。
 * 真机走原生 EdlFlashModule（见 android 原生模块）；原生模块缺失时（Metro 纯前端调试）
 * 自动回落到 mock，让 iOS 风界面无需真机也能完整演示与开发。
 */
import {NativeEventEmitter, NativeModules} from 'react-native';

export type LogLevel = 'info' | 'success' | 'error' | 'warn';
export type LogLine = {text: string; level: LogLevel};
export type Progress = {
  percent: number;
  label?: string;
  speedMBps?: number;
  indeterminate?: boolean;
};
export type DeviceStatus = {
  connected: boolean;
  usbPath?: string;
  vidPid?: string;
  rootAvailable: boolean;
  vip?: boolean;
};
export type GptEntry = {
  name: string;
  lun: string;
  startSector?: string;
  numSectors?: string;
  sectorSize?: string;
};

// QFIL 解析出的待刷分区（来自 rawprogram*.xml），exists=镜像在目录中是否就位
export type QfilPartition = {
  name: string;
  lun: string;
  startSector?: string;
  numSectors?: string;
  sectorSize?: string;
  filename: string;
  sparse: boolean;
  exists: boolean;
  // super 等刷写期才合并产出的分区：现成镜像缺失但可合并，视为可获得(exists=true)并提示"将合并"
  willMerge?: boolean;
  // 稳定唯一标识 lun:start_sector:filename，前端 key/勾选与原生 skip 匹配共用，避免同名项联动
  uid: string;
};
export type QfilXml = {name: string; partitions: QfilPartition[]};

export type CommandCategory = 'read' | 'write' | 'erase' | 'config';
export type CommandDef = {id: string; label: string; desc: string; category: CommandCategory};

// 命令目录（供可搜索分组 PickerSheet 使用；破坏性=write/erase）
export const COMMANDS: CommandDef[] = [
  {id: 'gpt', label: '读取分区表', desc: '读取设备 GPT 并列出分区', category: 'read'},
  {id: 'r', label: '读取镜像', desc: '按分区名读取为 .img', category: 'read'},
  {id: 'rl', label: '读取全部分区', desc: '逐分区导出整机', category: 'read'},
  {id: 'rf', label: '读取整个 LUN', desc: '整块物理分区读出', category: 'read'},
  {id: 'rs', label: '读取扇区', desc: '指定起始扇区与数量', category: 'read'},
  {id: 'getstorageinfo', label: '存储信息', desc: '读取 UFS/eMMC 信息', category: 'read'},
  {id: 'getactiveslot', label: '当前槽位', desc: '读取 A/B 活动槽', category: 'read'},
  {id: 'peek', label: '读内存', desc: '从地址读取数据', category: 'read'},
  {id: 'memorydump', label: '内存转储', desc: '导出内存区域', category: 'read'},
  {id: 'w', label: '写入分区', desc: '把镜像写入指定分区', category: 'write'},
  {id: 'wl', label: '写入全部', desc: '批量写入多个分区', category: 'write'},
  {id: 'wf', label: '写入整个 LUN', desc: '整块写入', category: 'write'},
  {id: 'ws', label: '写入扇区', desc: '从指定扇区写入', category: 'write'},
  {id: 'qfil', label: 'QFIL 整包刷写', desc: 'rawprogram/patch 整包', category: 'write'},
  {id: 'poke', label: '写内存', desc: '向地址写入数据', category: 'write'},
  {id: 'e', label: '擦除分区', desc: '擦除指定分区', category: 'erase'},
  {id: 'es', label: '擦除扇区', desc: '按扇区擦除', category: 'erase'},
  {id: 'ep', label: '擦除分区扇区', desc: '分区内扇区擦除', category: 'erase'},
  {id: 'setactiveslot', label: '设置活动槽', desc: '切换 A/B 槽', category: 'config'},
  {id: 'provision', label: 'Provision', desc: 'UFS 配置', category: 'config'},
  {id: 'xml', label: '发送 XML', desc: '发送 Firehose XML 文件', category: 'config'},
  {id: 'rawxml', label: '原始 XML', desc: '发送自定义 XML', category: 'config'},
  {id: 'reset', label: '重启设备', desc: '复位并重启', category: 'config'},
  {id: 'nop', label: 'NOP 探测', desc: '探测 Firehose 存活', category: 'config'},
];

export function isDestructive(id: string): boolean {
  const def = COMMANDS.find(c => c.id === id);
  return def?.category === 'write' || def?.category === 'erase';
}

export type PickedFile = {path: string; name: string};

export type RunSpec = {
  command: string;
  args: {
    arg1?: string;
    arg2?: string;
    arg3?: string;
    arg1Path?: string;
    arg2Path?: string;
    arg3Path?: string;
    arg1Paths?: string[];
    arg2Paths?: string[];
  };
  outputName?: string;
  edlPackagePath?: string;
  loader?: {
    builtin?: {vendorDir: string; chip: string; devprgAsset: string};
    path?: string;
  };
  vip?: {digestPath?: string; signPath?: string};
  partitions?: {selected?: string; multiRead?: string[]; lun?: string};
  // QFIL 取消勾选的分区("xml名:分区名")，刷写时由原生从 rawprogram 移除
  qfilSkip?: string[];
  options: Record<string, string | boolean | undefined>;
};

export interface EdlBridge {
  ensureAssetsReady(): Promise<{version: string; ready: boolean}>;
  getRootStatus(): Promise<{available: boolean; status: string}>;
  startDeviceMonitor(intervalMs?: number): Promise<void>;
  stopDeviceMonitor(): Promise<void>;
  run(spec: RunSpec): Promise<{ok: boolean; reason?: string; outputs?: string[]}>;
  stop(): Promise<void>;
  // 真机经 runOp resolve {ok,started,vipAuthorized}；分区数据通过 onPartitions 事件回传，不在此 Promise。
  readPartitionTable(spec: RunSpec): Promise<{ok: boolean; started?: boolean; vipAuthorized?: boolean}>;
  // 解析 QFIL 输入(整包/rawprogram)列出各 XML 的待刷分区，供勾选裁剪；读不到时 xmls 为空
  parseQfilInputs(spec: RunSpec): Promise<{xmls: QfilXml[]}>;
  vipAuth(spec: RunSpec): Promise<{ok: boolean; reason?: string}>;
  sign(spec: RunSpec): Promise<{ok: boolean}>;
  listBuiltinVendors(): Promise<{label: string; dir: string}[]>;
  listBuiltinChips(vendorDir: string): Promise<string[]>;
  listBuiltinLoaders(
    vendorDir: string,
    chip: string,
  ): Promise<{devprg: string; digest?: string; sign?: string; preferred?: boolean}[]>;
  multiRead(spec: RunSpec): Promise<{ok: boolean}>;
  /** 回应破坏性 persist 确认：0=继续 1=跳过 2=取消 */
  resolveConfirm(decision: number): Promise<void>;
  /** 系统文件/目录选择（SAF），取消返回 null */
  pickFile(): Promise<PickedFile | null>;
  pickMultiple(): Promise<PickedFile[]>;
  pickDirectory(): Promise<PickedFile | null>;
  /** 选项持久化（瞬态项不传） */
  saveOptions(json: string): Promise<void>;
  loadOptions(): Promise<string>;
  subscribe(event: 'onLog', cb: (p: {lines?: LogLine[]; session?: number; clear?: boolean}) => void): () => void;
  subscribe(event: 'onProgress', cb: (p: Progress) => void): () => void;
  subscribe(event: 'onDeviceStatus', cb: (p: DeviceStatus) => void): () => void;
  subscribe(event: 'onPartitions', cb: (p: {entries: GptEntry[]}) => void): () => void;
  subscribe(event: 'onConfirmPersist', cb: (p: {warning: string}) => void): () => void;
}

// ---- mock 实现（无真机时驱动 UI） ----
type Listener = (p: any) => void;

class MockBridge implements EdlBridge {
  private listeners: Record<string, Set<Listener>> = {};
  private session = 1;

  private emit(event: string, payload: any) {
    this.listeners[event]?.forEach(l => l(payload));
  }

  subscribe(event: any, cb: any): () => void {
    (this.listeners[event] ??= new Set()).add(cb);
    return () => this.listeners[event]?.delete(cb);
  }

  async ensureAssetsReady() {
    return {version: '2026-06-03-01', ready: true};
  }
  async getRootStatus() {
    return {available: true, status: 'granted'};
  }
  async startDeviceMonitor() {
    setTimeout(
      () =>
        this.emit('onDeviceStatus', {
          connected: true,
          usbPath: '/dev/bus/usb/001/004',
          vidPid: '05c6:9008',
          rootAvailable: true,
        } as DeviceStatus),
      900,
    );
  }
  async stopDeviceMonitor() {}
  async resolveConfirm() {}
  async multiRead(spec: RunSpec) {
    return this.simulate('批量读取');
  }
  async pickFile() {
    return {path: '/sdcard/Download/sample.img', name: 'sample.img'};
  }
  async pickMultiple() {
    return [{path: '/sdcard/Download/sample.img', name: 'sample.img'}];
  }
  async pickDirectory() {
    return {path: '/sdcard/Download/avb', name: 'avb'};
  }
  private opts = '{}';
  async saveOptions(json: string) {
    this.opts = json;
  }
  async loadOptions() {
    return this.opts;
  }

  async readPartitionTable() {
    const names = ['boot_a', 'boot_b', 'system_a', 'vendor_a', 'super', 'persist', 'modem_a', 'abl_a', 'xbl_a', 'userdata'];
    const entries: GptEntry[] = names.map((name, i) => ({
      name,
      lun: i < 6 ? '0' : '1',
      startSector: String(2048 + i * 1024),
      numSectors: String(65536 * (i + 1)),
      sectorSize: '4096',
    }));
    this.emit('onPartitions', {entries});
    return {ok: true};
  }

  async parseQfilInputs() {
    return {
      xmls: [
        {
          name: 'rawprogram0.xml',
          partitions: [
            {name: 'boot_a', lun: '0', numSectors: '131072', sectorSize: '4096', filename: 'boot.img', sparse: false, exists: true, uid: '0:131072:boot.img'},
            {name: 'vendor_boot_a', lun: '0', numSectors: '196608', sectorSize: '4096', filename: 'vendor_boot.img', sparse: false, exists: true, uid: '0:262144:vendor_boot.img'},
            {name: 'super', lun: '0', numSectors: '2097152', sectorSize: '4096', filename: 'super.img', sparse: true, exists: true, uid: '0:524288:super.img'},
          ],
        },
        {
          name: 'rawprogram5.xml',
          partitions: [
            {name: 'modem_a', lun: '5', numSectors: '262144', sectorSize: '4096', filename: 'modem.img', sparse: false, exists: false, uid: '5:0:modem.img'},
          ],
        },
      ],
    };
  }

  async run(spec: RunSpec) {
    return this.simulate(`${spec.command} ${spec.args.arg1 ?? ''}`.trim());
  }
  async vipAuth() {
    await this.simulate('VIP 授权');
    return {ok: true};
  }
  async sign() {
    await this.simulate('AVB 签名');
    return {ok: true};
  }

  private simulate(label: string): Promise<{ok: boolean}> {
    this.session += 1;
    const session = this.session;
    this.emit('onProgress', {percent: 0, label: '准备中', indeterminate: true});
    this.emit('onLog', {lines: [{text: `> 开始 ${label}`, level: 'info'}], session});
    const steps = ['等待 EDL 设备…', '发送引导 (Sahara)', 'Firehose configure', '传输数据', '校验完成'];
    return new Promise(resolve => {
      let i = 0;
      const tick = () => {
        if (i >= steps.length) {
          this.emit('onProgress', {percent: 100, label: '完成'});
          this.emit('onLog', {lines: [{text: '✓ 完成', level: 'success'}], session});
          resolve({ok: true});
          return;
        }
        const pct = Math.round(((i + 1) / steps.length) * 100);
        this.emit('onProgress', {percent: pct, label: steps[i], speedMBps: 18 + i * 6});
        this.emit('onLog', {lines: [{text: steps[i], level: 'info'}], session});
        i += 1;
        setTimeout(tick, 650);
      };
      setTimeout(tick, 500);
    });
  }

  async stop() {
    this.emit('onLog', {lines: [{text: '已停止', level: 'warn'}], session: this.session});
    this.emit('onProgress', {percent: 0, label: '已停止'});
  }

  async listBuiltinVendors() {
    return [{label: '欧加 (OPlus)', dir: 'oplus'}, {label: '小米 (Xiaomi)', dir: 'xiaomi'}];
  }
  async listBuiltinChips() {
    return ['SDM845', 'SM8550', 'SM8650'];
  }
  async listBuiltinLoaders() {
    return [{devprg: 'prog_firehose_ddr.elf', digest: 'digest.bin', sign: 'sig.bin'}];
  }
}

// ---- 原生实现（真机），缺失时回落 mock ----
function makeNative(): EdlBridge {
  const native = (NativeModules as any).EdlFlash;
  if (!native) {
    // 发布包里原生模块缺失=构建/注册异常，必须立即暴露，绝不能用 mock 伪装成功而不写数据。
    if (!__DEV__) {
      throw new Error('原生模块 EdlFlash 未注册，构建异常');
    }
    return new MockBridge();
  }
  const emitter = new NativeEventEmitter(native);
  const call = <T>(name: string, ...args: any[]): Promise<T> => native[name](...args);
  return {
    ensureAssetsReady: () => call('ensureAssetsReady'),
    getRootStatus: () => call('getRootStatus'),
    startDeviceMonitor: (i?: number) => call('startDeviceMonitor', i ?? 1000),
    stopDeviceMonitor: () => call('stopDeviceMonitor'),
    multiRead: (s: RunSpec) => call('multiRead', s),
    resolveConfirm: (d: number) => call('resolveConfirm', d),
    pickFile: () => call('pickFile'),
    pickMultiple: () => call('pickMultiple'),
    pickDirectory: () => call('pickDirectory'),
    saveOptions: (json: string) => call('saveOptions', json),
    loadOptions: () => call('loadOptions'),
    run: (s: RunSpec) => call('run', s),
    stop: () => call('stop'),
    readPartitionTable: (s: RunSpec) => call('readPartitionTable', s),
    parseQfilInputs: async (s: RunSpec) => {
      try {
        return JSON.parse(await call<string>('parseQfilInputs', s));
      } catch {
        return {xmls: []};
      }
    },
    vipAuth: (s: RunSpec) => call('vipAuth', s),
    sign: (s: RunSpec) => call('sign', s),
    listBuiltinVendors: () => call('listBuiltinVendors'),
    listBuiltinChips: (v: string) => call('listBuiltinChips', v),
    listBuiltinLoaders: (v: string, c: string) => call('listBuiltinLoaders', v, c),
    subscribe: (event: any, cb: any) => {
      const sub = emitter.addListener(event, cb);
      return () => sub.remove();
    },
  } as EdlBridge;
}

export const edl: EdlBridge = makeNative();
