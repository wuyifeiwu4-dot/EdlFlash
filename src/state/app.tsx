import React, {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useCallback,
} from 'react';
import {
  edl,
  RunSpec,
  GptEntry,
  QfilXml,
  LogLine,
  LogLevel,
  Progress,
  DeviceStatus,
  isDestructive,
} from '../native/bridge';

type LoaderState = {
  mode: 'builtin' | 'file';
  builtin?: {vendorDir: string; chip: string; devprgAsset: string};
  path?: string;
  pathName?: string;
};

export type AppState = {
  device: DeviceStatus;
  assetVersion: string;
  command: string;
  args: {arg1?: string; arg2?: string; arg3?: string};
  argPaths: {arg1Path?: string; arg2Path?: string; arg3Path?: string};
  outputName: string;
  edlPackagePath?: string;
  edlPackageName?: string;
  // qfil 多选：多个 rawprogram / patch 的真实路径与显示名
  rawprogramPaths?: string[];
  rawprogramNames?: string[];
  patchPaths?: string[];
  patchNames?: string[];
  loader: LoaderState;
  vip: {digestPath?: string; signPath?: string; authorized: boolean};
  options: Record<string, string | boolean | undefined>;
  partitions: GptEntry[];
  // QFIL 解析出的待刷分区(按 XML 分组)；qfilSkip 内的 `xml:part` 不刷，其余刷(默认全刷)
  qfilXmls: QfilXml[];
  qfilSkip: Set<string>;
  partitionMode: 'single' | 'multi';
  selectedPartition?: {name: string; lun: string};
  checkedKeys: Set<string>;
  logs: LogLine[];
  progress: Progress | null;
  busy: 'idle' | 'gpt' | 'run' | 'vip' | 'sign';
  confirm: {warning: string} | null;
};

export type AppActions = {
  setCommand: (id: string) => void;
  setArg: (k: 'arg1' | 'arg2' | 'arg3', v: string) => void;
  setOutputName: (v: string) => void;
  setArgFile: (k: 'arg1' | 'arg2' | 'arg3', path: string, name: string) => void;
  setEdlPackage: (path: string, name: string) => void;
  setQfilMulti: (
    kind: 'rawprogram' | 'patch',
    files: {path: string; name: string}[],
  ) => void;
  setOption: (k: string, v: string | boolean) => void;
  setLoaderBuiltin: (b: {
    vendorDir: string;
    chip: string;
    devprgAsset: string;
  }) => void;
  setLoaderPath: (path: string, name: string) => void;
  setVipFiles: (digestPath?: string, signPath?: string) => void;
  setPartitionMode: (m: 'single' | 'multi') => void;
  selectPartition: (name: string, lun: string) => void;
  toggleChecked: (key: string) => void;
  parseQfil: () => void;
  toggleQfilSkip: (key: string) => void;
  toggleQfilSkipBulk: (keys: string[], skip: boolean) => void;
  readGpt: () => void;
  run: () => void;
  runPartition: (cmd: 'r' | 'w' | 'e') => void;
  multiRead: () => void;
  stop: () => void;
  vipAuth: () => void;
  sign: () => void;
  clearLog: () => void;
  resolvePersist: (decision: number) => void;
};

const LOG_CAP = 2000;

// 瞬态选项键：一次性 SAF 路径与单次会话意图，不跨启动持久化（避免污染下次操作）
const TRANSIENT_OPTION_KEYS = [
  'signDir',
  'signKey',
  'signOutputDir',
  'signPartitions',
  'signChain',
  'signVerify',
  'signRegenSalt',
  'resetMode',
  'qfilSplit',
  'protectLun5',
  'skipWrite', // 演练开关不跨启动持久化，避免“以为真刷其实在演练”
  'skipStorageInit', // 跳过存储初始化属危险一次性意图，不跨启动持久化，避免残留导致下次刷写跳过初始化
];

