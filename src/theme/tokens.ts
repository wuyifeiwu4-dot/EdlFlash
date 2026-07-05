/**
 * iOS 风设计 token（深色优先 + 浅色两套）。
 * 数值来自 UI 设计规格：8pt 网格、iOS 字阶、毛玻璃、系统蓝强调色。
 */

export type Palette = {
  scheme: 'dark' | 'light';
  bgBase: string;
  bgGradTop: string;
  bgGradBottom: string;
  surface1: string;
  surface2: string;
  surfaceGlass: string;
  separator: string;
  fillTertiary: string;
  labelPrimary: string;
  labelSecondary: string;
  labelTertiary: string;
  accent: string;
  accentPressed: string;
  accentGradTop: string;
  accentGradBottom: string;
  success: string;
  warning: string;
  destructive: string;
  destructiveGradTop: string;
  destructiveGradBottom: string;
  monoBg: string;
  monoText: string;
};

export const dark: Palette = {
  scheme: 'dark',
  bgBase: '#0B0E13',
  bgGradTop: '#0E141B',
  bgGradBottom: '#0B1216',
  surface1: '#161C24',
  surface2: '#1B222C',
  surfaceGlass: 'rgba(22,28,36,0.72)',
  separator: 'rgba(255,255,255,0.08)',
  fillTertiary: 'rgba(118,128,150,0.20)',
  labelPrimary: '#E9ECF2',
  labelSecondary: '#9AA3B2',
  labelTertiary: '#5C6678',
  accent: '#0A84FF',
  accentPressed: '#0970D6',
  accentGradTop: '#2A93FF',
  accentGradBottom: '#0A84FF',
  success: '#30D158',
  warning: '#FFD60A',
  destructive: '#FF453A',
  destructiveGradTop: '#FF6B5E',
  destructiveGradBottom: '#FF453A',
  monoBg: '#0A0D11',
  monoText: '#C8D2E0',
};

export const light: Palette = {
  scheme: 'light',
  bgBase: '#F2F4F8',
  bgGradTop: '#EDF3FF',
  bgGradBottom: '#F6F3FF',
  surface1: '#FFFFFF',
  surface2: '#F4F6FB',
  surfaceGlass: 'rgba(255,255,255,0.72)',
  separator: 'rgba(60,60,67,0.12)',
  fillTertiary: 'rgba(118,128,150,0.12)',
  labelPrimary: '#11141A',
  labelSecondary: '#5A6173',
  labelTertiary: '#A0A6B4',
  accent: '#007AFF',
  accentPressed: '#0062CC',
  accentGradTop: '#2A93FF',
  accentGradBottom: '#007AFF',
  success: '#34C759',
  warning: '#FF9F0A',
  destructive: '#FF3B30',
  destructiveGradTop: '#FF6B5E',
  destructiveGradBottom: '#FF3B30',
  monoBg: '#0E141B',
  monoText: '#C8D2E0',
};

// iOS 字阶（size / lineHeight / weight）
export const type = {
  largeTitle: {fontSize: 34, lineHeight: 41, fontWeight: '700' as const},
  title2: {fontSize: 22, lineHeight: 28, fontWeight: '700' as const},
  headline: {fontSize: 17, lineHeight: 22, fontWeight: '600' as const},
  body: {fontSize: 17, lineHeight: 22, fontWeight: '400' as const},
  callout: {fontSize: 16, lineHeight: 21, fontWeight: '400' as const},
  subhead: {fontSize: 15, lineHeight: 20, fontWeight: '400' as const},
  footnote: {fontSize: 13, lineHeight: 18, fontWeight: '400' as const},
  caption2: {fontSize: 11, lineHeight: 13, fontWeight: '600' as const},
  mono: {fontSize: 13, lineHeight: 19, fontWeight: '400' as const},
};

export const space = {xs: 4, sm: 8, md: 12, lg: 16, xl: 20, xxl: 24, xxxl: 32};

export const radius = {
  card: 22,
  button: 14,
  chip: 999,
  sheet: 28,
  input: 12,
  row: 14,
};

export const hairline = 0.5;

// 统一弹簧物理（reanimated withSpring 配置）
export const spring = {
  standard: {damping: 18, stiffness: 220, mass: 1},
  snappy: {damping: 26, stiffness: 300, mass: 1},
  bouncy: {damping: 14, stiffness: 380, mass: 1},
  sheet: {damping: 28, stiffness: 280, mass: 1},
};

export const mono =
  'ui-monospace, Menlo, Monaco, "SF Mono", "Roboto Mono", monospace';
