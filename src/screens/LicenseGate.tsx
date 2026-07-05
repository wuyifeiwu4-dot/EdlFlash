/**
 * 卡密门禁：进入主界面前校验微验卡密；启动时拉取后台公告，有则弹窗。
 * 公告默认无（后台返回空则不弹）。
 */
import React, {useCallback, useEffect, useState} from 'react';
import {
  View,
  TextInput,
  Modal,
  ScrollView,
  ActivityIndicator,
  StatusBar,
  Pressable,
  Linking,
  Alert,
} from 'react-native';
import LinearGradient from 'react-native-linear-gradient';
import {SafeAreaProvider, useSafeAreaInsets} from 'react-native-safe-area-context';
import {useTheme} from '../theme/theme';
import {Txt, PrimaryButton} from '../components';
import {license, VerifyResult} from '../native/license';

type Phase = 'checking' | 'login' | 'authorized';

// 关于信息：QQ 交流群 / 作者 QQ，点击拉起 QQ；未安装或不支持 scheme 时回退网页/提示号码
const QQ_GROUP = '1077185480';
const AUTHOR_QQ = '2481849298';

function openWithFallback(primary: string, web: string, failTitle: string, failMsg: string) {
  Linking.openURL(primary).catch(() =>
    Linking.openURL(web).catch(() => Alert.alert(failTitle, failMsg)),
  );
}

function openQQGroup() {
  openWithFallback(
    `mqqapi://card/show_pslcard?src_type=internal&version=1&uin=${QQ_GROUP}&card_type=group&source=qrcode`,
    `https://qm.qq.com/cgi-bin/qm/qr?_wv=1027&group_code=${QQ_GROUP}`,
    '无法打开 QQ',
    `请在 QQ 中搜索加入交流群：${QQ_GROUP}`,
  );
}

function openAuthorQQ() {
  openWithFallback(
    `mqqwpa://im/chat?chat_type=wpa&uin=${AUTHOR_QQ}`,
    `https://wpa.qq.com/msgrd?v=3&uin=${AUTHOR_QQ}&site=qq&menu=yes`,
    '无法打开 QQ',
    `请在 QQ 中添加作者：${AUTHOR_QQ}`,
  );
}

