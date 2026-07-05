'use strict';
/*
 * 自写 ARX 流密码 + keyed MAC（非 AES/标准算法），用于 license 数据包应用层加密。
 * 与 native android/app/src/main/cpp/edlcipher.c 逐字节一致：
 *   cipher 全 32 位 ARX（>>>0 保无符号）；MAC 用 BigInt 保 64 位精度。
 * 改一处务必同步另一处。
 */
const crypto = require('crypto');

const MASK64 = (1n << 64n) - 1n;
const PRIME = 0x9e3779b97f4a7c15n;

function rotl32(x, n) { return ((x << n) | (x >>> (32 - n))) >>> 0; }
function rotl64(x, n) { return ((x << BigInt(n)) | (x >> BigInt(64 - n))) & MASK64; }
function ld32(b, o) { return (b[o] | (b[o+1]<<8) | (b[o+2]<<16) | (b[o+3]<<24)) >>> 0; }
function st32(b, o, v) { b[o]=v&0xff; b[o+1]=(v>>>8)&0xff; b[o+2]=(v>>>16)&0xff; b[o+3]=(v>>>24)&0xff; }
function ld64(b, o) {
  let v = 0n;
  for (let i = 0; i < 8; i++) v |= BigInt(b[o+i]) << BigInt(8*i);
  return v & MASK64;
}

function qr(s, a, b, c, d) {
  s[a]=(s[a]+s[b])>>>0; s[d]=rotl32((s[d]^s[a])>>>0,15);
  s[c]=(s[c]+s[d])>>>0; s[b]=rotl32((s[b]^s[c])>>>0,11);
  s[a]=(s[a]+s[b])>>>0; s[d]=rotl32((s[d]^s[a])>>>0,8);
  s[c]=(s[c]+s[d])>>>0; s[b]=rotl32((s[b]^s[c])>>>0,7);
}

function block(key, nonce, counter, out) {
  const st = new Uint32Array(16);
  st[0]=0x65646c66; st[1]=0x6c617368; st[2]=0x76327830; st[3]=0x9e3779b9;
  for (let i=0;i<8;i++) st[4+i]=ld32(key, i*4);
  st[12]=counter>>>0;
  st[13]=ld32(nonce,0); st[14]=ld32(nonce,4); st[15]=ld32(nonce,8);
  const w = Uint32Array.from(st);
  for (let r=0;r<10;r++){
    qr(w,0,4,8,12); qr(w,1,5,9,13); qr(w,2,6,10,14); qr(w,3,7,11,15);
    qr(w,0,5,10,15); qr(w,1,6,11,12); qr(w,2,7,8,13); qr(w,3,4,9,14);
  }
  for (let i=0;i<16;i++) st32(out, i*4, (w[i]+st[i])>>>0);
}

function streamXor(key, nonce, input) {
  const out = Buffer.alloc(input.length);
  const ks = Buffer.alloc(64);
  let counter = 0, off = 0;
  while (off < input.length) {
    block(key, nonce, counter++, ks);
    let n = Math.min(64, input.length - off);
    for (let i=0;i<n;i++) out[off+i]=input[off+i]^ks[i];
    off += n;
  }
  return out;
}

function mac(mk, nonce, data) {
  const pad = Buffer.alloc(64);
  block(mk, nonce, 0xffffffff, pad);
  const k0 = ld64(pad,0), k1 = ld64(pad,8);
  let acc = k0;
  const step = (chunk) => {
    acc = (acc ^ ld64(chunk,0)) & MASK64;
    acc = (acc * PRIME) & MASK64;
    acc = (rotl64(acc,23) ^ (acc >> 29n)) & MASK64;
    acc = (acc + k1) & MASK64;
  };
  let c = Buffer.alloc(8);
  nonce.copy(c, 0, 0, 8); step(c);
  c = Buffer.alloc(8); nonce.copy(c, 0, 8, 12);
  c[4]=data.length&0xff; c[5]=(data.length>>>8)&0xff;
  c[6]=(data.length>>>16)&0xff; c[7]=(data.length>>>24)&0xff; step(c);
  let off = 0;
  while (off < data.length) {
    c = Buffer.alloc(8);
    let n = Math.min(8, data.length - off);
    data.copy(c, 0, off, off+n); step(c);
    off += n;
  }
  acc = (acc ^ k1) & MASK64;
  const tag = Buffer.alloc(8);
  for (let i=0;i<8;i++) tag[i]=Number((acc >> BigInt(8*i)) & 0xffn);
  return tag;
}

function kdf(secret) {
  const key0 = Buffer.alloc(32);
  const slen = secret.length;
  for (let i=0;i<32;i++) key0[i]=(secret[slen ? i%slen : 0] ^ ((i*0x6d)&0xff) ^ 0xa5) & 0xff;
  const kdfNonce = Buffer.from([0x45,0x44,0x4c,0x4b,0x44,0x46,0x31,0,0,0,0,0]);
  const out = Buffer.alloc(64);
  block(key0, kdfNonce, 0, out);
  return { ck: out.subarray(0,32), mk: out.subarray(32,64) };
}

// 包格式：hex( nonce(12) || tag(8) || ciphertext )
function pack(secret, plaintext) {
  const { ck, mk } = kdf(secret);
  const nonce = crypto.randomBytes(12);
  const pt = Buffer.isBuffer(plaintext) ? plaintext : Buffer.from(plaintext, 'utf8');
  const ct = streamXor(ck, nonce, pt);
  const tag = mac(mk, nonce, ct);
  return Buffer.concat([nonce, tag, ct]).toString('hex');
}

function unpack(secret, hexBlob) {
  const blob = Buffer.from(String(hexBlob).trim(), 'hex');
  if (blob.length < 20) throw new Error('blob too short');
  const nonce = blob.subarray(0, 12);
  const tag = blob.subarray(12, 20);
  const ct = blob.subarray(20);
  const { ck, mk } = kdf(secret);
  const expect = mac(mk, nonce, ct);
  if (!crypto.timingSafeEqual(tag, expect)) throw new Error('mac mismatch');
  return streamXor(ck, nonce, ct).toString('utf8');
}

module.exports = { block, streamXor, mac, kdf, pack, unpack };
