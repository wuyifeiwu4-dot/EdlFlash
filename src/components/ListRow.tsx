import React from 'react';
import {View} from 'react-native';
import {useTheme} from '../theme/theme';
import {Txt} from './Txt';
import {Pressy} from './Pressy';

/** 通用 grouped row：左主文案 / 右值 + chevron / 任意 accessory 插槽。 */
export function ListRow({
  label,
  value,
  onPress,
  accessory,
  left,
  destructive,
  showChevron,
  subtitle,
}: {
  label: string;
  value?: string;
  onPress?: () => void;
  accessory?: React.ReactNode;
  left?: React.ReactNode;
  destructive?: boolean;
  showChevron?: boolean;
  subtitle?: string;
}) {
  const {c, space} = useTheme();
  const body = (
    <View
      style={{
        minHeight: 52,
        paddingHorizontal: space.lg,
        paddingVertical: space.md,
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: c.surface1,
      }}>
      {left ? <View style={{marginRight: space.md}}>{left}</View> : null}
      <View style={{flex: 1}}>
        <Txt variant="headline" color={destructive ? c.destructive : c.labelPrimary}>
          {label}
        </Txt>
        {subtitle ? (
          <Txt variant="footnote" color={c.labelSecondary} style={{marginTop: 2}}>
            {subtitle}
          </Txt>
        ) : null}
      </View>
      {value ? (
        <Txt
          variant="body"
          color={c.labelSecondary}
          numberOfLines={1}
          style={{maxWidth: 180, marginLeft: space.sm, textAlign: 'right'}}>
          {value}
        </Txt>
      ) : null}
      {accessory ? <View style={{marginLeft: space.sm}}>{accessory}</View> : null}
      {showChevron ? (
        <Txt variant="body" color={c.labelTertiary} style={{marginLeft: space.xs, fontSize: 20}}>
          ›
        </Txt>
      ) : null}
    </View>
  );
  if (!onPress) {
    return body;
  }
  return (
    <Pressy onPress={onPress} scaleTo={0.985}>
      {body}
    </Pressy>
  );
}
