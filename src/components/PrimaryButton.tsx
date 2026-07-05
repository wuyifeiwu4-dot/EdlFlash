import React from 'react';
import {View, ActivityIndicator} from 'react-native';
import LinearGradient from 'react-native-linear-gradient';
import {useTheme} from '../theme/theme';
import {Txt} from './Txt';
import {Pressy} from './Pressy';

/** iOS 大圆角渐变主按钮：accent(蓝) / destructive(红) 两变体，带 loading 与禁用态。 */
export function PrimaryButton({
  title,
  onPress,
  variant = 'accent',
  loading,
  disabled,
}: {
  title: string;
  onPress?: () => void;
  variant?: 'accent' | 'destructive';
  loading?: boolean;
  disabled?: boolean;
}) {
  const {c, radius, type} = useTheme();
  const colors =
    variant === 'destructive'
      ? [c.destructiveGradTop, c.destructiveGradBottom]
      : [c.accentGradTop, c.accentGradBottom];
  const off = disabled || loading;
  return (
    <Pressy onPress={off ? undefined : onPress} disabled={off} scaleTo={0.97}>
      <LinearGradient
        colors={off ? [c.fillTertiary, c.fillTertiary] : colors}
        start={{x: 0, y: 0}}
        end={{x: 1, y: 1}}
        style={{
          minHeight: 52,
          borderRadius: radius.button,
          alignItems: 'center',
          justifyContent: 'center',
          flexDirection: 'row',
          opacity: off ? 0.7 : 1,
        }}>
        {loading ? (
          <ActivityIndicator color="#fff" style={{marginRight: 8}} />
        ) : null}
        <Txt
          style={{
            fontSize: type.headline.fontSize,
            fontWeight: '700',
            color: off ? c.labelTertiary : '#FFFFFF',
          }}>
          {title}
        </Txt>
      </LinearGradient>
    </Pressy>
  );
}