function buildRunSpec(s: AppState): RunSpec {
  const multiRead =
    s.partitionMode === 'multi'
      ? Array.from(s.checkedKeys) // 完整 key "lun:name"，保留 LUN 供原生精确匹配，避免多 LUN 同名读错
      : undefined;
  // qfil 的输入只来自其专属 UI（整包/目录 edlPackagePath + rawprogram/patch 多选），不掺参数区。
  // 切换命令不清空 args/argPaths，若把残留值混给 qfil，引擎的 FILE_MULTI 参数会经 resolveArg
  // 把残留路径当成手选 rawprogram/patch，从而跳过整包自动识别，validateQfilInputs 对错误路径报错秒退。
  // qfil 只认整包/目录(edlPackagePath)，由原生按 rawprogram*.xml / patch*.xml 自动识别
  // (模式已排除 rawprogram*_BLANK_GPT.xml 这类清空分区表的特殊文件)，不再手动选文件
  const args =
    s.command === 'qfil'
      ? {}
      : {...s.args, ...s.argPaths};
  return {
    command: s.command,
    args,
    outputName: s.outputName,
    edlPackagePath: s.edlPackagePath,
    loader:
      s.loader.mode === 'builtin' && s.loader.builtin
        ? {builtin: s.loader.builtin}
        : s.loader.path
        ? {path: s.loader.path}
        : undefined,
    vip: {digestPath: s.vip.digestPath, signPath: s.vip.signPath},
    partitions: {
      selected: s.selectedPartition?.name,
      lun: s.selectedPartition?.lun,
      multiRead,
    },
    qfilSkip: s.command === 'qfil' ? Array.from(s.qfilSkip) : undefined,
    options: s.options,
  } as RunSpec;
}

// 缺图分区默认跳过集(对齐参考 IsSelected=fileExists)：现成镜像缺失即不勾选，避免原生缺图硬中止
// 致含缺图包整体刷写失败。super 等 willMerge 项 exists=true，不会进入跳过集。供解析与提交刷写共用(DRY)。
function buildQfilSkip(xmls: QfilXml[]): Set<string> {
  const skip = new Set<string>();
  for (const xml of xmls) {
    for (const p of xml.partitions) {
      if (!p.exists) {
        skip.add(`${xml.name}#${p.uid}`);
      }
    }
  }
  return skip;
}

const Ctx = createContext<{state: AppState; actions: AppActions} | null>(null);

