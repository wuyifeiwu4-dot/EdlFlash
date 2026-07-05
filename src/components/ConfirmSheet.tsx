import React, {useState} from 'react';
import {LayoutChangeEvent, Modal, Pressable, StyleSheet, View} from 'react-native';
import {Gesture, GestureDetector} from 'react-native-gesture-handler';
import Animated, {
  interpolate,
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  withTiming,
} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {Txt} from './Txt';
import {Pressy} from './Pressy';

const SLIDER_H = 56;
const KNOB = 48;
const KNOB_PAD = 4;

/** iOS action sheet 风确认：普通按钮或"滑动以确认"滑块。 */
export function ConfirmSheet({
  visible,
  onClose,
  title,
  message,
  confirmLabel,
  destructive,
  slideToConfirm,
  onConfirm,
  neutralLabel,
  onNeutral,
}: {
  visible: boolean;
  onClose: () => void;
  title: string;
  message?: string;
  confirmLabel: string;
  destructive?: boolean;
  slideToConfirm?: boolean;
  onConfirm: () => void;
  neutralLabel?: string;
  onNeutral?: () => void;
}) {
  const {c, radius, space, spring, hairline} = useTheme();
  const tint = destructive ? c.destructive : c.accent;

  // 进出场状态机：mounted 控制 Modal 挂载，退场动画跑完才卸载
  const [mounted, setMounted] = useState(visible);
  const translateY = useSharedValue(400);
  const backdrop = useSharedValue(0);

  const unmount = React.useCallback(() => setMounted(false), []);

  React.useEffect(() => {
    if (visible) {
      setMounted(true);
      translateY.value = withSpring(0, spring.sheet);
      backdrop.value = withTiming(1, {duration: 220});
    } else if (mounted) {
      backdrop.value = withTiming(0, {duration: 180});
      translateY.value = withTiming(400, {duration: 220}, finished => {
        if (finished) {
          runOnJS(unmount)();
        }
      });
    }
  }, [visible, mounted, spring.sheet, translateY, backdrop, unmount]);

  const sheetStyle = useAnimatedStyle(() => ({
    transform: [{translateY: translateY.value}],
  }));
  const backdropStyle = useAnimatedStyle(() => ({opacity: backdrop.value}));

  if (!mounted) {
    return null;
  }

  return (
    <Modal visible transparent animationType="none" onRequestClose={onClose}>
      <View style={{flex: 1, justifyContent: 'flex-end'}}>
        <Animated.View
          style={[
            StyleSheet.absoluteFill,
            {backgroundColor: 'rgba(0,0,0,0.45)'},
            backdropStyle,
          ]}>
          <Pressable style={{flex: 1}} onPress={onClose} />
        </Animated.View>
        <Animated.View
          style={[{padding: space.md, paddingBottom: space.xl}, sheetStyle]}>
          <View
            style={{
              backgroundColor: c.surface1,
              borderRadius: radius.card,
              borderWidth: c.scheme === 'dark' ? hairline : 0,
              borderColor: c.separator,
              padding: space.lg,
              marginBottom: space.sm,
            }}>
            <Txt
              variant="headline"
              style={{textAlign: 'center', marginBottom: message ? space.xs : space.md}}>
              {title}
            </Txt>
            {message ? (
              <Txt
                variant="footnote"
                color={c.labelSecondary}
                style={{textAlign: 'center', marginBottom: space.md}}>
                {message}
              </Txt>
            ) : null}

            {slideToConfirm ? (
              <SlideToConfirm
                label={confirmLabel}
                tint={tint}
                onConfirm={onConfirm}
              />
            ) : (
              <Pressy
                scaleTo={0.97}
                onPress={onConfirm}
                style={{
                  minHeight: 52,
                  borderRadius: radius.button,
                  alignItems: 'center',
                  justifyContent: 'center',
                  backgroundColor: tint,
                }}>
                <Txt variant="headline" color="#FFFFFF" style={{fontWeight: '700'}}>
                  {confirmLabel}
                </Txt>
              </Pressy>
            )}
          </View>

          {neutralLabel && onNeutral ? (
            <Pressy
              scaleTo={0.98}
              onPress={onNeutral}
              style={{
                minHeight: 52,
                borderRadius: radius.card,
                alignItems: 'center',
                justifyContent: 'center',
                backgroundColor: c.surface1,
                marginBottom: space.sm,
                borderWidth: c.scheme === 'dark' ? hairline : 0,
                borderColor: c.separator,
              }}>
              <Txt variant="headline" color={c.accent} style={{fontWeight: '600'}}>
                {neutralLabel}
              </Txt>
            </Pressy>
          ) : null}

          <Pressy
            scaleTo={0.98}
            onPress={onClose}
            style={{
              minHeight: 52,
              borderRadius: radius.card,
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: c.surface1,
              borderWidth: c.scheme === 'dark' ? hairline : 0,
              borderColor: c.separator,
            }}>
            <Txt variant="headline" color={c.accent} style={{fontWeight: '600'}}>
              取消
            </Txt>
          </Pressy>
        </Animated.View>
      </View>
    </Modal>
  );
}

/** 滑动确认：拖到右端触发，未到松手弹回。 */
function SlideToConfirm({
  label,
  tint,
  onConfirm,
}: {
  label: string;
  tint: string;
  onConfirm: () => void;
}) {
  const {radius, spring} = useTheme();
  const [trackW, setTrackW] = useState(0);
  const maxX = Math.max(0, trackW - KNOB - KNOB_PAD * 2);
  const x = useSharedValue(0);

  const onTrackLayout = (e: LayoutChangeEvent) =>
    setTrackW(e.nativeEvent.layout.width);

  const pan = Gesture.Pan()
    .onChange(e => {
      const next = x.value + e.changeX;
      x.value = Math.min(Math.max(next, 0), maxX);
    })
    .onEnd(() => {
      // 拖过 90% 视为确认，否则弹回
      if (maxX > 0 && x.value >= maxX * 0.9) {
        x.value = withSpring(maxX, spring.snappy);
        runOnJS(onConfirm)();
      } else {
        x.value = withSpring(0, spring.snappy);
      }
    });

  const knobStyle = useAnimatedStyle(() => ({
    transform: [{translateX: x.value}],
  }));
  // 进度提示文字随拖动淡出
  const labelStyle = useAnimatedStyle(() => ({
    opacity: maxX > 0 ? interpolate(x.value, [0, maxX], [1, 0]) : 1,
  }));

  return (
    <View
      onLayout={onTrackLayout}
      style={{
        height: SLIDER_H,
        borderRadius: radius.button,
        backgroundColor: tint,
        justifyContent: 'center',
        overflow: 'hidden',
      }}>
      <Animated.View
        style={[
          StyleSheet.absoluteFill,
          {alignItems: 'center', justifyContent: 'center'},
          labelStyle,
        ]}>
        <Txt variant="headline" color="#FFFFFF" style={{fontWeight: '700'}}>
          {label} ›››
        </Txt>
      </Animated.View>
      <GestureDetector gesture={pan}>
        <Animated.View
          style={[
            {
              position: 'absolute',
              left: KNOB_PAD,
              width: KNOB,
              height: KNOB,
              borderRadius: KNOB / 2,
              backgroundColor: '#FFFFFF',
              alignItems: 'center',
              justifyContent: 'center',
            },
            knobStyle,
          ]}>
          <Txt variant="title2" color={tint} style={{fontWeight: '700'}}>
            ›
          </Txt>
        </Animated.View>
      </GestureDetector>
    </View>
  );
}
