import React, {useState} from 'react';
import {LayoutChangeEvent, View} from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {Txt} from './Txt';
import {Pressy} from './Pressy';

type Segment = {key: string; label: string};

/** iOS 分段控件：滑块 shared-layout 滑动，选中段文字加深。 */
export function SegmentedControl({
  segments,
  value,
  onChange,
}: {
  segments: Segment[];
  value: string;
  onChange: (k: string) => void;
}) {
  const {c, radius, space, spring} = useTheme();
  // 每段实测宽度，初始全 0；测完才显示滑块以免闪烁
  const [widths, setWidths] = useState<number[]>(() =>
    segments.map(() => 0),
  );
  const measured = widths.length === segments.length && widths.every(w => w > 0);
  const index = Math.max(
    0,
    segments.findIndex(s => s.key === value),
  );

  const x = useSharedValue(0);
  const w = useSharedValue(0);

  // 选中项或宽度变化时，滑块 spring 到目标位置
  React.useEffect(() => {
    if (!measured) {
      return;
    }
    const left = widths.slice(0, index).reduce((sum, n) => sum + n, 0);
    x.value = withSpring(left, spring.standard);
    w.value = withSpring(widths[index], spring.standard);
  }, [measured, index, widths, spring.standard, x, w]);

  const thumbStyle = useAnimatedStyle(() => ({
    transform: [{translateX: x.value}],
    width: w.value,
  }));

  const onSegmentLayout = (i: number) => (e: LayoutChangeEvent) => {
    const next = e.nativeEvent.layout.width;
    setWidths(prev => {
      if (prev[i] === next) {
        return prev;
      }
      const copy = [...prev];
      copy[i] = next;
      return copy;
    });
  };

  return (
    <View
      style={{
        flexDirection: 'row',
        backgroundColor: c.fillTertiary,
        borderRadius: radius.chip,
        padding: 2,
      }}>
      {measured ? (
        <Animated.View
          pointerEvents="none"
          style={[
            {
              position: 'absolute',
              top: 2,
              bottom: 2,
              borderRadius: radius.chip,
              backgroundColor: c.surface1,
              shadowColor: '#000',
              shadowOpacity: 0.12,
              shadowRadius: 4,
              shadowOffset: {width: 0, height: 1},
              elevation: 2,
            },
            thumbStyle,
          ]}
        />
      ) : null}
      {segments.map((seg, i) => {
        const active = seg.key === value;
        return (
          <Pressy
            key={seg.key}
            scaleTo={0.96}
            onPress={() => onChange(seg.key)}
            onLayout={onSegmentLayout(i)}
            style={{
              flex: 1,
              minHeight: 32,
              alignItems: 'center',
              justifyContent: 'center',
              paddingHorizontal: space.sm,
            }}>
            <Txt
              variant="subhead"
              numberOfLines={1}
              color={active ? c.labelPrimary : c.labelSecondary}
              style={{fontWeight: active ? '600' : '400'}}>
              {seg.label}
            </Txt>
          </Pressy>
        );
      })}
    </View>
  );
}