export function AppProvider({children}: {children: React.ReactNode}) {
  const [state, setState] = useState<AppState>({
    device: {connected: false, rootAvailable: false},
    assetVersion: '',
    command: 'gpt',
    args: {},
    argPaths: {},
    outputName: '',
    loader: {mode: 'builtin'},
    vip: {authorized: false},
    options: {memory: 'ufs', fastMode: true, autoReboot: true, mergeSuper: true},
    partitions: [],
    qfilXmls: [],
    qfilSkip: new Set(),
    partitionMode: 'single',
    checkedKeys: new Set(),
    logs: [],
    progress: null,
    busy: 'idle',
    confirm: null,
  });

  // 用 ref 持有最新 state，供动作里 buildRunSpec 读取，避免闭包陈旧
  const ref = useRef(state);
  ref.current = state;
  const patch = useCallback(
    (p: Partial<AppState>) => setState(s => ({...s, ...p})),
    [],
  );

  // 订阅引擎事件
  useEffect(() => {
    edl
      .ensureAssetsReady()
      .then(r => patch({assetVersion: r.version}))
      .catch(e =>
        setState(s => ({
          ...s,
          logs: s.logs.concat([
            {text: '资源解包失败: ' + (e?.message ?? e), level: 'error'},
          ]),
        })),
      );
    edl.loadOptions().then(json => {
      try {
        const saved = JSON.parse(json);
        if (saved && typeof saved === 'object') {
          setState(s => ({...s, options: {...s.options, ...saved}}));
        }
      } catch (e) {
        setState(s => ({
          ...s,
          logs: s.logs.concat([
            {text: '已保存选项解析失败，已重置为默认', level: 'warn'},
          ]),
        }));
      }
    });
    edl.startDeviceMonitor(1000).catch(() => {});
    const subs = [
      edl.subscribe('onDeviceStatus', d =>
        setState(s => {
          // 监视线程每秒上报，状态未变时短路，避免无谓的整树重渲染
          const auth = d.vip ?? s.vip.authorized;
          if (
            d.connected === s.device.connected &&
            d.usbPath === s.device.usbPath &&
            d.vidPid === s.device.vidPid &&
            d.rootAvailable === s.device.rootAvailable &&
            auth === s.vip.authorized
          ) {
            return s;
          }
          return {...s, device: d, vip: {...s.vip, authorized: auth}};
        }),
      ),
      edl.subscribe('onProgress', p => patch({progress: p})),
      edl.subscribe('onLog', p => {
        if (p.clear) {
          patch({logs: []});
          return;
        }
        const lines = p.lines ?? [];
        if (!lines.length) {
          return;
        }
        setState(s => {
          const merged = s.logs.concat(lines);
          return {
            ...s,
            logs: merged.length > LOG_CAP ? merged.slice(-LOG_CAP) : merged,
          };
        });
      }),
      edl.subscribe('onPartitions', ({entries}) =>
        patch({
          partitions: entries,
          selectedPartition: entries[0]
            ? {name: entries[0].name, lun: entries[0].lun}
            : undefined,
        }),
      ),
      edl.subscribe('onConfirmPersist', ({warning}) =>
        patch({confirm: {warning}}),
      ),
    ];
    return () => {
      subs.forEach(u => u());
      edl.stopDeviceMonitor();
    };
  }, [patch]);

  const guard = useCallback(
    async (busy: AppState['busy'], fn: () => Promise<any>) => {
      if (ref.current.busy !== 'idle') {
        return;
      }
      patch({
        busy,
        progress: {percent: 0, label: '准备中', indeterminate: true},
      });
      try {
        await fn();
      } catch (e) {
        // 错误已由 onLog 事件呈现，这里仅吞掉拒绝以防未处理的 Promise rejection（开发期红屏）
      } finally {
        patch({busy: 'idle'});
      }
    },
    [patch],
  );

  const actions = useMemo<AppActions>(
    () => ({
      setCommand: id => patch({command: id}),
      setArg: (k, v) => setState(s => ({...s, args: {...s.args, [k]: v}})),
      setArgFile: (k, path, name) =>
        setState(s => ({
          ...s,
          args: {...s.args, [k]: name},
          argPaths: {...s.argPaths, [`${k}Path`]: path},
        })),
      setEdlPackage: (path, name) =>
        // 换包后旧的 QFIL 预览/勾选必须失效，否则弱 key 会作用到新包
        patch({
          edlPackagePath: path,
          edlPackageName: name,
          qfilXmls: [],
          qfilSkip: new Set(),
        }),
      setQfilMulti: (kind, files) =>
        patch({
          ...(kind === 'rawprogram'
            ? {
                rawprogramPaths: files.map(f => f.path),
                rawprogramNames: files.map(f => f.name),
              }
            : {
                patchPaths: files.map(f => f.path),
                patchNames: files.map(f => f.name),
              }),
          // 换 rawprogram/patch 后旧预览失效
          qfilXmls: [],
          qfilSkip: new Set(),
        }),
      setOutputName: v => patch({outputName: v}),
      setOption: (k, v) =>
        setState(s => {
          const options = {...s.options, [k]: v};
          // 仅持久化配置型选项，过滤掉瞬态键（一次性 SAF 路径与单次会话意图）
          const persist = Object.fromEntries(
            Object.entries(options).filter(
              ([key]) => !TRANSIENT_OPTION_KEYS.includes(key),
            ),
          );
          edl.saveOptions(JSON.stringify(persist));
          // protectLun5/qfilSplit 改变 QFIL 解析范围，旧预览/勾选失效需清除
          if (k === 'protectLun5' || k === 'qfilSplit') {
            return {...s, options, qfilXmls: [], qfilSkip: new Set()};
          }
          return {...s, options};
        }),
      setLoaderBuiltin: b => patch({loader: {mode: 'builtin', builtin: b}}),
      setLoaderPath: (path, name) =>
        patch({loader: {mode: 'file', path, pathName: name}}),
      setVipFiles: (digestPath, signPath) =>
        setState(s => ({...s, vip: {...s.vip, digestPath, signPath}})),
      setPartitionMode: m => patch({partitionMode: m}),
      selectPartition: (name, lun) => patch({selectedPartition: {name, lun}}),
      toggleChecked: key =>
        setState(s => {
          const next = new Set(s.checkedKeys);
          next.has(key) ? next.delete(key) : next.add(key);
          return {...s, checkedKeys: next};
        }),
      parseQfil: async () => {
        const pushLog = (text: string, level: LogLevel) =>
          setState(s => ({
            ...s,
            logs: s.logs.concat([{text, level}]).slice(-LOG_CAP),
          }));
        try {
          const r = await edl.parseQfilInputs(
            buildRunSpec({...ref.current, command: 'qfil'}),
          );
          // 仅在真正解析出分区时替换预览；空结果(原生忙/未解出)保留旧预览，不清空，但给出反馈避免"点了没反应"
          if (r && r.xmls && r.xmls.length > 0) {
            patch({qfilXmls: r.xmls, qfilSkip: buildQfilSkip(r.xmls)});
          } else {
            pushLog('未解析到待刷分区：请确认已选整包/解包目录(含 rawprogram*.xml)', 'warn');
          }
        } catch {
          pushLog('解析 QFIL 输入失败', 'error');
        }
      },
      toggleQfilSkip: key =>
        setState(s => {
          const next = new Set(s.qfilSkip);
          next.has(key) ? next.delete(key) : next.add(key);
          return {...s, qfilSkip: next};
        }),
      // skip=true 把全部 key 加入跳过集(全不选)，skip=false 全部移出(全选)
      toggleQfilSkipBulk: (keys, skip) =>
        setState(s => {
          const next = new Set(s.qfilSkip);
          for (const k of keys) {
            skip ? next.add(k) : next.delete(k);
          }
          return {...s, qfilSkip: next};
        }),
      clearLog: () => patch({logs: []}),
      readGpt: () =>
        guard('gpt', async () => {
          await edl.readPartitionTable(
            buildRunSpec({...ref.current, command: 'gpt'}),
          );
        }),
      run: () =>
        guard('run', async () => {
          let spec = buildRunSpec(ref.current);
          // qfil 未手动"解析"过即直接提交：在此刻惰性预填缺图跳过集，避免缺图分区被原生硬校验拒绝致
          // 整包秒退。解包成本仅在用户已决定刷写时发生(不在选包时强制解密 .ofp)。skip 直接塞进 spec——
          // patch 异步不会同步落地，沿用 ref.current.qfilSkip 仍是空集。
          if (
            ref.current.command === 'qfil' &&
            ref.current.qfilXmls.length === 0
          ) {
            try {
              const r = await edl.parseQfilInputs(
                buildRunSpec({...ref.current, command: 'qfil'}),
              );
              if (r && r.xmls && r.xmls.length > 0) {
                const skip = buildQfilSkip(r.xmls);
                patch({qfilXmls: r.xmls, qfilSkip: skip});
                spec = {...spec, qfilSkip: Array.from(skip)};
              }
            } catch {
              // 解析失败则按原 spec 继续，由原生 validateQfilInputs 给出错误
            }
          }
          await edl.run(spec);
        }),
      runPartition: cmd => {
        // 选中分区后一键提取/写入/擦除：以列表选中分区为准强制覆盖 arg1（清掉文件槽），
        // 对齐原版 applyPartitionSelection 的无条件覆盖；否则参数区残留的旧 arg1 会被
        // 引擎当成目标分区（引擎仅在 arg1 为空时才注入选中分区），导致读/写错分区。
        const sel = ref.current.selectedPartition;
        patch({command: cmd});
        guard('run', async () => {
          await edl.run(
            buildRunSpec({
              ...ref.current,
              command: cmd,
              args: {
                ...ref.current.args,
                arg1: sel?.name ?? ref.current.args.arg1,
              },
              argPaths: {...ref.current.argPaths, arg1Path: undefined},
            }),
          );
        });
      },
      multiRead: () =>
        guard('run', async () => {
          await edl.multiRead(buildRunSpec({...ref.current, command: 'r'}));
        }),
      vipAuth: () =>
        guard('vip', async () => {
          const r = await edl.vipAuth(buildRunSpec(ref.current));
          if (r && r.ok) {
            setState(s => ({...s, vip: {...s.vip, authorized: true}}));
          }
        }),
      sign: () =>
        guard('sign', async () => {
          await edl.sign(buildRunSpec(ref.current));
        }),
      stop: () => {
        edl.stop();
      },
      resolvePersist: (decision: number) => {
        patch({confirm: null});
        edl.resolveConfirm(decision);
      },
    }),
    [patch, guard],
  );

  return <Ctx.Provider value={{state, actions}}>{children}</Ctx.Provider>;
}

export function useApp() {
  const v = useContext(Ctx);
  if (!v) {
    throw new Error('useApp 必须在 AppProvider 内');
  }
  return v;
}
