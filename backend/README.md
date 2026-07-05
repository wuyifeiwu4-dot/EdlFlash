# EdlFlash 统一后台 (backend)

零依赖纯 Node.js：**卡密验证 + 公告管理 + AstrBot 群自助发卡**，三合一。
无管理员账号密码，使用启动时随机生成的 **管理 token** 鉴权。

## 运行

```bash
cd backend
node server.js
# 自定义端口/密钥：
PORT=8787 LICENSE_SECRET=和App一致的密钥 node server.js
```

- 无需 `npm install`，Node ≥ 14 即可。建议 `pm2 start server.js --name edlflash` 常驻。
- 首次启动会随机生成管理 token 存到 `data/admin_token.txt` 并在日志打印；**用它登录管理面板、配置 AstrBot**。
- 管理面板：`http://服务器IP:8787/`（粘贴 token 登录）→ 卡密(发卡/列表/禁用/解绑/删除) + 公告(编辑/开关/预览)。

## 配置（环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8787` | 监听端口 |
| `ADMIN_TOKEN` | 随机持久化 | 覆盖随机管理 token（一般不用设，让它随机即可） |
| `LICENSE_SECRET` | 内置固定值 | 卡密响应 HMAC 签名密钥，**必须与 App `LicenseModule.kt` 内 `licenseSecret` 完全一致** |

## 卡密体系（对齐微验）

- **时卡**：按天，首次激活起 N 天到期。
- **次卡**：按可登录次数，每次验证消耗一次。
- **设备绑定**：首次验证绑定设备机器码(markcode)；换设备验证被拒。
- **不可解绑(unbindable)**：绑定后永不可解绑（机器人天卡默认开启）。
- **防伪**：成功响应带 `HMAC-SHA256(LICENSE_SECRET)` 签名 + 服务器时间，App 验签 + 时间偏差校验后才放行。

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/card/verify` | 无 | App 用：`{card,markcode,t}` → `{ok,code,mode,expiry/remaining,serverTime,sign}` |
| GET | `/api/announcement` | 无 | App 用：当前公告，关闭/空时 `enabled:false` |
| GET | `/api/admin/overview` | `X-Admin-Token` | 概览(公告+卡密统计) |
| POST | `/api/admin/announcement` | `X-Admin-Token` | `{enabled,title,content}` |
| GET | `/api/admin/cards?limit=` | `X-Admin-Token` | 卡密列表 |
| POST | `/api/admin/cards/generate` | `X-Admin-Token` | `{type:'time'|'count',days,totalCount,unbindable,qty,note}` |
| POST | `/api/admin/cards/update` | `X-Admin-Token` | `{code,action:'disable'|'enable'|'delete'|'unbind'}` |
| POST | `/api/bot/daycard` | `X-Admin-Token` | AstrBot：`{qq}` → 天卡(1天/不可解绑/每QQ每天1张) |

## 对接 App

编辑 `android/app/src/main/java/com/edlflash/edl/LicenseModule.kt`：
```kotlin
private val backendUrl = "http://你的服务器IP:8787"   // 留空则无法验证
private val licenseSecret = "edlflash-license-7f3a9c21d8e64b05"  // 与后台 LICENSE_SECRET 一致
```
改后需重新 `assembleRelease` 打包。http(非https)已允许明文(`usesCleartextTraffic`)。

## 对接 AstrBot 自助发卡

把 `astrbot_plugin/` 整个目录放到 AstrBot 的 `data/plugins/edlflash_card/`，重启/加载后在插件配置页填：
- `backend_url`：本后台地址
- `admin_token`：本后台管理 token
- `group_id`：`1077185480`（限定群；留空=所有群）

群成员发送 **`/获取卡密`** → 机器人回复当日天卡。每人每天 1 张（后台按 QQ 限流）。

## 数据

- `data/cards.json` 卡密、`data/announcement.json` 公告、`data/ratelimit.json` 发卡限流、`data/admin_token.txt` 管理 token。
- 直接备份 `data/` 即可。建议生产用 https 反代。
