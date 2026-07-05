import React from 'react';
import {Text, TextProps} from 'react-native';
import {useTheme} from '../theme/theme';
import {type as typeRamp} from '../theme/tokens';

type Variant = keyof typeof typeRamp;

export function Txt({
  variant = 'body',
  color,
  style,
  ...p
}: TextProps & {variant?: Variant; color?: string}) {
  const {c, type} = useTheme();
  const t = type[variant];
  return (
    <Text
      {...p}
      style={[
        {
          fontSize: t.fontSize,
          lineHeight: t.lineHeight,
          fontWeight: t.fontWeight,
          color: color ?? c.labelPrimary,
        },
        variant === 'mono' && {fontFamily: 'monospace'},
        style,
      ]}
    />
  );
}