function formatExpiry(epochSec: number): string {
  const d = new Date(epochSec * 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes(),
  )}:${p(d.getSeconds())}`;
}

function licenseSummary(r: Extract<VerifyResult, {ok: true}>): string {
  return r.mode === 'count'
    ? `登录成功，剩余可登录次数：${r.remaining}`
    : `登录成功，到期时间：${formatExpiry(r.expiry)}`;
}

function AnnouncementModal({
  title,
  message,
  onClose,
}: {
  title?: string;
  message: string;
  onClose: () => void;
}) {
  const {c, space, radius} = useTheme();
  return (
    <Modal visible animationType="fade" transparent onRequestClose={onClose}>
      <View
        style={{
          flex: 1,
          backgroundColor: 'rgba(0,0,0,0.55)',
          alignItems: 'center',
          justifyContent: 'center',
          padding: space.xl,
        }}>
        <View
          style={{
            width: '100%',
            maxWidth: 460,
            maxHeight: '74%',
            backgroundColor: c.surface1,
            borderRadius: radius.card,
            paddingTop: space.xl,
            overflow: 'hidden',
          }}>
          <Txt variant="title2" style={{textAlign: 'center', marginBottom: space.md}}>
            {title && title.trim() ? title : '公告'}
          </Txt>
          <ScrollView
            style={{paddingHorizontal: space.xl}}
            contentContainerStyle={{paddingBottom: space.lg}}>
            <Txt variant="callout" color={c.labelSecondary} style={{lineHeight: 22}}>
              {message}
            </Txt>
          </ScrollView>
          <Pressable
            onPress={onClose}
            style={{
              borderTopWidth: 0.5,
              borderTopColor: c.separator,
              paddingVertical: space.md,
              alignItems: 'center',
            }}>
            <Txt variant="headline" color={c.accent}>
              我知道了
            </Txt>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}

function LoginView({
  markcode,
  savedKey,
  loading,
  error,
  onVerify,
}: {
  markcode: string;
  savedKey: string;
  loading: boolean;
  error: string;
  onVerify: (kami: string) => void;
}) {
  const {c, space, radius, type} = useTheme();
  const insets = useSafeAreaInsets();
  const [kami, setKami] = useState('');

  return (
    <ScrollView
      contentContainerStyle={{
        flexGrow: 1,
        justifyContent: 'center',
        paddingHorizontal: space.xl,
        paddingTop: insets.top + space.xxl,
        paddingBottom: insets.bottom + space.xxl,
      }}
      keyboardShouldPersistTaps="handled">
      <View style={{alignItems: 'center', marginBottom: space.xxxl}}>
        <Txt variant="largeTitle" style={{marginBottom: space.sm}}>
          EDL Flash
        </Txt>
        <Txt variant="subhead" color={c.labelSecondary}>
          请输入卡密以激活使用
        </Txt>
      </View>

      <View
        style={{
          backgroundColor: c.surface1,
          borderRadius: radius.card,
          padding: space.lg,
          gap: space.md,
        }}>
        <TextInput
          value={kami}
          onChangeText={setKami}
          placeholder="在此输入卡密"
          placeholderTextColor={c.labelTertiary}
          autoCapitalize="none"
          autoCorrect={false}
          editable={!loading}
          style={{
            backgroundColor: c.surface2,
            borderRadius: radius.input,
            paddingHorizontal: space.md,
            minHeight: 48,
            color: c.labelPrimary,
            fontSize: type.body.fontSize,
          }}
        />

        {error ? (
          <Txt variant="footnote" color={c.destructive}>
            {error}
          </Txt>
        ) : null}

        <PrimaryButton
          title="登录"
          loading={loading}
          disabled={!kami.trim()}
          onPress={() => onVerify(kami.trim())}
        />

        {savedKey ? (
          <Pressable
            onPress={loading ? undefined : () => onVerify(savedKey)}
            style={{alignItems: 'center', paddingVertical: space.sm}}>
            <Txt variant="subhead" color={c.accent}>
              使用上次卡密登录
            </Txt>
          </Pressable>
        ) : null}
      </View>

      {markcode ? (
        <Txt
          variant="footnote"
          color={c.labelTertiary}
          style={{textAlign: 'center', marginTop: space.xl}}>
          机器码：{markcode}
        </Txt>
      ) : null}

      <View style={{marginTop: space.xxl, alignItems: 'center', gap: space.sm}}>
        <Txt variant="footnote" color={c.labelTertiary}>
          — 关于 · 点击跳转 QQ —
        </Txt>
        <Pressable onPress={openQQGroup} hitSlop={8}>
          <Txt variant="subhead" color={c.accent}>
            QQ 交流群：{QQ_GROUP}
          </Txt>
        </Pressable>
        <Pressable onPress={openAuthorQQ} hitSlop={8}>
          <Txt variant="subhead" color={c.accent}>
            作者 QQ：{AUTHOR_QQ}
          </Txt>
        </Pressable>
      </View>
    </ScrollView>
  );
}

function Gate({children}: {children: React.ReactNode}) {
  const {c} = useTheme();
  const [phase, setPhase] = useState<Phase>('checking');
  const [markcode, setMarkcode] = useState('');
  const [savedKey, setSavedKey] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [announcement, setAnnouncement] = useState<{title: string; content: string}>({
    title: '',
    content: '',
  });
  const [announceClosed, setAnnounceClosed] = useState(false);

  const runVerify = useCallback(async (kami: string) => {
    setLoading(true);
    setError('');
    try {
      const r = await license.verify(kami);
      if (r.ok) {
        setPhase('authorized');
      } else {
        setError(r.message || '卡密验证失败');
        setPhase('login');
      }
    } catch (e: any) {
      setError(e?.message ?? '验证请求失败');
      setPhase('login');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    (async () => {
      // 公告：拉取自建统一后台；无内容则不设置 → 不弹窗(默认无公告)。
      license
        .getAnnouncement()
        .then(a => {
          if (alive && a.ok && a.message.trim()) {
            setAnnouncement({title: a.title || '', content: a.message.trim()});
          }
        })
        .catch(() => {});

      try {
        const mc = await license.getMarkcode();
        if (alive) {
          setMarkcode(mc);
        }
      } catch {}

      let last = '';
      try {
        last = await license.getSavedCard();
      } catch {}
      if (!alive) {
        return;
      }
      setSavedKey(last);
      if (last) {
        runVerify(last);
      } else {
        setPhase('login');
      }
    })();
    return () => {
      alive = false;
    };
  }, [runVerify]);

  const announceVisible = !!announcement.content && !announceClosed;

  return (
    <View style={{flex: 1}}>
      {phase === 'authorized' ? (
        children
      ) : (
        <View style={{flex: 1}}>
          <LinearGradient
            colors={[c.bgGradTop, c.bgGradBottom]}
            style={{position: 'absolute', left: 0, right: 0, top: 0, bottom: 0}}
          />
          <StatusBar
            translucent
            backgroundColor="transparent"
            barStyle={c.scheme === 'dark' ? 'light-content' : 'dark-content'}
          />
          {phase === 'checking' ? (
            <View style={{flex: 1, alignItems: 'center', justifyContent: 'center'}}>
              <ActivityIndicator color={c.accent} size="large" />
              <Txt variant="subhead" color={c.labelSecondary} style={{marginTop: 16}}>
                正在校验授权…
              </Txt>
            </View>
          ) : (
            <LoginView
              markcode={markcode}
              savedKey={savedKey}
              loading={loading}
              error={error}
              onVerify={runVerify}
            />
          )}
        </View>
      )}

      {announceVisible ? (
        <AnnouncementModal
          title={announcement.title}
          message={announcement.content}
          onClose={() => setAnnounceClosed(true)}
        />
      ) : null}
    </View>
  );
}

export function LicenseGate({children}: {children: React.ReactNode}) {
  // SafeAreaProvider 已在 App 根部提供，这里直接使用插入值
  return <Gate>{children}</Gate>;
}
