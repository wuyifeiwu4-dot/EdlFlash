import React from 'react';
import {ScrollView, View} from 'react-native';
import Animated, {FadeInDown} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {useApp} from '../state/app';
import {edl} from '../native/bridge';
import {GroupedSection, ListRow, Txt, InlineField, IOSToggle, PrimaryButton} from '../components';

const asText = (v: string | boolean | undefined) => String(v ?? '');
const asBool = (v: string | boolean | undefined) => v === true;

export function ToolsScreen() {
  const {c, space} = useTheme();
  const {state, actions} = useApp();
  const opt = state.options;

  const toggleRow = (key: string, label: string, subtitle?: string) => (
    <ListRow
      label={label}
      subtitle={subtitle}
      accessory={<IOSToggle value={asBool(opt[key])} onValueChange={v => actions.setOption(key, v)} />}
    />
  );

  // 真实 SAF 选择：目录→pickDirectory，文件→pickFile，直接使用真实路径不复制到私有目录
  const pickRow = (key: string, label: string, kind: 'file' | 'dir') => (
    <ListRow
      label={label}
      value={opt[key] ? asText(opt[key]) : '未选择'}
      accessory={
        <Txt variant="headline" color={c.accent}>
          选择
        </Txt>
      }
      onPress={async () => {
        const f = kind === 'dir' ? await edl.pickDirectory() : await edl.pickFile();
        if (f) actions.setOption(key, f.path);
      }}
    />
  );

  let sectionIndex = 0;
  const fade = () => FadeInDown.delay(sectionIndex++ * 50).springify();

  return (
    <ScrollView
      style={{backgroundColor: 'transparent'}}
      contentContainerStyle={{paddingTop: space.md, paddingHorizontal: space.lg, paddingBottom: 240}}
      keyboardShouldPersistTaps="handled">
      <Txt variant="largeTitle" style={{marginBottom: space.lg}}>
        工具
      </Txt>

      <Animated.View entering={fade()}>
        <GroupedSection header="AVB 签名" footer="对指定分区做 AVB 签名，可选链式分区与重新生成 salt">
          {pickRow('signDir', '签名目录', 'dir')}
          {pickRow('signKey', '私钥文件（可选）', 'file')}
          {pickRow('signOutputDir', '导出目录（可选）', 'dir')}
          <InlineField
            label="分区列表（空=自动）"
            value={asText(opt.signPartitions)}
            onChangeText={v => actions.setOption('signPartitions', v)}
            placeholder="boot init_boot vendor_boot"
            mono
          />
          {toggleRow('signChain', '链式签名', '生成链式分区描述符')}
          {toggleRow('signVerify', '仅验证', '只校验不写回签名')}
          {toggleRow('signRegenSalt', '重新生成 salt')}
        </GroupedSection>
      </Animated.View>

      <Animated.View entering={fade()}>
        <View>
          <PrimaryButton
            title={asBool(opt.signVerify) ? '开始验证' : '开始签名'}
            variant="accent"
            loading={state.busy === 'sign'}
            onPress={actions.sign}
          />
        </View>
      </Animated.View>
    </ScrollView>
  );
}
