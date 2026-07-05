import React from 'react';
import {
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  withTiming,
} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';

/**
 * 底部弹层的进出场：mounted 控制 Modal 挂载，退场动画跑完才卸载。
 * offscreenY 为面板收起时的下移量（固定高度传常量，动态高度传实测值）。
 */
export function useSheetTransition(visible: boolean, offscreenY: number) {
  const {spring} = useTheme();
  const [mounted, setMounted] = React.useState(visible);
  const translateY = useSharedValue(offscreenY);
  const backdrop = useSharedValue(0);

  const unmount = React.useCallback(() => setMounted(false), []);

  React.useEffect(() => {
    if (visible) {
      setMounted(true);
      translateY.value = withSpring(0, spring.sheet);
      backdrop.value = withTiming(1, {duration: 220});
    } else if (mounted) {
      backdrop.value = withTiming(0, {duration: 180});
      translateY.value = withTiming(offscreenY, {duration: 220}, finished => {
        if (finished) {
          runOnJS(unmount)();
        }
      });
    }
  }, [visible, mounted, offscreenY, spring.sheet, translateY, backdrop, unmount]);

  const sheetStyle = useAnimatedStyle(() => ({
    transform: [{translateY: translateY.value}],
  }));
  const backdropStyle = useAnimatedStyle(() => ({opacity: backdrop.value}));

  return {mounted, sheetStyle, backdropStyle};
}
