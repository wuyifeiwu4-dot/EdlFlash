import React from 'react';
import {View} from 'react-native';
import {useTheme} from '../theme/theme';
import {Txt} from './Txt';

/** iOS 设置风 grouped inset 卡：可选大写 header / footnote footer，子行间自动插左缩进 hairline。 */
export function GroupedSection({
  header,
  headerRight,
  footer,
  children,
  style,
}: {
  header?: string;
  headerRight?: React.ReactNode;
  footer?: string;
  children: React.ReactNode;
  style?: any;
}) {
  const {c, radius, hairline, space} = useTheme();
  const items = React.Children.toArray(children).filter(Boolean);
  return (
    <View style={[{marginBottom: space.xl}, style]}>
      {header || headerRight ? (
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            marginLeft: space.lg,
            marginRight: space.lg,
            marginBottom: space.sm,
          }}>
          {header ? (
            <Txt
              variant="subhead"
              color={c.labelTertiary}
              style={{
                flex: 1,
                textTransform: 'uppercase',
                letterSpacing: 0.6,
              }}>
              {header}
            </Txt>
          ) : (
            <View style={{flex: 1}} />
          )}
          {headerRight}
        </View>
      ) : null}
      <View
        style={{
          backgroundColor: c.surface1,
          borderRadius: radius.card,
          borderWidth: c.scheme === 'dark' ? hairline : 0,
          borderColor: c.separator,
          overflow: 'hidden',
          ...(c.scheme === 'light'
            ? {
                shadowColor: '#000',
                shadowOpacity: 0.06,
                shadowRadius: 16,
                shadowOffset: {width: 0, height: 4},
                elevation: 2,
              }
            : null),
        }}>
        {items.map((child, i) => (
          <View key={i}>
            {i > 0 ? (
              <View
                style={{
                  height: hairline,
                  backgroundColor: c.separator,
                  marginLeft: space.lg,
                }}
              />
            ) : null}
            {child}
          </View>
        ))}
      </View>
      {footer ? (
        <Txt
          variant="footnote"
          color={c.labelTertiary}
          style={{marginLeft: space.lg, marginTop: space.sm, marginRight: space.lg}}>
          {footer}
        </Txt>
      ) : null}
    </View>
  );
}
