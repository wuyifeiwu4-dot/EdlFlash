import React, {useEffect, useRef} from 'react';
import {ActivityIndicator, ScrollView, useWindowDimensions, View} from 'react-native';
import {Gesture, GestureDetector} from 'react-native-gesture-handler';
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useDerivedValue,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {LogLine, LogLevel, Progress} from '../native/bridge';
import {Txt} from './Txt';
import {Pressy} from './Pressy';

type Props = {
  logs: LogLine[];
  progress: Progress | null;
  onClear: () => void;
  bottomInset?: number;
};

const PEEK = 72;

/** 上拉日志面板：peek / half / full 三档，拖拽吸附，执行时显示环形进度与终端日志。
 *  bottomInset：把整个面板抬到底部操作栏(RunDock+TabBar)之上，避免遮挡其点击。 */
export function LogSheet({logs, progress, onClear, bottomInset = 0}: Props) {
  const {c, radius, space, spring} = useTheme();
  const {height: screenH} = useWindowDimensions();

  // 可用高度扣除底栏，三档高度据此算（自小到大）
  const avail = Math.max(240, screenH - bottomInset);
  const half = Math.round(avail * 0.5);
  const full = Math.round(avail * 0.9);
  const detents = [PEEK, half, full];

  // translateY 以「最大高度容器」为基准下移，露出当前档的高度
  const translateY = useSharedValue(full - PEEK);
  const startY = useSharedValue(0);

  const sheetStyle = useAnimatedStyle(() => ({
    transform: [{translateY: translateY.value}],
  }));

  // 把手随拖拽变粗
  const dragging = useSharedValue(0);
  const grabberStyle = useAnimatedStyle(() => ({
    height: 5 + dragging.value * 3,
    opacity: 0.5 + dragging.value * 0.4,
  }));

  // 露出高度超过 peek 档即视为展开；只在布尔翻转时回 JS，避免逐帧 setState
  const [isExpanded, setIsExpanded] = React.useState(false);
  const expandThreshold = (PEEK + half) / 2;
  useDerivedValue(() => {
    const open = full - translateY.value > expandThreshold;
    runOnJS(setIsExpanded)(open);
  }, [full, expandThreshold]);

  const snapTo = (targetHeight: number) => {
    'worklet';
    translateY.value = withSpring(full - targetHeight, spring.sheet);
  };

  const pan = Gesture.Pan()
    .onStart(() => {
      startY.value = translateY.value;
      dragging.value = withSpring(1, spring.snappy);
    })
    .onUpdate(e => {
      const next = startY.value + e.translationY;
      // 约束在 [full-full, full-peek] = [0, full-peek]
      translateY.value = Math.max(0, Math.min(full - PEEK, next));
    })
    .onEnd(e => {
      dragging.value = withSpring(0, spring.snappy);
      // 当前露出高度 + 速度预测，吸附最近档
      const current = full - translateY.value;
      const projected = current - e.velocityY * 0.1;
      let nearest = detents[0];
      let best = Infinity;
      for (const d of detents) {
        const dist = Math.abs(d - projected);
        if (dist < best) {
          best = dist;
          nearest = d;
        }
      }
      snapTo(nearest);
    });

  const pct = clampPercent(progress?.percent);
  // 失败终态由 finishProgress(false) 上报 percent=0/label=失败：放宽门控以保留进度环可见
  const showProgress =
    !!progress &&
    !progress.indeterminate &&
    (progress.percent > 0 || progress.label === '失败');
  // 不定态（准备中/等待设备/握手等无百分比阶段）：以转圈占位，避免“无任何进度态”看似卡死
  const showSpinner = !!progress && (progress.indeterminate || progress.percent < 0);

  return (
    <Animated.View
      style={[
        {
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: bottomInset,
          height: full,
          backgroundColor: c.surfaceGlass,
          borderTopLeftRadius: radius.sheet,
          borderTopRightRadius: radius.sheet,
          borderWidth: 0.5,
          borderColor: c.separator,
          shadowColor: '#000',
          shadowOpacity: 0.18,
          shadowRadius: 24,
          shadowOffset: {width: 0, height: -8},
          elevation: 12,
        },
        sheetStyle,
      ]}>
      {/* 把手 + 拖拽区 */}
      <GestureDetector gesture={pan}>
        <View style={{paddingTop: space.sm, paddingBottom: space.sm, alignItems: 'center'}}>
          <Animated.View
            style={[
              {width: 40, borderRadius: 3, backgroundColor: c.labelTertiary},
              grabberStyle,
            ]}
          />
          {!isExpanded ? <PeekLine log={logs[logs.length - 1]} /> : null}
        </View>
      </GestureDetector>

      {isExpanded ? (
        <View style={{flex: 1, paddingHorizontal: space.lg, paddingBottom: space.xl}}>
          {/* 头部：标题 + 清空 */}
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              marginBottom: space.md,
            }}>
            <Txt variant="title2" style={{flex: 1}}>
              运行日志
            </Txt>
            <Pressy onPress={onClear} scaleTo={0.95}>
              <View
                style={{
                  paddingHorizontal: space.md,
                  paddingVertical: 6,
                  borderRadius: radius.chip,
                  backgroundColor: c.fillTertiary,
                }}>
                <Txt variant="footnote" color={c.labelSecondary}>
                  清空
                </Txt>
              </View>
            </Pressy>
          </View>

          {showProgress ? (
            <View style={{alignItems: 'center', marginBottom: space.lg}}>
              <RingProgress percent={pct} />
              {progress?.label ? (
                <Txt variant="footnote" color={c.labelSecondary} style={{marginTop: space.sm}}>
                  {progress.label}
                </Txt>
              ) : null}
            </View>
          ) : showSpinner ? (
            <View style={{alignItems: 'center', marginBottom: space.lg}}>
              <ActivityIndicator color={c.accent} />
              {progress?.label ? (
                <Txt variant="footnote" color={c.labelSecondary} style={{marginTop: space.sm}}>
                  {progress.label}
                </Txt>
              ) : null}
            </View>
          ) : null}

          <Terminal logs={logs} />
        </View>
      ) : null}
    </Animated.View>
  );
}

