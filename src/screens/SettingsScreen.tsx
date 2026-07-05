import React from 'react';
import {ScrollView, View} from 'react-native';
import Animated, {FadeInDown} from 'react-native-reanimated';
import {useTheme, type Appearance} from '../theme/theme';
import {useApp} from '../state/app';
import {GroupedSection, ListRow, Txt, SegmentedControl} from '../components';

const APP_VERSION = '1.0.0';

type Language = 'zh' | 'system';

export function SettingsScreen() {
  const {space, appearance, setAppearance} = useTheme();
  const {state, actions} = useApp();
  // 语言选择持久化到选项，重启后保留；真正切换 UI 文案需后续接入 i18n。
  const language = (state.options.language as Language) ?? 'zh';

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
        设置
      </Txt>

      <Animated.View entering={fade()}>
        <GroupedSection header="外观">
          <View style={{padding: space.md}}>
            <SegmentedControl
              segments={[
                {key: 'auto', label: '自动'},
                {key: 'light', label: '浅色'},
                {key: 'dark', label: '深色'},
              ]}
              value={appearance}
              onChange={key => setAppearance(key as Appearance)}
            />
          </View>
        </GroupedSection>
      </Animated.View>

      <Animated.View entering={fade()}>
        <GroupedSection header="语言" footer="语言切换功能将在后续接入 i18n 后生效">
          <View style={{padding: space.md}}>
            <SegmentedControl
              segments={[
                {key: 'zh', label: '中文'},
                {key: 'system', label: '跟随系统'},
              ]}
              value={language}
              onChange={key => actions.setOption('language', key)}
            />
          </View>
        </GroupedSection>
      </Animated.View>

      <Animated.View entering={fade()}>
        <GroupedSection header="关于">
          <ListRow label="资产版本" value={state.assetVersion || '加载中…'} />
          <ListRow label="应用版本" value={APP_VERSION} />
        </GroupedSection>
      </Animated.View>
    </ScrollView>
  );
}
