import React, {useEffect, useState} from 'react';
import {ScrollView, View} from 'react-native';
import Animated, {FadeInDown} from 'react-native-reanimated';
import {useTheme} from '../theme/theme';
import {useApp} from '../state/app';
import {edl} from '../native/bridge';
import {
  GroupedSection,
  ListRow,
  Txt,
  SegmentedControl,
  StatusChip,
  PrimaryButton,
  PickerSheet,
} from '../components';
import type {PickerItem} from '../components/PickerSheet';

type LoaderTab = 'builtin' | 'file' | 'vip';
type PickerKind = 'vendor' | 'chip' | 'devprg' | null;

export function LoaderScreen() {
  const {space} = useTheme();
  const {state, actions} = useApp();
  const [tab, setTab] = useState<LoaderTab>('builtin');
  const [picker, setPicker] = useState<PickerKind>(null);

  // 真实内置资产枚举（扫 assets/loader_builtin）
  const [vendors, setVendors] = useState<PickerItem[]>([]);
  const [chips, setChips] = useState<PickerItem[]>([]);
  const [loaders, setLoaders] = useState<PickerItem[]>([]);

  const builtin = state.loader.builtin;

  useEffect(() => {
    edl.listBuiltinVendors().then(list =>
      setVendors(list.map(v => ({id: v.dir, label: v.label, category: 'config'}))),
    );
  }, []);

  useEffect(() => {
    const vendorDir = builtin?.vendorDir;
    if (!vendorDir) {
      setChips([]);
      return;
    }
    let alive = true;
    edl.listBuiltinChips(vendorDir).then(list => {
      if (!alive) return;
      setChips(list.map(ch => ({id: ch, label: ch, category: 'config'})));
      // 对齐原生：未选芯片时自动选首项
      if (!builtin?.chip && list.length > 0) {
        setBuiltin(vendorDir, list[0], '');
      }
    });
    return () => {
      alive = false;
    };
  }, [builtin?.vendorDir]);

  useEffect(() => {
    const vendorDir = builtin?.vendorDir;
    const chip = builtin?.chip;
    if (!vendorDir || !chip) {
      setLoaders([]);
      return;
    }
    let alive = true;
    edl.listBuiltinLoaders(vendorDir, chip).then(list => {
      if (!alive) return;
      setLoaders(list.map(l => ({id: l.devprg, label: l.devprg, category: 'config'})));
      // 对齐原生 choosePreferredLoaderCandidate：未选引导文件时自动预选首选项
      if (!builtin?.devprgAsset && list.length > 0) {
        const devprg =
          list.find((l: {devprg: string; preferred?: boolean}) => l.preferred)?.devprg ??
          list[0].devprg;
        setBuiltin(vendorDir, chip, devprg);
      }
    });
    return () => {
      alive = false;
    };
  }, [builtin?.vendorDir, builtin?.chip]);

  // 合并写回；切换上层选项时清空下层（厂商变→清芯片/devprg，芯片变→清 devprg）
  const setBuiltin = (vendorDir: string, chip: string, devprgAsset: string) => {
    actions.setLoaderBuiltin({vendorDir, chip, devprgAsset});
  };

  const labelOf = (items: PickerItem[], id?: string) =>
    items.find(it => it.id === id)?.label ?? '未选择';

  let sectionIndex = 0;
  const fade = () => FadeInDown.delay(sectionIndex++ * 50).springify();

  return (
    <ScrollView
      style={{backgroundColor: 'transparent'}}
      contentContainerStyle={{paddingTop: space.md, paddingHorizontal: space.lg, paddingBottom: 240}}
      keyboardShouldPersistTaps="handled">
      <Txt variant="largeTitle" style={{marginBottom: space.lg}}>
        引导文件与授权
      </Txt>

      <Animated.View entering={fade()}>
        <View style={{marginBottom: space.xl}}>
          <SegmentedControl
            segments={[
              {key: 'builtin', label: '内置授权'},
              {key: 'file', label: '自定义'},
              {key: 'vip', label: 'VIP'},
            ]}
            value={tab}
            onChange={key => setTab(key as LoaderTab)}
          />
        </View>
      </Animated.View>

      {tab === 'builtin' ? (
        <Animated.View entering={fade()}>
          <GroupedSection header="内置引导" footer="内置授权包随应用分发，无需手动准备文件">
            <ListRow
              label="品牌"
              value={labelOf(vendors, builtin?.vendorDir)}
              showChevron
              onPress={() => setPicker('vendor')}
            />
            <ListRow
              label="处理器"
              value={builtin?.chip || '未选择'}
              showChevron
              onPress={() => builtin?.vendorDir && setPicker('chip')}
            />
            <ListRow
              label="引导文件"
              value={builtin?.devprgAsset || '未选择'}
              showChevron
              onPress={() => builtin?.chip && setPicker('devprg')}
            />
          </GroupedSection>
        </Animated.View>
      ) : null}

      {tab === 'file' ? (
        <Animated.View entering={fade()}>
          <GroupedSection header="自定义文件" footer="从本机选择 devprg 及可选的 digest / sign 文件">
            <ListRow
              label="引导程序 (devprg)"
              value={state.loader.pathName ?? '未选择'}
              showChevron
              onPress={async () => {
                const f = await edl.pickFile();
                if (f) actions.setLoaderPath(f.path, f.name);
              }}
            />
            <ListRow
              label="摘要 (digest)"
              value={state.vip.digestPath ? '已选择' : '未选择'}
              showChevron
              onPress={async () => {
                const f = await edl.pickFile();
                if (f) actions.setVipFiles(f.path, state.vip.signPath);
              }}
            />
            <ListRow
              label="签名 (sign)"
              value={state.vip.signPath ? '已选择' : '未选择'}
              showChevron
              onPress={async () => {
                const f = await edl.pickFile();
                if (f) actions.setVipFiles(state.vip.digestPath, f.path);
              }}
            />
          </GroupedSection>
        </Animated.View>
      ) : null}

      {tab === 'vip' ? (
        <Animated.View entering={fade()}>
          <GroupedSection header="VIP 授权" footer="授权需 digest 与 sign 文件，完成后方可执行受保护操作">
            <ListRow
              label="摘要 (digest)"
              accessory={
                <StatusChip
                  text={state.vip.digestPath ? '已就绪' : '缺失'}
                  tone={state.vip.digestPath ? 'success' : 'warning'}
                />
              }
            />
            <ListRow
              label="签名 (sign)"
              accessory={
                <StatusChip
                  text={state.vip.signPath ? '已就绪' : '缺失'}
                  tone={state.vip.signPath ? 'success' : 'warning'}
                />
              }
            />
            <ListRow
              label="授权状态"
              accessory={
                <StatusChip
                  text={state.vip.authorized ? '已授权' : '未授权'}
                  tone={state.vip.authorized ? 'success' : 'neutral'}
                />
              }
            />
          </GroupedSection>
          <PrimaryButton
            title={state.vip.authorized ? '授权完成 ✓' : '执行授权'}
            variant="accent"
            loading={state.busy === 'vip'}
            onPress={actions.vipAuth}
          />
        </Animated.View>
      ) : null}

      <PickerSheet
        visible={picker === 'vendor'}
        onClose={() => setPicker(null)}
        title="选择品牌"
        items={vendors}
        searchable
        selectedId={builtin?.vendorDir}
        onSelect={id => {
          setBuiltin(id, '', ''); // 换厂商→清芯片/devprg
          setPicker(null);
        }}
      />
      <PickerSheet
        visible={picker === 'chip'}
        onClose={() => setPicker(null)}
        title="选择处理器"
        items={chips}
        searchable
        selectedId={builtin?.chip}
        onSelect={id => {
          setBuiltin(builtin?.vendorDir ?? '', id, ''); // 换芯片→清 devprg
          setPicker(null);
        }}
      />
      <PickerSheet
        visible={picker === 'devprg'}
        onClose={() => setPicker(null)}
        title="选择引导文件"
        items={loaders}
        searchable
        selectedId={builtin?.devprgAsset}
        onSelect={id => {
          setBuiltin(builtin?.vendorDir ?? '', builtin?.chip ?? '', id);
          setPicker(null);
        }}
      />
    </ScrollView>
  );
}
