import React from 'react';
import {Pressable} from 'react-native';
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useDerivedValue,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';

const TRACK_W = 51;
const TRACK_H = 31;
const KNOB = 27;
const PAD = 2;

/** iOS 开关：旋钮 spring 平移，轨道色随状态交叉过渡。 */
export function IOSToggle({
  value,
  onValueChange,
  tone = 'accent',
}: {
  value: boolean;
  onValueChange: (v: boolean) => void;
  tone?: 'accent' | 'success' | 'destructive';
}) {
  const {c, spring} = useTheme();
  const onColor =
    tone === 'success'
      ? c.success
      : tone === 'destructive'
      ? c.destructive
      : c.accent;

  // progress 0→1 同时驱动旋钮位移与轨道色
  const progress = useSharedValue(value ? 1 : 0);
  React.useEffect(() => {
    progress.value = withSpring(value ? 1 : 0, spring.snappy);
  }, [value, spring.snappy, progress]);

  const trackColor = useDerivedValue(() =>
    interpolateColor(progress.value, [0, 1], [c.fillTertiary, onColor]),
  );
  const trackStyle = useAnimatedStyle(() => ({
    backgroundColor: trackColor.value,
  }));
  const knobStyle = useAnimatedStyle(() => ({
    transform: [{translateX: progress.value * (TRACK_W - KNOB - PAD * 2)}],
  }));

  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityState={{checked: value}}
      hitSlop={8}
      onPress={() => onValueChange(!value)}>
      <Animated.View
        style={[
          {
            width: TRACK_W,
            height: TRACK_H,
            borderRadius: TRACK_H / 2,
            padding: PAD,
            justifyContent: 'center',
          },
          trackStyle,
        ]}>
        <Animated.View
          style={[
            {
              width: KNOB,
              height: KNOB,
              borderRadius: KNOB / 2,
              backgroundColor: '#FFFFFF',
              shadowColor: '#000',
              shadowOpacity: 0.2,
              shadowRadius: 2,
              shadowOffset: {width: 0, height: 1},
              elevation: 2,
            },
            knobStyle,
          ]}
        />
      </Animated.View>
    </Pressable>
  );
}
