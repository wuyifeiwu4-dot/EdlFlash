/**
 * 自建卡密/公告桥接（替代微验）。真机走原生 License 模块（与统一后台 backend/ 通信）；
 * 模块缺失时（Metro 纯前端调试）回落 mock。
 */
import {NativeModules} from 'react-native';

export type VerifyResult =
  | {ok: true; card: string; mode: 'time'; expiry: number}
  | {ok: true; card: string; mode: 'count'; remaining: string}
  | {ok: false; message: string};

export type Announcement = {ok: boolean; title: string; message: string};

export interface LicenseBridge {
  /** 设备机器码（卡密绑定用，可展示给用户） */
  getMarkcode(): Promise<string>;
  /** 上次登录成功的卡密（无则空串） */
  getSavedCard(): Promise<string>;
  clearSavedCard(): Promise<boolean>;
  /** 公告：拉取后台，无公告时 message 为空串 */
  getAnnouncement(): Promise<Announcement>;
  /** 卡密验证 */
  verify(card: string): Promise<VerifyResult>;
}

class MockLicense implements LicenseBridge {
  async getMarkcode() {
    return 'mock-0000-1111-2222';
  }
  async getSavedCard() {
    return '';
  }
  async clearSavedCard() {
    return true;
  }
  async getAnnouncement() {
    return {ok: true, title: '演示公告', message: '欢迎使用 EDL Flash（演示）。'};
  }
  async verify(card: string): Promise<VerifyResult> {
    if (!card.trim()) {
      return {ok: false, message: '卡密不能为空'};
    }
    return {ok: true, card, mode: 'time', expiry: Math.floor(Date.now() / 1000) + 86400};
  }
}

function makeNative(): LicenseBridge {
  const native = (NativeModules as any).License;
  if (!native) {
    if (!__DEV__) {
      throw new Error('原生模块 License 未注册，构建异常');
    }
    return new MockLicense();
  }
  return {
    getMarkcode: () => native.getMarkcode(),
    getSavedCard: () => native.getSavedCard(),
    clearSavedCard: () => native.clearSavedCard(),
    getAnnouncement: () => native.getAnnouncement(),
    verify: (card: string) => native.verify(card),
  };
}

export const license: LicenseBridge = makeNative();
