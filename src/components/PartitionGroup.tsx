import React, {useEffect, useMemo, useState} from 'react';
import {TextInput, View} from 'react-native';
import Animated, {
  LinearTransition,
  useAnimatedStyle,
  useSharedValue,
  withSequence,
  withSpring,
  withTiming,
} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {GptEntry} from '../native/bridge';
import {Txt} from './Txt';
import {Pressy} from './Pressy';

type Mode = 'single' | 'multi';

type Props = {
  entries: GptEntry[];
  mode: Mode;
  selectedKey?: string;
  checkedKeys: Set<string>;
  onSelectSingle: (name: string, lun: string) => void;
  onToggle: (key: string) => void;
  onExpandDetail?: (key: string) => void;
};

const rowKey = (lun: string, name: string) => `${lun}:${name}`;

/** sector 字段是字符串，先安全解析再估算容量；先除后算避免大盘溢出。 */
function formatCapacity(numSectors?: string, sectorSize?: string): string | null {
  const sectors = Number(numSectors);
  const size = Number(sectorSize);
  if (!Number.isFinite(sectors) || !Number.isFinite(size) || sectors <= 0 || size <= 0) {
    return null;
  }
  const mib = (sectors * size) / (1024 * 1024);
  if (mib >= 1024) {
    return `${(mib / 1024).toFixed(mib / 1024 >= 100 ? 0 : 2)} GiB`;
  }
  if (mib >= 1) {
    return `${mib.toFixed(mib >= 100 ? 0 : 1)} MiB`;
  }
  return `${(mib * 1024).toFixed(0)} KiB`;
}

/** 把 entries 按 lun 分组并按数值排序，保留组内原顺序。 */
function groupByLun(entries: GptEntry[]): {lun: string; rows: GptEntry[]}[] {
  const map = new Map<string, GptEntry[]>();
  for (const e of entries) {
    const arr = map.get(e.lun);
    if (arr) {
      arr.push(e);
    } else {
      map.set(e.lun, [e]);
    }
  }
  return Array.from(map.entries())
    .map(([lun, rows]) => ({lun, rows}))
    .sort((a, b) => Number(a.lun) - Number(b.lun));
}

/** 分组分区列表：按 LUN 折叠分区，single 单选 / multi 多选，支持原地展开扇区详情。 */
export function PartitionGroup({
  entries,
  mode,
  selectedKey,
  checkedKeys,
  onSelectSingle,
  onToggle,
  onExpandDetail,
}: Props) {
  const {c, radius, hairline, space} = useTheme();

  // 按分区名大小写无关过滤；分区表常有数十项，搜索快速定位目标分区
  const [query, setQuery] = useState('');
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? entries.filter(e => e.name.toLowerCase().includes(q)) : entries;
  }, [entries, query]);
  const groups = useMemo(() => groupByLun(filtered), [filtered]);
  const searching = query.trim().length > 0;

  // 默认展开第一个 LUN，其余收起
  const [openLuns, setOpenLuns] = useState<Set<string>>(
    () => new Set(groups.length ? [groups[0].lun] : []),
  );
  const [openDetails, setOpenDetails] = useState<Set<string>>(() => new Set());

  const toggleLun = (lun: string) => {
    setOpenLuns(prev => {
      const next = new Set(prev);
      next.has(lun) ? next.delete(lun) : next.add(lun);
      return next;
    });
  };

  const toggleDetail = (key: string) => {
    setOpenDetails(prev => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
    onExpandDetail?.(key);
  };

  return (
    <View>
      <TextInput
        value={query}
        onChangeText={setQuery}
        placeholder="搜索分区名"
        placeholderTextColor={c.labelTertiary}
        autoCapitalize="none"
        autoCorrect={false}
        style={{
          backgroundColor: c.surface2,
          borderRadius: radius.input,
          paddingHorizontal: space.md,
          paddingVertical: space.sm,
          marginBottom: space.lg,
          color: c.labelPrimary,
        }}
      />
      {searching && groups.length === 0 ? (
        <Txt
          variant="footnote"
          color={c.labelTertiary}
          style={{marginLeft: space.lg, marginBottom: space.lg}}>
          无匹配分区
        </Txt>
      ) : null}
      {groups.map(group => {
        // 搜索时展开所有命中组，避免过滤结果落在折叠的 LUN 内被隐藏
        const open = searching || openLuns.has(group.lun);
        return (
          <Animated.View
            key={group.lun}
            layout={LinearTransition.springify().damping(20).stiffness(220)}
            style={{
              marginBottom: space.lg,
              backgroundColor: c.surface1,
              borderRadius: radius.card,
              borderWidth: c.scheme === 'dark' ? hairline : 0,
              borderColor: c.separator,
              overflow: 'hidden',
            }}>
            <LunHeader
              lun={group.lun}
              count={group.rows.length}
              open={open}
              onPress={() => toggleLun(group.lun)}
            />
            {open ? (
              <Animated.View layout={LinearTransition.springify()}>
                {group.rows.map((e, i) => {
                  const key = rowKey(e.lun, e.name);
                  return (
                    <View key={key}>
                      {i > 0 ? (
                        <View
                          style={{
                            height: hairline,
                            backgroundColor: c.separator,
                            marginLeft: space.lg,
                          }}
                        />
                      ) : null}
                      <PartitionRow
                        entry={e}
                        rowId={key}
                        mode={mode}
                        selected={mode === 'single' && selectedKey === key}
                        checked={checkedKeys.has(key)}
                        detailOpen={openDetails.has(key)}
                        onSelectSingle={onSelectSingle}
                        onToggle={onToggle}
                        onToggleDetail={toggleDetail}
                      />
                    </View>
                  );
                })}
              </Animated.View>
            ) : null}
          </Animated.View>
        );
      })}
    </View>
  );
}