/** peek 档单行最新日志。 */
function PeekLine({log}: {log?: LogLine}) {
  const {c, space} = useTheme();
  return (
    <View style={{width: '100%', paddingHorizontal: space.lg, marginTop: space.xs}}>
      <Txt
        variant="footnote"
        color={log ? levelColor(log.level, c) : c.labelTertiary}
        numberOfLines={1}>
        {log ? log.text : '暂无日志'}
      </Txt>
    </View>
  );
}

/** 终端卡：等宽字体，按 level 染色，新行自动滚到底。 */
function Terminal({logs}: {logs: LogLine[]}) {
  const {c, radius, space} = useTheme();
  const ref = useRef<ScrollView>(null);
  useEffect(() => {
    ref.current?.scrollToEnd({animated: true});
  }, [logs.length]);
  return (
    <View
      style={{
        flex: 1,
        backgroundColor: c.monoBg,
        borderRadius: radius.input,
        overflow: 'hidden',
      }}>
      <ScrollView
        ref={ref}
        contentContainerStyle={{padding: space.md}}
        showsVerticalScrollIndicator={false}>
        {logs.map((line, i) => (
          <Txt
            key={i}
            variant="mono"
            color={levelColor(line.level, c)}
            style={{marginBottom: 2}}>
            {line.text}
          </Txt>
        ))}
      </ScrollView>
    </View>
  );
}

/**
 * 无 svg 的环形进度：满色底盘 + 两片半圆遮罩旋转露出进度弧。
 * 右遮罩盖右半圆，覆盖 0~50%（旋转 0~180° 逐步让出右半）；
 * 过 50% 后右半全亮，左遮罩再旋转让出左半（50~100%）。纯 View，零原生依赖。
 */
