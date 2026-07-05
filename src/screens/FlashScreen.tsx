import React, {useState} from 'react';
import {ActivityIndicator, Pressable, ScrollView, View} from 'react-native';
import Animated, {FadeInDown} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {useApp} from '../state/app';
import {
  GroupedSection,
  ListRow,
  Txt,
  InlineField,
  SegmentedControl,
  IOSToggle,
  PartitionGroup,
  PrimaryButton,
  PickerSheet,
} from '../components';
import {COMMANDS, isDestructive, edl} from '../native/bridge';

/**
 * 各命令的参数标签，须与引擎 COMMAND_SPECS（FlashEngine.java）逐项对齐：
 * file 指向哪个槽用文件选择器，dir 指向哪个槽用目录选择器，output=true 表示需要输出文件名。
 * LUN 不在此处出现，统一由 选项页『默认 LUN』(options.lun) 提供。
 */
type ArgSpec = {
  arg1?: string;
  arg2?: string;
  arg3?: string;
  file?: 'arg1' | 'arg2';
  dir?: 'arg1';
  output?: boolean;
};

const ARG_LABELS: Record<string, ArgSpec> = {
  gpt: {output: true},
  r: {arg1: '分区名', output: true},
  rl: {arg1: '输出目录名'},
  rf: {output: true},
  rs: {arg1: '起始扇区', arg2: '扇区数量', output: true},
  peek: {arg1: '地址', arg2: '长度', output: true},
  memorydump: {arg1: '分区列表（可选，逗号/空格分隔）'},
  w: {arg1: '分区名', arg2: '镜像文件', file: 'arg2'},
  wl: {arg1: '分区目录', dir: 'arg1'},
  wf: {arg1: '全盘镜像文件', file: 'arg1'},
  ws: {arg1: '起始扇区', arg2: '镜像文件', file: 'arg2'},
  poke: {arg1: '偏移地址', arg2: '输入镜像文件', file: 'arg2'},
  e: {arg1: '分区名'},
  es: {arg1: '起始扇区', arg2: '扇区数量'},
  ep: {arg1: '分区名', arg2: '扇区数量'},
  setactiveslot: {arg1: '槽位 (a/b)'},
  xml: {arg1: 'XML 文件', file: 'arg1'},
  provision: {arg1: 'XML 文件', file: 'arg1'},
  rawxml: {arg1: 'XML 内容'},
  // 无参命令显式声明空 spec，避免落到默认 {arg1:'参数'} 渲染出误导性输入框
  getstorageinfo: {},
  getactiveslot: {},
  nop: {},
  reset: {},
};

const READ_COMMANDS = new Set(
  COMMANDS.filter(cmd => cmd.category === 'read').map(cmd => cmd.id),
);

const argSpecFor = (command: string): ArgSpec =>
  ARG_LABELS[command] ?? {arg1: '参数'};

/** QFIL 待刷分区容量估算：扇区数×扇区大小，按 KiB/MiB/GiB 归一。 */
function formatQfilCap(numSectors?: string, sectorSize?: string): string | null {
  const n = Number(numSectors);
  const s = Number(sectorSize);
  if (!Number.isFinite(n) || !Number.isFinite(s) || n <= 0 || s <= 0) {
    return null;
  }
  const mib = (n * s) / (1024 * 1024);
  if (mib >= 1024) {
    return `${(mib / 1024).toFixed(2)} GiB`;
  }
  if (mib >= 1) {
    return `${mib.toFixed(1)} MiB`;
  }
  return `${(mib * 1024).toFixed(0)} KiB`;
}

