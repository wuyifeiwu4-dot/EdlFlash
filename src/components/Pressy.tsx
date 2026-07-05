import React from 'react';
import {Pressable, PressableProps, ViewStyle, StyleProp} from 'react-native';
import Animated, {useSharedValue, useAnimatedStyle, withSpring} from 'react-native-reanimated';
import {spring} from '../theme/tokens';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

type Props = PressableProps & {
  scaleTo?: number;
  style?: StyleProp<ViewStyle>;
  children?: React.ReactNode;
};

/** 统一的 iOS 按压回弹：按下 spring 缩放 + 轻微变暗，松手弹回。 */
export function Pressy({scaleTo = 0.97, style, children, disabled, ...rest}: Props) {
  const scale = useSharedValue(1);
  const opacity = useSharedValue(1);
  const aStyle = useAnimatedStyle(() => ({
    transform: [{scale: scale.value}],
    opacity: opacity.value,
  }));
  return (
    <AnimatedPressable
      disabled={disabled}
      onPressIn={() => {
        scale.value = withSpring(scaleTo, spring.snappy);
        opacity.value = withSpring(0.92, spring.snappy);
      }}
      onPressOut={() => {
        scale.value = withSpring(1, spring.snappy);
        opacity.value = withSpring(1, spring.snappy);
      }}
      style={[aStyle, style]}
      {...rest}>
      {children as any}
    </AnimatedPressable>
  );
}
