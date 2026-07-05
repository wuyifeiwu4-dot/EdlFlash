'use strict';
/*
 * EdlFlash 统一后台（零依赖纯 Node.js）：卡密验证 + 公告管理 + AstrBot 自助发卡。
 *
 * 鉴权：无账号密码。首次启动随机生成管理 token 存入 data/admin_token.txt 并打印；
 *       管理面板与 AstrBot 均用该 token（请求头 X-Admin-Token）。
 *
 * 接口：
 *   公开（App）：
 *     POST /api/card/verify     {card, markcode, t}   卡密验证(设备绑定/时卡/次卡/到期/HMAC签名)
 *     GET  /api/announcement                          当前公告(默认无→enabled:false)
 *   管理（X-Admin-Token）：
 *     GET  /api/admin/overview                         概览(公告+卡密统计)
 *     POST /api/admin/announcement {enabled,title,content}
 *     GET  /api/admin/cards?limit=                     卡密列表
 *     POST /api/admin/cards/generate {type,days,totalCount,unbindable,qty,note}
 *     POST /api/admin/cards/update   {code,action}     action: disable|enable|delete|unbind
 *   AstrBot（X-Admin-Token）：
 *     POST /api/bot/daycard {qq}                       群自助天卡(1天/不可解绑/每QQ每天1张)
 *
 * 配置(环境变量)：PORT(默认 8787)、ADMIN_TOKEN(覆盖随机 token)、LICENSE_SECRET(响应签名密钥，
 *   须与 App 内常量一致；默认下方固定值，可改但两端要同步)。
 */
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.env.PORT || '8787', 10);
// 响应签名密钥：App(LicenseModule.kt LICENSE_SECRET) 必须与此一致，用于卡密响应防伪。
const LICENSE_SECRET = process.env.LICENSE_SECRET || 'edlflash-license-7f3a9c21d8e64b05';
// 自定义流密码：/api/card/verify 请求/响应应用层加密，密钥派生自 LICENSE_SECRET。
// 与 native android/.../edlcipher.c 逐字节一致。
const edl = require('./edlcipher');
const LIC_SECRET_BUF = Buffer.from(LICENSE_SECRET, 'utf8');

const DATA_DIR = path.join(__dirname, 'data');
const PUBLIC_DIR = path.join(__dirname, 'public');
const F_ANNOUNCE = path.join(DATA_DIR, 'announcement.json');
const F_CARDS = path.join(DATA_DIR, 'cards.json');
const F_RATE = path.join(DATA_DIR, 'ratelimit.json');
const F_TOKEN = path.join(DATA_DIR, 'admin_token.txt');

const CARD_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // 去除易混淆 0/O/1/I
const DAY_MS = 86400 * 1000;

// ---------- 通用存储 ----------
function ensureDir() { if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, {recursive: true}); }
function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { return fallback; }
}
function writeJson(file, obj) { ensureDir(); fs.writeFileSync(file, JSON.stringify(obj, null, 2), 'utf8'); }

function loadAnnounce() {
  const o = readJson(F_ANNOUNCE, {});
  return {
    enabled: !!o.enabled,
    title: typeof o.title === 'string' ? o.title : '',
    content: typeof o.content === 'string' ? o.content : '',
    updatedAt: Number(o.updatedAt) || 0,
  };
}
function loadCards() { const o = readJson(F_CARDS, {}); return (o && typeof o === 'object') ? o : {}; }
function loadRate() { const o = readJson(F_RATE, {}); return (o && typeof o === 'object') ? o : {}; }

// ---------- token ----------
let ADMIN_TOKEN = process.env.ADMIN_TOKEN || '';
function initToken() {
  ensureDir();
  if (ADMIN_TOKEN) return;
  if (fs.existsSync(F_TOKEN)) {
    ADMIN_TOKEN = fs.readFileSync(F_TOKEN, 'utf8').trim();
  }
  if (!ADMIN_TOKEN) {
    ADMIN_TOKEN = crypto.randomBytes(24).toString('hex');
    fs.writeFileSync(F_TOKEN, ADMIN_TOKEN, 'utf8');
  }
}
function checkToken(req) {
  const t = (req.headers['x-admin-token'] || '').trim();
  if (!t || t.length !== ADMIN_TOKEN.length) return false;
  try { return crypto.timingSafeEqual(Buffer.from(t), Buffer.from(ADMIN_TOKEN)); } catch (e) { return false; }
}