export function FlashScreen() {
  const {c, space} = useTheme();
  const {state, actions} = useApp();
  const [pickerOpen, setPickerOpen] = useState(false);

  const command = state.command;
  const commandDef = COMMANDS.find(cmd => cmd.id === command);
  const spec = argSpecFor(command);
  const hasPartitions = state.partitions.length > 0;

  // 错峰入场：每个 section 按出现顺序递增 index。
  let sectionIndex = 0;
  const fade = () => FadeInDown.delay(sectionIndex++ * 50).springify();

  return (
    <ScrollView
      style={{backgroundColor: 'transparent'}}
      contentContainerStyle={{
        paddingTop: space.md,
        paddingHorizontal: space.lg,
        paddingBottom: 220,
      }}
      keyboardShouldPersistTaps="handled">
      <Txt variant="largeTitle" style={{marginBottom: space.lg}}>
        刷机
      </Txt>

      <Animated.View entering={fade()}>
        <GroupedSection header="设备">
          <ListRow
            label="读取分区表"
            subtitle="读取 GPT 并列出全部分区"
            onPress={actions.readGpt}
            accessory={
              state.busy === 'gpt' ? (
                <ActivityIndicator color={c.accent} />
              ) : undefined
            }
            showChevron={state.busy !== 'gpt'}
          />
        </GroupedSection>
      </Animated.View>

      <Animated.View entering={fade()}>
        <GroupedSection header="命令" footer={commandDef?.desc}>
          <ListRow
            label="当前命令"
            value={commandDef?.label ?? command}
            destructive={isDestructive(command)}
            showChevron
            onPress={() => setPickerOpen(true)}
          />
        </GroupedSection>
      </Animated.View>

      {command === 'qfil' ? (
        <Animated.View entering={fade()}>
          <GroupedSection
            header="刷机包 (OFP/OPS)"
            footer="选择整包文件或已解包目录，自动识别 rawprogram/patch">
            <ListRow
              label="刷机包文件"
              value={state.edlPackageName ?? '未选择'}
              accessory={
                <Txt variant="headline" color={c.accent}>
                  选择
                </Txt>
              }
              onPress={async () => {
                const f = await edl.pickFile();
                if (f) actions.setEdlPackage(f.path, f.name);
              }}
            />
            <ListRow
              label="或选择解包目录"
              accessory={
                <Txt variant="headline" color={c.accent}>
                  选择
                </Txt>
              }
              onPress={async () => {
                const f = await edl.pickDirectory();
                if (f) actions.setEdlPackage(f.path, f.name);
              }}
            />
            <ListRow
              label="逐分区执行"
              accessory={
                <IOSToggle
                  value={state.options.qfilSplit === true}
                  onValueChange={v => actions.setOption('qfilSplit', v)}
                />
              }
            />
            <ListRow
              label="保护 LUN5"
              accessory={
                <IOSToggle
                  value={state.options.protectLun5 === true}
                  onValueChange={v => actions.setOption('protectLun5', v)}
                />
              }
            />
            <ListRow
              label="合并 super"
              subtitle="缺 super.img 时由分片/动态分区合并生成（关则只用现成 super.img）"
              accessory={
                <IOSToggle
                  value={state.options.mergeSuper !== false}
                  onValueChange={v => actions.setOption('mergeSuper', v)}
                />
              }
            />
            <ListRow
              label="列出待刷分区"
              subtitle="解析 rawprogram 列出将刷写的分区，可取消勾选排除"
              accessory={
                <Txt variant="headline" color={c.accent}>
                  解析
                </Txt>
              }
              onPress={actions.parseQfil}
            />
          </GroupedSection>
        </Animated.View>
      ) : null}

      {command === 'qfil' && state.qfilXmls.length > 0
        ? state.qfilXmls.map(xml => {
            const keys = xml.partitions.map(p => `${xml.name}#${p.uid}`);
            // 缺图分区原生校验门必拒，批量"全选"只针对有镜像/可合并(exists)分区，避免重选缺图制造
            // "✓刷写/镜像缺失"矛盾并触发整包秒退；allFlash 据可选项重算，否则全选缺图包后按钮永不切换
            const selectable = xml.partitions
              .filter(p => p.exists)
              .map(p => `${xml.name}#${p.uid}`);
            const flashCount = keys.filter(k => !state.qfilSkip.has(k)).length;
            const allFlash =
              selectable.length > 0 &&
              selectable.every(k => !state.qfilSkip.has(k));
            return (
              <Animated.View key={xml.name} entering={fade()}>
                <GroupedSection
                  header={xml.name}
                  headerRight={
                    <Pressable
                      onPress={() =>
                        actions.toggleQfilSkipBulk(selectable, allFlash)
                      }
                      hitSlop={8}>
                      <Txt variant="subhead" color={c.accent}>
                        {allFlash ? '全不选' : '全选'}
                      </Txt>
                    </Pressable>
                  }
                  footer={`将刷写 ${flashCount}/${xml.partitions.length} 个分区`}>
                  {xml.partitions.map(p => {
                    const key = `${xml.name}#${p.uid}`;
                    const flash = !state.qfilSkip.has(key);
                    const cap = formatQfilCap(p.numSectors, p.sectorSize);
                    const meta = `LUN${p.lun}${cap ? ' · ' + cap : ''}${
                      p.sparse ? ' · sparse' : ''
                    }${
                      p.willMerge ? ' · 将合并' : p.exists ? '' : ' · 镜像缺失'
                    }`;
                    return (
                      <ListRow
                        key={key}
                        label={p.name}
                        subtitle={meta}
                        destructive={!p.exists}
                        accessory={
                          <Txt
                            variant="headline"
                            color={flash ? c.accent : c.labelTertiary}>
                            {flash ? '✓ 刷写' : '跳过'}
                          </Txt>
                        }
                        onPress={() => actions.toggleQfilSkip(key)}
                      />
                    );
                  })}
                </GroupedSection>
              </Animated.View>
            );
          })
        : null}

      {hasPartitions ? (
        <Animated.View entering={fade()}>
          <View style={{marginBottom: space.xl}}>
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                marginBottom: space.md,
                marginLeft: space.xs,
              }}>
              <Txt
                variant="subhead"
                color={c.labelTertiary}
                style={{
                  flex: 1,
                  textTransform: 'uppercase',
                  letterSpacing: 0.6,
                }}>
                分区
              </Txt>
              <View style={{width: 160}}>
                <SegmentedControl
                  segments={[
                    {key: 'single', label: '单选'},
                    {key: 'multi', label: '批量'},
                  ]}
                  value={state.partitionMode}
                  onChange={key =>
                    actions.setPartitionMode(key as 'single' | 'multi')
                  }
                />
              </View>
            </View>
            <PartitionGroup
              entries={state.partitions}
              mode={state.partitionMode}
              selectedKey={
                state.selectedPartition
                  ? `${state.selectedPartition.lun}:${state.selectedPartition.name}`
                  : undefined
              }
              checkedKeys={state.checkedKeys}
              onSelectSingle={actions.selectPartition}
              onToggle={actions.toggleChecked}
            />
            {state.partitionMode === 'single' ? (
              <View
                style={{
                  flexDirection: 'row',
                  gap: space.sm,
                  marginTop: space.md,
                }}>
                <View style={{flex: 1}}>
                  <PrimaryButton
                    title="提取该分区"
                    variant="accent"
                    disabled={!state.selectedPartition || state.busy !== 'idle'}
                    onPress={() => actions.runPartition('r')}
                  />
                </View>
                <View style={{flex: 1}}>
                  <PrimaryButton
                    title="写入该分区"
                    variant="destructive"
                    disabled={!state.selectedPartition}
                    onPress={() => actions.setCommand('w')}
                  />
                </View>
              </View>
            ) : (
              <View style={{marginTop: space.md}}>
                <PrimaryButton
                  title={`批量读取所选 (${state.checkedKeys.size})`}
                  variant="accent"
                  disabled={
                    state.checkedKeys.size === 0 || state.busy !== 'idle'
                  }
                  onPress={actions.multiRead}
                />
              </View>
            )}
          </View>
        </Animated.View>
      ) : null}

      {command !== 'qfil' ? (
        <Animated.View entering={fade()}>
          <GroupedSection header="参数">
            {spec.arg1 && spec.file !== 'arg1' && spec.dir !== 'arg1' ? (
              <InlineField
                label={spec.arg1}
                value={state.args.arg1 ?? ''}
                onChangeText={v => actions.setArg('arg1', v)}
                placeholder={spec.arg1}
              />
            ) : null}
            {spec.arg2 && spec.file !== 'arg2' ? (
              <InlineField
                label={spec.arg2}
                value={state.args.arg2 ?? ''}
                onChangeText={v => actions.setArg('arg2', v)}
                placeholder={spec.arg2}
              />
            ) : null}
            {spec.file ? (
              <ListRow
                label={`选择${spec[spec.file] ?? '文件'}`}
                value={state.args[spec.file] || '未选择'}
                accessory={
                  <Txt variant="headline" color={c.accent}>
                    浏览
                  </Txt>
                }
                onPress={async () => {
                  const f = await edl.pickFile();
                  if (f) actions.setArgFile(spec.file!, f.path, f.name);
                }}
              />
            ) : null}
            {spec.dir ? (
              <ListRow
                label={`选择${spec[spec.dir] ?? '目录'}`}
                value={state.args[spec.dir] || '未选择'}
                accessory={
                  <Txt variant="headline" color={c.accent}>
                    浏览
                  </Txt>
                }
                onPress={async () => {
                  const f = await edl.pickDirectory();
                  if (f) actions.setArgFile(spec.dir!, f.path, f.name);
                }}
              />
            ) : null}
            {spec.arg3 ? (
              <InlineField
                label={spec.arg3}
                value={state.args.arg3 ?? ''}
                onChangeText={v => actions.setArg('arg3', v)}
                placeholder={spec.arg3}
              />
            ) : null}
            {!spec.arg1 && !spec.arg2 && !spec.arg3 ? (
              <ListRow label="此命令无需参数" subtitle="直接执行即可" />
            ) : null}
          </GroupedSection>
        </Animated.View>
      ) : null}

      {READ_COMMANDS.has(command) && command !== 'r' ? (
        <Animated.View entering={fade()}>
          <GroupedSection header="输出" footer="读取结果将以该文件名导出">
            <InlineField
              label="输出文件名"
              value={state.outputName}
              onChangeText={actions.setOutputName}
              placeholder="edl_output.img"
              mono
            />
          </GroupedSection>
        </Animated.View>
      ) : null}

      <PickerSheet
        visible={pickerOpen}
        onClose={() => setPickerOpen(false)}
        title="选择命令"
        items={COMMANDS}
        selectedId={command}
        searchable
        onSelect={id => {
          actions.setCommand(id);
          setPickerOpen(false);
        }}
      />
    </ScrollView>
  );
}
