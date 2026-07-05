/**
 * EDL Flash — iOS 风 React Native 外壳
 * @format
 */
import React, {useState} from 'react';
import {View, StatusBar, Pressable} from 'react-native';
import {GestureHandlerRootView} from 'react-native-gesture-handler';
import {SafeAreaProvider, useSafeAreaInsets} from 'react-native-safe-area-context';
import LinearGradient from 'react-native-linear-gradient';
import Animated, {FadeIn} from 'react-native-reanimated';

import {ThemeProvider, useTheme} from './src/theme/theme';
import {AppProvider, useApp, type AppState} from './src/state/app';
import {Txt, GlassBar, StatusChip, RunDock, LogSheet, ConfirmSheet} from './src/components';
import {isDestructive, COMMANDS} from './src/native/bridge';
import {FlashScreen, LoaderScreen, OptionsScreen, ToolsScreen, SettingsScreen, LicenseGate} from './src/screens';

type TabKey = 'flash' | 'loader' | 'options' | 'tools' | 'settings';
const TABS: {key: TabKey; label: string; glyph: string}[] = [
  {key: 'flash', label: '刷机', glyph: '⚡'},
  {key: 'loader', label: '引导', glyph: '📦'},
  {key: 'options', label: '选项', glyph: '⚙︎'},
  {key: 'tools', label: '工具', glyph: '🛠'},
  {key: 'settings', label: '设置', glyph: '⌘'},
];

function StatusCapsuleBar() {
  const {c, space} = useTheme();
  const insets = useSafeAreaInsets();
  const {state} = useApp();
  const root = state.device.rootAvailable;
  const conn = state.device.connected;
  // 显示引擎实际枚举到的 vid:pid（9008 未命中会回退 900e），缺失时回退默认 9008
  const vidPid = state.device.vidPid ?? '05c6:9008';
  return (
    <GlassBar edge="top" style={{paddingTop: insets.top + 6, paddingBottom: 8}}>
      <View style={{flexDirection: 'row', paddingHorizontal: space.lg, gap: space.sm}}>
        <View style={{flex: 1}}>
          <StatusChip
            text={root ? 'Root 已授权' : 'Root 未检测'}
            tone={root ? 'success' : 'neutral'}
          />
        </View>
        <View style={{flex: 1, alignItems: 'flex-end'}}>
          <StatusChip
            text={conn ? `${vidPid} 已连接` : '设备未连接'}
            tone={conn ? 'success' : 'neutral'}
            pulse={conn}
          />
        </View>
      </View>
    </GlassBar>
  );
}

function TabBar({tab, onTab}: {tab: TabKey; onTab: (t: TabKey) => void}) {
  const {c, space} = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <GlassBar edge="bottom" style={{paddingBottom: insets.bottom + 4, paddingTop: 8}}>
      <View style={{flexDirection: 'row'}}>
        {TABS.map(t => {
          const active = t.key === tab;
          return (
            <Pressable
              key={t.key}
              onPress={() => onTab(t.key)}
              style={{flex: 1, alignItems: 'center', paddingVertical: 4}}>
              <Txt style={{fontSize: 22, color: active ? c.accent : c.labelTertiary}}>
                {t.glyph}
              </Txt>
              <Txt
                variant="caption2"
                color={active ? c.accent : c.labelTertiary}
                style={{marginTop: 2}}>
                {t.label}
              </Txt>
            </Pressable>
          );
        })}
      </View>
    </GlassBar>
  );
}

function runLabel(command: string): string {
  const def = COMMANDS.find(c => c.id === command);
  if (!def) return '执行';
  if (def.category === 'write') return '写入设备';
  if (def.category === 'erase') return '擦除分区';
  if (command === 'gpt') return '读取分区表';
  return '开始读取';
}

// 主执行键就绪性：仅对输入来源唯一、无替代的命令做门禁，避免与原生参数注入/校验重复或过度禁用。
// qfil 的唯一输入是整包/解包目录(edlPackagePath)，未选时点击必然秒退；其余命令交原生校验。
function runReady(s: AppState): boolean {
  if (s.command === 'qfil') {
    return !!s.edlPackagePath;
  }
  return true;
}

function Shell() {
  const {c} = useTheme();
  const insets = useSafeAreaInsets();
  const [tab, setTab] = useState<TabKey>('flash');
  const {state, actions} = useApp();
  const running = state.busy !== 'idle';
  const destructive = isDestructive(state.command);
  // 底部操作栏总高（TabBar + 可能的 RunDock），日志面板抬到其上
  const bottomBarH = insets.bottom + 64 + (tab === 'flash' ? 86 : 0);

  return (
    <View style={{flex: 1}}>
      <LinearGradient
        colors={[c.bgGradTop, c.bgGradBottom]}
        style={{position: 'absolute', left: 0, right: 0, top: 0, bottom: 0}}
      />
      <StatusBar
        translucent
        backgroundColor="transparent"
        barStyle={c.scheme === 'dark' ? 'light-content' : 'dark-content'}
      />
      <StatusCapsuleBar />

      <Animated.View key={tab} entering={FadeIn.duration(260)} style={{flex: 1}}>
        {tab === 'flash' && <FlashScreen />}
        {tab === 'loader' && <LoaderScreen />}
        {tab === 'options' && <OptionsScreen />}
        {tab === 'tools' && <ToolsScreen />}
        {tab === 'settings' && <SettingsScreen />}
      </Animated.View>

      {/* 日志面板：抬到底部操作栏之上，避免遮挡其点击 */}
      <LogSheet
        logs={state.logs}
        progress={state.progress}
        onClear={actions.clearLog}
        bottomInset={bottomBarH}
      />

      {/* 底部操作栏：绝对定位 + 最高层级，确保 Tab/执行键永远可点 */}
      <View
        style={{position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 30, elevation: 30}}>
        {tab === 'flash' ? (
          <RunDock
            command={COMMANDS.find(c => c.id === state.command)?.label ?? state.command}
            label={runLabel(state.command)}
            running={running}
            progress={state.progress}
            destructive={destructive}
            disabled={!runReady(state)}
            onRun={() => (state.command === 'gpt' ? actions.readGpt() : actions.run())}
            onStop={actions.stop}
          />
        ) : null}
        <TabBar tab={tab} onTab={setTab} />
      </View>

      {state.confirm ? (
        <ConfirmSheet
          visible
          title="危险操作确认"
          message={state.confirm.warning}
          confirmLabel="继续刷写"
          destructive
          slideToConfirm
          neutralLabel="跳过 persist，继续刷其余"
          onNeutral={() => actions.resolvePersist(1)}
          onConfirm={() => actions.resolvePersist(0)}
          onClose={() => actions.resolvePersist(2)}
        />
      ) : null}
    </View>
  );
}

export default function App() {
  return (
    <GestureHandlerRootView style={{flex: 1}}>
      <SafeAreaProvider>
        <ThemeProvider>
          <LicenseGate>
            <AppProvider>
              <Shell />
            </AppProvider>
          </LicenseGate>
        </ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