// ---------- HMAC 卡密响应签名 ----------
function signCard(code, markcode, serverTime, num) {
  return crypto.createHmac('sha256', LICENSE_SECRET)
    .update(`${code}|${markcode}|${serverTime}|${num}`).digest('hex');
}

// ---------- 卡密码生成 ----------
function genCode() {
  const bytes = crypto.randomBytes(16);
  let s = '';
  for (let i = 0; i < 16; i++) {
    s += CARD_CHARS[bytes[i] % CARD_CHARS.length];
    if (i % 4 === 3 && i !== 15) s += '-';
  }
  return s; // 形如 XXXX-XXXX-XXXX-XXXX
}
function dateStr(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

// ---------- HTTP 工具 ----------
function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-Admin-Token',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve) => {
    let data = ''; let big = false;
    req.on('data', (c) => { data += c; if (data.length > 1_000_000) { big = true; req.destroy(); } });
    req.on('end', () => { if (big) return resolve(null); try { resolve(data ? JSON.parse(data) : {}); } catch (e) { resolve(null); } });
    req.on('error', () => resolve(null));
  });
}
// 原始文本体（用于加密协议的 hex 密文包）。
function readRawBody(req) {
  return new Promise((resolve) => {
    let data = ''; let big = false;
    req.on('data', (c) => { data += c; if (data.length > 1_000_000) { big = true; req.destroy(); } });
    req.on('end', () => resolve(big ? '' : data));
    req.on('error', () => resolve(''));
  });
}
// 把对象 JSON 后用自定义流密码加密发送（text/plain hex）。
function sendPacked(res, obj) {
  const body = edl.pack(LIC_SECRET_BUF, JSON.stringify(obj));
  res.writeHead(200, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}
const STATIC_TYPES = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8'};
function serveStatic(res, urlPath) {
  const rel = urlPath === '/' ? '/index.html' : urlPath;
  const full = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!full.startsWith(PUBLIC_DIR)) { res.writeHead(403); return res.end('forbidden'); }
  fs.readFile(full, (err, buf) => {
    if (err) { res.writeHead(404); return res.end('not found'); }
    res.writeHead(200, {'Content-Type': STATIC_TYPES[path.extname(full)] || 'application/octet-stream'});
    res.end(buf);
  });
}

// 卡密验证失败响应（带签名，防 MITM 把失败篡改成功）；加密发送。
function cardFailPacked(res, markcode, msg) {
  const st = Math.floor(Date.now() / 1000);
  return sendPacked(res, {ok: false, code: 0, message: msg, serverTime: st, sign: signCard('', markcode, st, 0)});
}

