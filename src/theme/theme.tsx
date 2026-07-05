import React, {createContext, useContext, useMemo, useState} from 'react';
import {useColorScheme} from 'react-native';
import {dark, light, Palette, type, space, radius, spring, hairline} from './tokens';

export type Appearance = 'auto' | 'light' | 'dark';

type ThemeValue = {
  c: Palette;
  type: typeof type;
  space: typeof space;
  radius: typeof radius;
  spring: typeof spring;
  hairline: number;
  appearance: Appearance;
  setAppearance: (a: Appearance) => void;
};

const ThemeContext = createContext<ThemeValue | null>(null);

export function ThemeProvider({children}: {children: React.ReactNode}) {
  const system = useColorScheme();
  const [appearance, setAppearance] = useState<Appearance>('auto');
  const scheme = appearance === 'auto' ? system ?? 'dark' : appearance;
  const value = useMemo<ThemeValue>(
    () => ({
      c: scheme === 'light' ? light : dark,
      type,
      space,
      radius,
      spring,
      hairline,
      appearance,
      setAppearance,
    }),
    [scheme, appearance],
  );
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeValue {
  const v = useContext(ThemeContext);
  if (!v) {
    throw new Error('useTheme 必须在 ThemeProvider 内使用');
  }
  return v;
}
