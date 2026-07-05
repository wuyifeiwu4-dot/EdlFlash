import React from 'react';
import {View} from 'react-native';
import Animated, {
  useAnimatedStyle,
  useDerivedValue,
  useSharedValue,
  withRepeat,
  withSequence,
  withSpring,
  withTiming,
} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {Progress} from '../native/bridge';
import {Txt} from './Txt';
import {Pressy} from './Pressy';
import {PrimaryButton} from './PrimaryButton';
import {GlassBar} from './GlassBar';

type Props = {
  command: string;
  label: string;
  running: boolean;
  progress: Progress | null;
  disabled?: boolean;
  destructive?: boolean;
  onRun: () => void;
  onStop: () => void;
};

/** 吸底毛玻璃执行台：空闲显示命令与主按钮，运行中切换为停止并浮顶部进度条。 */
export function RunDock({
  command,
  label,
  running,
  progress,
  disabled,
  destructive,
  onRun,
  onStop,
}: Props) {
  const {c, space, radius} = useTheme();

  // 不定态：percent<0 或引擎显式标记（准备中/等待设备/握手等无百分比阶段）
  const indeterminate =
    running && (progress?.indeterminate || (progress?.percent ?? 0) < 0);

  // 确定态浮条宽度（0~100% → 0~1），跟随 progress.percent 平滑过渡
  const pct = clampPercent(progress?.percent);
  const widthRatio = useDerivedValue(() =>
    withTiming(running && !indeterminate ? pct / 100 : indeterminate ? 1 : 0, {
      duration: 300,
    }),
  );
  // 不定态时浮条整条铺满并循环脉冲，给出“进行中”的视觉，对齐原生 setIndeterminate(true)
  const pulse = useSharedValue(0.5);
  React.useEffect(() => {
    pulse.value = indeterminate
      ? withRepeat(withTiming(1, {duration: 700}), -1, true)
      : withTiming(running ? 1 : 0, {duration: 200});
  }, [indeterminate, running, pulse]);
  const barStyle = useAnimatedStyle(() => ({
    width: `${widthRatio.value * 100}%`,
    opacity: pulse.value,
  }));

  // 禁用态点击：translateX 抖动
  const shake = useSharedValue(0);
  const shakeStyle = useAnimatedStyle(() => ({
    transform: [{translateX: shake.value}],
  }));
  const triggerShake = () => {
    shake.value = withSequence(
      withSpring(-6, {damping: 8, stiffness: 600}),
      withSpring(6, {damping: 8, stiffness: 600}),
      withSpring(0, {damping: 12, stiffness: 400}),
    );
  };


  return (
    <GlassBar edge="bottom" style={{overflow: 'hidden'}}>
      {/* 顶部 2px 进度浮条 */}
      <View style={{height: 2, backgroundColor: c.separator}}>
        <Animated.View
          style={[
            {
              height: 2,
              backgroundColor: destructive ? c.destructive : c.accent,
            },
            barStyle,
          ]}
        />
      </View>

      <Animated.View
        style={[
          {
            flexDirection: 'row',
            alignItems: 'center',
            paddingHorizontal: space.lg,
            paddingTop: space.md,
            paddingBottom: space.lg,
          },
          shakeStyle,
        ]}>
        {/* 左：迷你状态 */}
        <View style={{flex: 1, marginRight: space.md}}>
          <View style={{flexDirection: 'row', alignItems: 'center'}}>
            {destructive ? (
              <View
                style={{
                  width: 7,
                  height: 7,
                  borderRadius: 4,
                  backgroundColor: c.destructive,
                  marginRight: space.sm,
                }}
              />
            ) : null}
            <Txt variant="footnote" color={c.labelTertiary} numberOfLines={1}>
              {running ? progress?.label ?? '执行中' : '命令'}
            </Txt>
          </View>
          <Txt variant="headline" numberOfLines={1} style={{marginTop: 2}}>
            {command}
          </Txt>
        </View>

        {/* 右：主按钮（运行中变停止，圆角更大） */}
        <View
          style={{
            minWidth: 140,
            borderRadius: running ? radius.chip : radius.button,
            overflow: 'hidden',
          }}>
          {running ? (
            <PrimaryButton title="停止" variant="destructive" onPress={onStop} />
          ) : disabled ? (
            // 禁用态：按钮置灰且不可点，覆盖层接管点击以触发抖动反馈
            <Pressy onPress={triggerShake} scaleTo={1}>
              <View pointerEvents="none">
                <PrimaryButton
                  title={label}
                  variant={destructive ? 'destructive' : 'accent'}
                  disabled
                />
              </View>
            </Pressy>
          ) : (
            <PrimaryButton
              title={label}
              variant={destructive ? 'destructive' : 'accent'}
              onPress={onRun}
            />
          )}
        </View>
      </Animated.View>
    </GlassBar>
  );
}

function clampPercent(p?: number): number {
  if (!Number.isFinite(p)) {
    return 0;
  }
  return Math.max(0, Math.min(100, p as number));
}