/** LUN section 头：标题 + 分区数 + 右侧箭头随展开 rotate 0→90°。 */
function LunHeader({
  lun,
  count,
  open,
  onPress,
}: {
  lun: string;
  count: number;
  open: boolean;
  onPress: () => void;
}) {
  const {c, space} = useTheme();
  const rot = useSharedValue(open ? 1 : 0);
  useEffect(() => {
    rot.value = withSpring(open ? 1 : 0, {damping: 18, stiffness: 220});
  }, [open, rot]);
  const arrowStyle = useAnimatedStyle(() => ({
    transform: [{rotate: `${rot.value * 90}deg`}],
  }));
  return (
    <Pressy onPress={onPress} scaleTo={0.99}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: space.lg,
          paddingVertical: space.md,
        }}>
        <View style={{flex: 1}}>
          <Txt variant="title2">LUN {lun}</Txt>
          <Txt variant="footnote" color={c.labelSecondary} style={{marginTop: 2}}>
            {count} 个分区
          </Txt>
        </View>
        <Animated.View style={arrowStyle}>
          <Txt variant="body" color={c.labelTertiary} style={{fontSize: 20}}>
            ›
          </Txt>
        </Animated.View>
      </View>
    </Pressy>
  );
}

/** LUN 徽章胶囊。 */
function LunBadge({lun}: {lun: string}) {
  const {c, radius, space} = useTheme();
  return (
    <View
      style={{
        backgroundColor: c.fillTertiary,
        borderRadius: radius.chip,
        paddingHorizontal: space.sm,
        paddingVertical: 2,
        marginLeft: space.sm,
      }}>
      <Txt variant="caption2" color={c.labelSecondary}>
        LUN {lun}
      </Txt>
    </View>
  );
}

/** 圆形勾选标记：勾选时 scale 0→1.15→1 弹性。 */
function CheckMark({checked}: {checked: boolean}) {
  const {c} = useTheme();
  const s = useSharedValue(checked ? 1 : 0);
  useEffect(() => {
    s.value = checked
      ? withSequence(
          withSpring(1.15, {damping: 12, stiffness: 380}),
          withSpring(1, {damping: 14, stiffness: 300}),
        )
      : withTiming(0, {duration: 120});
  }, [checked, s]);
  const style = useAnimatedStyle(() => ({
    transform: [{scale: s.value}],
    opacity: s.value,
  }));
  return (
    <View
      style={{
        width: 24,
        height: 24,
        borderRadius: 12,
        borderWidth: checked ? 0 : 1.5,
        borderColor: c.labelTertiary,
        alignItems: 'center',
        justifyContent: 'center',
      }}>
      <Animated.View
        style={[
          {
            width: 24,
            height: 24,
            borderRadius: 12,
            backgroundColor: c.accent,
            alignItems: 'center',
            justifyContent: 'center',
            position: 'absolute',
          },
          style,
        ]}>
        <Txt variant="footnote" color="#FFFFFF" style={{fontWeight: '700'}}>
          ✓
        </Txt>
      </Animated.View>
    </View>
  );
}

