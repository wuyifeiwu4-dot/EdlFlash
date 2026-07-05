import React, {useMemo, useState} from 'react';
import {
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  useWindowDimensions,
  View,
} from 'react-native';
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  withTiming,
} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {Txt} from './Txt';
import {Pressy} from './Pressy';

export type PickerCategory = 'read' | 'write' | 'erase' | 'config';

export type PickerItem = {
  id: string;
  label: string;
  desc?: string;
  category?: PickerCategory;
};

const GROUP_ORDER: PickerCategory[] = ['read', 'config', 'write', 'erase'];
const GROUP_TITLE: Record<PickerCategory, string> = {
  read: '读取',
  config: '配置',
  write: '写入',
  erase: '擦除',
};
// write/erase 为破坏性操作，文案标红并加警示前缀
const DANGER: Record<PickerCategory, boolean> = {
  read: false,
  config: false,
  write: true,
  erase: true,
};

/** 底部半屏选择器：spring 滑入、可搜索、按 category 分组、破坏性项标红。 */
export function PickerSheet({
  visible,
  onClose,
  title,
  items,
  selectedId,
  onSelect,
  searchable,
}: {
  visible: boolean;
  onClose: () => void;
  title: string;
  items: PickerItem[];
  selectedId?: string;
  onSelect: (id: string) => void;
  searchable?: boolean;
}) {
  const {c, radius, space, spring, hairline} = useTheme();
  const {height} = useWindowDimensions();
  const sheetH = Math.min(height * 0.7, 560);

  // 进出场状态机：mounted 控制 Modal 挂载，退场动画跑完才卸载
  const [mounted, setMounted] = useState(visible);
  const [query, setQuery] = useState('');
  const translateY = useSharedValue(sheetH);
  const backdrop = useSharedValue(0);

  const unmount = React.useCallback(() => setMounted(false), []);

  React.useEffect(() => {
    if (visible) {
      setMounted(true);
      setQuery('');
      translateY.value = withSpring(0, spring.sheet);
      backdrop.value = withTiming(1, {duration: 220});
    } else if (mounted) {
      backdrop.value = withTiming(0, {duration: 180});
      translateY.value = withTiming(sheetH, {duration: 220}, finished => {
        if (finished) {
          runOnJS(unmount)();
        }
      });
    }
  }, [visible, mounted, sheetH, spring.sheet, translateY, backdrop, unmount]);

  const sheetStyle = useAnimatedStyle(() => ({
    transform: [{translateY: translateY.value}],
  }));
  const backdropStyle = useAnimatedStyle(() => ({opacity: backdrop.value}));

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) {
      return items;
    }
    return items.filter(
      it =>
        it.label.toLowerCase().includes(q) ||
        (it.desc?.toLowerCase().includes(q) ?? false),
    );
  }, [items, query]);

  // 按 category 分组并保持固定展示顺序
  const groups = useMemo(() => {
    const map = new Map<PickerCategory, PickerItem[]>();
    for (const it of filtered) {
      const cat = it.category ?? 'config';
      const arr = map.get(cat);
      if (arr) {
        arr.push(it);
      } else {
        map.set(cat, [it]);
      }
    }
    return GROUP_ORDER.filter(cat => map.has(cat)).map(cat => ({
      category: cat,
      items: map.get(cat)!,
    }));
  }, [filtered]);

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
          style={[
            {
              height: sheetH,
              backgroundColor: c.bgBase,
              borderTopLeftRadius: radius.sheet,
              borderTopRightRadius: radius.sheet,
              paddingTop: space.sm,
              borderTopWidth: hairline,
              borderColor: c.separator,
            },
            sheetStyle,
          ]}>
          {/* 顶部抓手 */}
          <View
            style={{
              alignSelf: 'center',
              width: 36,
              height: 5,
              borderRadius: 3,
              backgroundColor: c.fillTertiary,
              marginBottom: space.sm,
            }}
          />
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              paddingHorizontal: space.lg,
              marginBottom: space.sm,
            }}>
            <Txt variant="title2">{title}</Txt>
            <Pressy scaleTo={0.94} onPress={onClose} hitSlop={8}>
              <Txt variant="headline" color={c.accent}>
                完成
              </Txt>
            </Pressy>
          </View>

          {searchable ? (
            <View
              style={{paddingHorizontal: space.lg, marginBottom: space.sm}}>
              <TextInput
                value={query}
                onChangeText={setQuery}
                placeholder="搜索"
                placeholderTextColor={c.labelTertiary}
                style={{
                  backgroundColor: c.surface2,
                  borderRadius: radius.input,
                  color: c.labelPrimary,
                  fontSize: 16,
                  paddingHorizontal: 12,
                  height: 44,
                }}
              />
            </View>
          ) : null}

          <ScrollView
            contentContainerStyle={{
              paddingHorizontal: space.lg,
              paddingBottom: space.xxl,
            }}
            keyboardShouldPersistTaps="handled">
            {groups.length === 0 ? (
              <Txt
                variant="footnote"
                color={c.labelTertiary}
                style={{textAlign: 'center', marginTop: space.xl}}>
                无匹配项
              </Txt>
            ) : null}
            {groups.map(group => {
              const danger = DANGER[group.category];
              return (
                <View key={group.category} style={{marginBottom: space.lg}}>
                  <Txt
                    variant="subhead"
                    color={danger ? c.destructive : c.labelTertiary}
                    style={{
                      marginLeft: space.sm,
                      marginBottom: space.sm,
                      textTransform: 'uppercase',
                      letterSpacing: 0.6,
                    }}>
                    {danger ? '⚠ ' : ''}
                    {GROUP_TITLE[group.category]}
                  </Txt>
                  <View
                    style={{
                      backgroundColor: c.surface1,
                      borderRadius: radius.card,
                      borderWidth: c.scheme === 'dark' ? hairline : 0,
                      borderColor: c.separator,
                      overflow: 'hidden',
                    }}>
                    {group.items.map((it, i) => {
                      const active = it.id === selectedId;
                      return (
                        <View key={it.id}>
                          {i > 0 ? (
                            <View
                              style={{
                                height: hairline,
                                backgroundColor: c.separator,
                                marginLeft: space.lg,
                              }}
                            />
                          ) : null}
                          <Pressy
                            scaleTo={0.985}
                            onPress={() => onSelect(it.id)}
                            style={{
                              minHeight: 52,
                              paddingHorizontal: space.lg,
                              paddingVertical: space.md,
                              flexDirection: 'row',
                              alignItems: 'center',
                              backgroundColor: c.surface1,
                            }}>
                            <View style={{flex: 1}}>
                              <Txt
                                variant="headline"
                                color={
                                  danger ? c.destructive : c.labelPrimary
                                }>
                                {danger ? '⚠ ' : ''}
                                {it.label}
                              </Txt>
                              {it.desc ? (
                                <Txt
                                  variant="footnote"
                                  color={c.labelSecondary}
                                  style={{marginTop: 2}}>
                                  {it.desc}
                                </Txt>
                              ) : null}
                            </View>
                            {active ? (
                              <Txt
                                variant="headline"
                                color={c.accent}
                                style={{marginLeft: space.sm}}>
                                ✓
                              </Txt>
                            ) : null}
                          </Pressy>
                        </View>
                      );
                    })}
                  </View>
                </View>
              );
            })}
          </ScrollView>
        </Animated.View>
      </View>
    </Modal>
  );
}
