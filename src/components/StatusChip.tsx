import React, {useEffect} from 'react';
import {View} from 'react-native';
import Animated, {useSharedValue, useAnimatedStyle, withRepeat, withTiming, cancelAnimation} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {Txt} from './Txt';

export type ChipTone = 'success' | 'warning' | 'destructive' | 'neutral' | 'accent';

/** 状态胶囊：圆点 + 文案；success 态圆点可呼吸。 */
export function StatusChip({
  text,
  tone = 'neutral',
  pulse,
}: {
  text: string;
  tone?: ChipTone;
  pulse?: boolean;
}) {
  const {c, radius, space} = useTheme();
  const toneColor =
    tone === 'success'
      ? c.success
      : tone === 'warning'
      ? c.warning
      : tone === 'destructive'
      ? c.destructive
      : tone === 'accent'
      ? c.accent
      : c.labelTertiary;

  const o = useSharedValue(1);
  useEffect(() => {
    if (pulse) {
      o.value = withRepeat(withTiming(0.4, {duration: 1100}), -1, true);
    } else {
      cancelAnimation(o);
      o.value = 1;
    }
    return () => cancelAnimation(o);
  }, [pulse, o]);
  const dotStyle = useAnimatedStyle(() => ({opacity: o.value}));

  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: c.fillTertiary,
        borderRadius: radius.chip,
        paddingHorizontal: space.md,
        paddingVertical: 6,
      }}>
      <Animated.View
        style={[
          {width: 8, height: 8, borderRadius: 4, backgroundColor: toneColor, marginRight: space.sm},
          dotStyle,
        ]}
      />
      <Txt variant="caption2" color={c.labelPrimary} numberOfLines={1}>
        {text}
      </Txt>
    </View>
  );
}
