import React, {useState} from 'react';
import {TextInput, View, KeyboardTypeOptions} from 'react-native';
import {useTheme} from '../theme/theme';
import {Txt} from './Txt';

/** iOS 内联输入：聚焦时 accent 描边，支持等宽（mono）多行。 */
export function InlineField({
  label,
  value,
  onChangeText,
  placeholder,
  keyboardType,
  mono,
  multiline,
}: {
  label?: string;
  value: string;
  onChangeText: (t: string) => void;
  placeholder?: string;
  keyboardType?: KeyboardTypeOptions;
  mono?: boolean;
  multiline?: boolean;
}) {
  const {c, radius, space} = useTheme();
  const [focused, setFocused] = useState(false);
  return (
    <View style={{paddingHorizontal: space.lg, paddingVertical: space.sm}}>
      {label ? (
        <Txt variant="footnote" color={c.labelSecondary} style={{marginBottom: 4}}>
          {label}
        </Txt>
      ) : null}
      <TextInput
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={c.labelTertiary}
        keyboardType={keyboardType}
        autoCapitalize="none"
        autoCorrect={false}
        multiline={multiline}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        style={{
          backgroundColor: c.surface2,
          borderRadius: radius.input,
          borderWidth: 1.5,
          borderColor: focused ? c.accent : 'transparent',
          color: c.labelPrimary,
          fontSize: 16,
          paddingHorizontal: 12,
          minHeight: multiline ? 80 : 44,
          textAlignVertical: multiline ? 'top' : 'center',
          fontFamily: mono ? 'monospace' : undefined,
        }}
      />
    </View>
  );
}