// 卡密对外摘要（隐藏无关内部字段）
function cardSummary(c) {
  return {
    code: c.code, type: c.type, enabled: c.enabled !== false, unbindable: !!c.unbindable,
    days: c.days || 0, totalCount: c.totalCount || 0, usedCount: c.usedCount || 0,
    markcode: c.markcode || '', firstUsedAt: c.firstUsedAt || 0, expiry: c.expiry || 0,
    createdAt: c.createdAt || 0, source: c.source || '', note: c.note || '',
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;
  if (req.method === 'OPTIONS') return sendJson(res, 204, {});

  // ===== 公开：卡密验证（请求/响应自定义流密码加密，抓包只见密文）=====
  if (p === '/api/card/verify' && req.method === 'POST') {
    const raw = await readRawBody(req);
    let body;
    try { body = JSON.parse(edl.unpack(LIC_SECRET_BUF, raw)); }
    catch (e) { return cardFailPacked(res, '', '安全通道异常'); }
    if (!body || typeof body.card !== 'string' || typeof body.markcode !== 'string') {
      return cardFailPacked(res, body && body.markcode ? body.markcode : '', '参数错误');
    }
    const card = body.card.trim().toUpperCase();
    const markcode = body.markcode.trim();
    if (!card || !markcode) return cardFailPacked(res, markcode, '卡密或设备码为空');
    const cards = loadCards();
    const c = cards[card];
    const now = Date.now();
    const st = Math.floor(now / 1000);
    if (!c) return cardFailPacked(res, markcode, '卡密不存在');
    if (c.enabled === false) return cardFailPacked(res, markcode, '卡密已被禁用');
    if (c.markcode && c.markcode !== markcode) {
      return cardFailPacked(res, markcode, c.unbindable ? '卡密已绑定其它设备(不可解绑)' : '卡密已绑定其它设备');
    }
    // 首次激活：绑定设备、计算到期
    if (!c.markcode) {
      c.markcode = markcode;
      c.firstUsedAt = now;
      if (c.type === 'time') c.expiry = now + (c.days || 1) * DAY_MS;
    }
    if (c.type === 'time') {
      if (now > (c.expiry || 0)) { cards[card] = c; writeJson(F_CARDS, cards); return cardFailPacked(res, markcode, '卡密已过期'); }
      c.lastUsedAt = now; cards[card] = c; writeJson(F_CARDS, cards);
      const expSec = Math.floor(c.expiry / 1000);
      return sendPacked(res, {ok: true, code: 1, mode: 'time', expiry: expSec, serverTime: st, sign: signCard(card, markcode, st, expSec)});
    }
    // 次卡：每次验证消耗一次
    if ((c.usedCount || 0) >= (c.totalCount || 0)) { cards[card] = c; writeJson(F_CARDS, cards); return cardFailPacked(res, markcode, '登录次数已用尽'); }
    c.usedCount = (c.usedCount || 0) + 1; c.lastUsedAt = now; cards[card] = c; writeJson(F_CARDS, cards);
    const remaining = (c.totalCount || 0) - c.usedCount;
    return sendPacked(res, {ok: true, code: 1, mode: 'count', remaining, serverTime: st, sign: signCard(card, markcode, st, remaining)});
  }

  // ===== 公开：公告 =====
  if (p === '/api/announcement' && req.method === 'GET') {
    const a = loadAnnounce();
    if (!a.enabled || !a.content.trim()) return sendJson(res, 200, {enabled: false, title: '', content: '', updatedAt: a.updatedAt});
    return sendJson(res, 200, {enabled: true, title: a.title, content: a.content, updatedAt: a.updatedAt});
  }

  // ===== 管理 token 校验门 =====
  if (p.startsWith('/api/admin/') || p === '/api/bot/daycard') {
    if (!checkToken(req)) return sendJson(res, 401, {ok: false, error: 'token 无效'});
  }

  // ===== 管理：概览 =====
  if (p === '/api/admin/overview' && req.method === 'GET') {
    const cards = loadCards();
    const list = Object.values(cards);
    const now = Date.now();
    let active = 0, used = 0, expired = 0;
    for (const c of list) {
      if (c.enabled === false) continue;
      if (c.type === 'time' && c.markcode && c.expiry && now > c.expiry) { expired++; continue; }
      if (c.type === 'count' && (c.usedCount || 0) >= (c.totalCount || 0)) { used++; continue; }
      active++;
    }
    return sendJson(res, 200, {ok: true, announcement: loadAnnounce(), stats: {total: list.length, active, used, expired}});
  }

  // ===== 管理：保存公告 =====
  if (p === '/api/admin/announcement' && req.method === 'POST') {
    const body = await readBody(req);
    if (!body) return sendJson(res, 400, {ok: false, error: '请求体无效'});
    const a = {enabled: !!body.enabled, title: String(body.title || '').slice(0, 200), content: String(body.content || '').slice(0, 8000), updatedAt: Date.now()};
    writeJson(F_ANNOUNCE, a);
    return sendJson(res, 200, {ok: true, announcement: a});
  }

  // ===== 管理：卡密列表 =====
  if (p === '/api/admin/cards' && req.method === 'GET') {
    const limit = Math.min(parseInt(url.searchParams.get('limit') || '500', 10) || 500, 2000);
    const list = Object.values(loadCards()).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)).slice(0, limit);
    return sendJson(res, 200, {ok: true, cards: list.map(cardSummary)});
  }

  // ===== 管理：生成卡密 =====
  if (p === '/api/admin/cards/generate' && req.method === 'POST') {
    const body = await readBody(req);
    if (!body) return sendJson(res, 400, {ok: false, error: '请求体无效'});
    const type = body.type === 'count' ? 'count' : 'time';
    const qty = Math.min(Math.max(parseInt(body.qty || '1', 10) || 1, 1), 100);
    const days = Math.max(parseInt(body.days || '0', 10) || 0, 0);
    const totalCount = Math.max(parseInt(body.totalCount || '0', 10) || 0, 0);
    const cards = loadCards();
    const created = [];
    for (let i = 0; i < qty; i++) {
      let code; do { code = genCode(); } while (cards[code]);
      cards[code] = {
        code, type,
        days: type === 'time' ? (days || 1) : 0,
        totalCount: type === 'count' ? (totalCount || 1) : 0,
        usedCount: 0, unbindable: !!body.unbindable,
        markcode: '', firstUsedAt: 0, expiry: 0, enabled: true,
        createdAt: Date.now(), source: 'admin', note: String(body.note || '').slice(0, 200),
      };
      created.push(code);
    }
    writeJson(F_CARDS, cards);
    return sendJson(res, 200, {ok: true, cards: created});
  }

  // ===== 管理：更新卡密(禁用/启用/删除/解绑) =====
  if (p === '/api/admin/cards/update' && req.method === 'POST') {
    const body = await readBody(req);
    if (!body || typeof body.code !== 'string') return sendJson(res, 400, {ok: false, error: '参数错误'});
    const cards = loadCards();
    const code = body.code.trim().toUpperCase();
    const c = cards[code];
    if (!c) return sendJson(res, 404, {ok: false, error: '卡密不存在'});
    switch (body.action) {
      case 'disable': c.enabled = false; break;
      case 'enable': c.enabled = true; break;
      case 'delete': delete cards[code]; writeJson(F_CARDS, cards); return sendJson(res, 200, {ok: true, deleted: true});
      case 'unbind':
        if (c.unbindable) return sendJson(res, 400, {ok: false, error: '该卡不可解绑'});
        c.markcode = ''; c.firstUsedAt = 0; if (c.type === 'time') c.expiry = 0; break;
      default: return sendJson(res, 400, {ok: false, error: '未知操作'});
    }
    cards[code] = c; writeJson(F_CARDS, cards);
    return sendJson(res, 200, {ok: true, card: cardSummary(c)});
  }

  // ===== AstrBot：群自助天卡 =====
  if (p === '/api/bot/daycard' && req.method === 'POST') {
    const body = await readBody(req);
    const qq = body && body.qq != null ? String(body.qq).trim() : '';
    if (!qq) return sendJson(res, 400, {ok: false, error: '缺少 qq'});
    // unlimited 仅由持有 admin token 的机器人对其配置的管理员置真 → 绕过每日限领、不计入限流。
    const unlimited = !!(body && body.unlimited === true);
    const rate = loadRate();
    const today = dateStr(Date.now());
    if (!unlimited && rate[qq] === today) return sendJson(res, 200, {ok: false, error: '今日已领取，请明天再来'});
    const cards = loadCards();
    let code; do { code = genCode(); } while (cards[code]);
    cards[code] = {
      code, type: 'time', days: 1, totalCount: 0, usedCount: 0,
      unbindable: true, markcode: '', firstUsedAt: 0, expiry: 0, enabled: true,
      createdAt: Date.now(), source: 'bot:' + qq + (unlimited ? '(admin)' : ''), note: '群自助天卡',
    };
    writeJson(F_CARDS, cards);
    if (!unlimited) { rate[qq] = today; writeJson(F_RATE, rate); }
    return sendJson(res, 200, {ok: true, card: code, days: 1, unlimited});
  }

  if (p.startsWith('/api/')) return sendJson(res, 404, {ok: false, error: 'not found'});
  return serveStatic(res, p);
});

initToken();
server.listen(PORT, () => {
  if (!fs.existsSync(F_ANNOUNCE)) writeJson(F_ANNOUNCE, {enabled: false, title: '', content: '', updatedAt: 0});
  if (!fs.existsSync(F_CARDS)) writeJson(F_CARDS, {});
  console.log('========================================');
  console.log(`[edlflash] 统一后台已启动: http://0.0.0.0:${PORT}`);
  console.log(`[edlflash] 管理面板: http://0.0.0.0:${PORT}/  (用下方 token 登录)`);
  console.log(`[edlflash] 管理 token: ${ADMIN_TOKEN}`);
  console.log(`[edlflash] AstrBot 发卡: POST /api/bot/daycard  (头 X-Admin-Token=该 token)`);
  if (LICENSE_SECRET === 'edlflash-license-7f3a9c21d8e64b05') {
    console.log('[edlflash] 提示: LICENSE_SECRET 用默认值，需与 App LicenseModule.kt 内常量一致');
  }
  console.log('========================================');
});
