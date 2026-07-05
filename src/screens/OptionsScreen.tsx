import React, {useState} from 'react';
import {ScrollView, KeyboardTypeOptions} from 'react-native';
import Animated, {FadeInDown} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {useApp} from '../state/app';
import {GroupedSection, ListRow, Txt, InlineField, IOSToggle, PickerSheet} from '../components';
import type {PickerItem} from '../components/PickerSheet';

const asText = (v: string | boolean | undefined) => String(v ?? '');
const asBool = (v: string | boolean | undefined) => v === true;

// 复位模式预设（值对应引擎 runFhResetCommand 接受的 mode）
const RESET_MODES: PickerItem[] = [
  {id: 'reset', label: '正常重启', category: 'config'},
  {id: 'edl', label: '重启到 EDL (9008)', category: 'config'},
  {id: 'fastboot', label: '重启到 fastboot', category: 'config'},
  {id: 'recovery', label: '重启到 recovery', category: 'config'},
  {id: 'off', label: '关机', category: 'config'},
];

export function OptionsScreen() {
  const {space} = useTheme();
  const {state, actions} = useApp();
  const opt = state.options;
  const [resetPicker, setResetPicker] = useState(false);

  const toggleRow = (
    key: string,
    label: string,
    subtitle?: string,
    tone?: 'accent' | 'success' | 'destructive',
  ) => (
    <ListRow
      label={label}
      subtitle={subtitle}
      destructive={tone === 'destructive'}
      accessory={
        <IOSToggle
          value={asBool(opt[key])}
          onValueChange={v => actions.setOption(key, v)}
          tone={tone === 'destructive' ? 'destructive' : tone}
        />
      }
    />
  );

  const field = (
    key: string,
    label: string,
    placeholder?: string,
    keyboardType?: KeyboardTypeOptions,
    mono?: boolean,
  ) => (
    <InlineField
      label={label}
      value={asText(opt[key])}
      onChangeText={v => actions.setOption(key, v)}
      placeholder={placeholder}
      keyboardType={keyboardType}
      mono={mono}
    />
  );

  const resetLabel = RESET_MODES.find(m => m.id === asText(opt.resetMode))?.label ?? '正常重启';

  let sectionIndex = 0;
  const fade = () => FadeInDown.delay(sectionIndex++ * 50).springify();

  return (
    <ScrollView
      style={{backgroundColor: 'transparent'}}
      contentContainerStyle={{paddingTop: space.md, paddingHorizontal: space.lg, paddingBottom: 240}}
      keyboardShouldPersistTaps="handled">
      <Txt variant="largeTitle" style={{marginBottom: space.lg}}>
        连接与选项
      </Txt>

      <Animated.View entering={fade()}>
        <GroupedSection header="极速与重启" footer="配置项自动保存，下次启动保留">
          {toggleRow('fastMode', '极速模式', '提高负载传输并发', 'success')}
          {toggleRow('autoReboot', '完成后重启', '操作结束自动复位设备')}
          <ListRow
            label="复位模式"
            value={resetLabel}
            showChevron
            onPress={() => setResetPicker(true)}
          />
        </GroupedSection>
      </Animated.View>

      <Animated.View entering={fade()}>
        <GroupedSection header="Root" footer="留空默认 su；可指定 su 路径或带参数，如 /system/bin/su 或 su -c">
          {field('suCommand', 'su 路径 / 命令', 'su')}
        </GroupedSection>
      </Animated.View>

      <Animated.View entering={fade()}>
        <GroupedSection header="设备识别" footer="留空则自动探测 9008 设备">
          {field('vid', 'VID', '05c6')}
          {field('pid', 'PID', '9008')}
          {field('port', '端口', '自动', 'numeric')}
        </GroupedSection>
      </Animated.View>

      <Animated.View entering={fade()}>
        <GroupedSection header="调试" footer="跳过写入仅做演练，不会真正改动设备数据">
          {toggleRow('skipStorageInit', '跳过存储初始化')}
          {toggleRow('skipWrite', '跳过写入（演练）', '不落盘的安全演练模式', 'destructive')}
        </GroupedSection>
      </Animated.View>

      <Animated.View entering={fade()}>
        <GroupedSection header="存储与扇区" footer="全局默认值；命令需要时由其取用">
          {field('memory', '存储类型', 'ufs / emmc')}
          {field('lun', '默认 LUN', '0', 'numeric')}
          {/* 最大负载旋钮已移除：实际 payload 由设备 configure 协商(VIP 路径固定 1MB)，
              该值不被 qdl 使用，暴露会误导用户(见审计 EDL_MAXPAYLOAD 幽灵契约) */}
          {field('sectorSize', '扇区大小', '4096', 'numeric')}
        </GroupedSection>
      </Animated.View>

      <PickerSheet
        visible={resetPicker}
        onClose={() => setResetPicker(false)}
        title="复位模式"
        items={RESET_MODES}
        selectedId={asText(opt.resetMode) || 'reset'}
        onSelect={id => {
          actions.setOption('resetMode', id);
          setResetPicker(false);
        }}
      />
    </ScrollView>
  );
}
