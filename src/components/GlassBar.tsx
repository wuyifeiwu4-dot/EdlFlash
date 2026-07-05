import React from 'react';
import {View, ViewStyle, StyleProp} from 'react-native';
import {useTheme} from '../theme/theme';

/**
 * 毛玻璃容器（状态条/TabBar/Dock 复用）。Android 真实 blur 可靠性差，
 * 这里用半透明 surfaceGlass + hairline 模拟磨砂质感，零原生风险。
 */
export function GlassBar({
  children,
  style,
  edge = 'top',
}: {
  children?: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  edge?: 'top' | 'bottom' | 'none';
}) {
  const {c, hairline} = useTheme();
  return (
    <View
      style={[
        {
          backgroundColor: c.surfaceGlass,
          borderTopWidth: edge === 'bottom' ? hairline : 0,
          borderBottomWidth: edge === 'top' ? hairline : 0,
          borderColor: c.separator,
        },
        style,
      ]}>
      {children}
    </View>
  );
}