/** 单个分区行：选中态高亮 + 左缘竖条，支持原地展开扇区详情。 */
function PartitionRow({
  entry,
  rowId,
  mode,
  selected,
  checked,
  detailOpen,
  onSelectSingle,
  onToggle,
  onToggleDetail,
}: {
  entry: GptEntry;
  rowId: string;
  mode: Mode;
  selected: boolean;
  checked: boolean;
  detailOpen: boolean;
  onSelectSingle: (name: string, lun: string) => void;
  onToggle: (key: string) => void;
  onToggleDetail: (key: string) => void;
}) {
  const {c, space} = useTheme();
  const cap = formatCapacity(entry.numSectors, entry.sectorSize);

  // 选中行轻微回弹
  const scale = useSharedValue(1);
  const rowStyle = useAnimatedStyle(() => ({transform: [{scale: scale.value}]}));
  const bounce = () => {
    scale.value = withSequence(
      withSpring(0.985, {damping: 18, stiffness: 300}),
      withSpring(1, {damping: 14, stiffness: 380}),
    );
  };

  const handlePress = () => {
    bounce();
    if (mode === 'single') {
      onSelectSingle(entry.name, entry.lun);
    } else {
      onToggle(rowId);
    }
  };

  // accent 8% 底色
  const selectedBg = mode === 'single' && selected;

  return (
    <Animated.View style={rowStyle}>
      <Pressy onPress={handlePress} onLongPress={() => onToggleDetail(rowId)} scaleTo={1}>
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            minHeight: 52,
            paddingHorizontal: space.lg,
            paddingVertical: space.md,
            backgroundColor: selectedBg ? withAlpha(c.accent, 0.08) : 'transparent',
          }}>
          {selectedBg ? (
            <View
              style={{
                position: 'absolute',
                left: 0,
                top: 6,
                bottom: 6,
                width: 3,
                borderRadius: 2,
                backgroundColor: c.accent,
              }}
            />
          ) : null}
          <View style={{flex: 1, flexDirection: 'row', alignItems: 'center'}}>
            <Txt variant="headline" numberOfLines={1} style={{flexShrink: 1}}>
              {entry.name}
            </Txt>
            <LunBadge lun={entry.lun} />
          </View>
          {cap ? (
            <Txt
              variant="footnote"
              color={c.labelSecondary}
              style={{marginLeft: space.sm, marginRight: space.sm}}>
              {cap}
            </Txt>
          ) : null}
          {mode === 'single' ? (
            <Txt variant="body" color={c.labelTertiary} style={{fontSize: 20}}>
              ›
            </Txt>
          ) : (
            <CheckMark checked={checked} />
          )}
        </View>
      </Pressy>
      {detailOpen ? (
        <Animated.View
          layout={LinearTransition.springify()}
          style={{
            paddingHorizontal: space.lg,
            paddingBottom: space.md,
            backgroundColor: selectedBg ? withAlpha(c.accent, 0.04) : 'transparent',
          }}>
          <DetailRow label="起始扇区" value={entry.startSector ?? '—'} />
          <DetailRow label="扇区数" value={entry.numSectors ?? '—'} />
          <DetailRow label="扇区大小" value={entry.sectorSize ?? '—'} />
        </Animated.View>
      ) : null}
    </Animated.View>
  );
}

function DetailRow({label, value}: {label: string; value: string}) {
  const {c, space} = useTheme();
  return (
    <View style={{flexDirection: 'row', justifyContent: 'space-between', marginTop: space.xs}}>
      <Txt variant="footnote" color={c.labelTertiary}>
        {label}
      </Txt>
      <Txt variant="mono" color={c.monoText}>
        {value}
      </Txt>
    </View>
  );
}

/** 给十六进制色叠加透明度；非 #rrggbb 时原样返回，避免破坏 rgba/命名色。 */
function withAlpha(color: string, alpha: number): string {
  if (/^#[0-9a-fA-F]{6}$/.test(color)) {
    const r = parseInt(color.slice(1, 3), 16);
    const g = parseInt(color.slice(3, 5), 16);
    const b = parseInt(color.slice(5, 7), 16);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }
  return color;
}