function RingProgress({percent}: {percent: number}) {
  const {c} = useTheme();
  const size = 88;
  const ring = 8;
  const half = size / 2;
  const arcColor = c.accent;
  const trackColor = c.fillTertiary;
  const maskColor = c.surfaceGlass;

  const p = useSharedValue(percent);
  useEffect(() => {
    p.value = withSpring(percent, {damping: 18, stiffness: 160});
  }, [percent, p]);

  // 右遮罩从 0° 转到 180°，露出右半进度（0~50%）
  const rightMask = useAnimatedStyle(() => ({
    transform: [{rotate: `${Math.min(p.value, 50) * 3.6}deg`}],
  }));
  // 左遮罩在过半后从 0° 转到 180°，露出左半进度（50~100%）
  const leftMask = useAnimatedStyle(() => ({
    transform: [{rotate: `${Math.max(0, p.value - 50) * 3.6}deg`}],
  }));
  // 50% 之前左半须被静态遮罩盖住
  const leftCoverStyle = useAnimatedStyle(() => ({
    opacity: p.value < 50 ? 1 : 0,
  }));

  // 一片贴边的半圆遮罩，绕圆心旋转（容器只占半边 + overflow 裁切）
  const HalfMask = ({
    side,
    style,
  }: {
    side: 'left' | 'right';
    style: ReturnType<typeof useAnimatedStyle>;
  }) => (
    <View
      style={{
        position: 'absolute',
        top: 0,
        left: side === 'right' ? half : 0,
        width: half,
        height: size,
        overflow: 'hidden',
      }}>
      <Animated.View
        style={[
          {
            width: half,
            height: size,
            backgroundColor: maskColor,
            borderTopRightRadius: side === 'left' ? half : 0,
            borderBottomRightRadius: side === 'left' ? half : 0,
            borderTopLeftRadius: side === 'right' ? half : 0,
            borderBottomLeftRadius: side === 'right' ? half : 0,
            // 旋转轴落在整圆圆心
            transform: [{translateX: side === 'right' ? -half : half}],
          },
          style,
        ]}
      />
    </View>
  );

  return (
    <View style={{width: size, height: size, alignItems: 'center', justifyContent: 'center'}}>
      {/* 满色进度盘 */}
      <View
        style={{
          position: 'absolute',
          width: size,
          height: size,
          borderRadius: half,
          backgroundColor: arcColor,
        }}
      />
      {/* 右遮罩：旋转露出右半进度 */}
      <HalfMask side="right" style={rightMask} />
      {/* 左半在未过半时整体盖住（静态遮罩 + 旋转遮罩叠加，保证边缘干净） */}
      <Animated.View
        style={[
          {
            position: 'absolute',
            top: 0,
            left: 0,
            width: half,
            height: size,
            backgroundColor: trackColor,
            borderTopLeftRadius: half,
            borderBottomLeftRadius: half,
          },
          leftCoverStyle,
        ]}
      />
      <HalfMask side="left" style={leftMask} />
      {/* 内圈挖空成环 */}
      <View
        style={{
          width: size - ring * 2,
          height: size - ring * 2,
          borderRadius: (size - ring * 2) / 2,
          backgroundColor: c.surfaceGlass,
          alignItems: 'center',
          justifyContent: 'center',
        }}>
        <PercentLabel percent={percent} />
      </View>
    </View>
  );
}

function PercentLabel({percent}: {percent: number}) {
  const {c} = useTheme();
  return (
    <Txt variant="mono" color={c.monoText} style={{fontWeight: '700'}}>
      {Math.round(percent)}%
    </Txt>
  );
}

function levelColor(level: LogLevel, c: ReturnType<typeof useTheme>['c']): string {
  switch (level) {
    case 'success':
      return c.success;
    case 'error':
      return c.destructive;
    case 'warn':
      return c.warning;
    default:
      return c.monoText;
  }
}

function clampPercent(p?: number): number {
  if (!Number.isFinite(p)) {
    return 0;
  }
  return Math.max(0, Math.min(100, p as number));
}
