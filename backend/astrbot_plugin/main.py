"""
EdlFlash 群自助发卡 AstrBot 插件。
指定 QQ 群内任何成员发送 "/获取卡密"(或"获取卡密") → 生成天卡(1天/不可解绑) → 回复。
普通成员每人每天 1 张；配置中的机器人管理员可无限获取(绕过每日限领)。

安装：本目录放到 AstrBot 的 data/plugins/edlflash_card/，重启或在面板加载。
配置(插件配置页)：backend_url / admin_token / group_id / admins。
"""
import aiohttp

from astrbot.api.event import filter, AstrMessageEvent
from astrbot.api.star import Context, Star, register
from astrbot.api import logger


@register("edlflash_card", "EdlFlash", "群自助发卡(天卡)", "1.1.0")
class EdlFlashCard(Star):
    def __init__(self, context: Context, config=None):
        super().__init__(context)
        cfg = config or {}
        self.backend_url = (cfg.get("backend_url") or "http://127.0.0.1:8787").rstrip("/")
        self.admin_token = cfg.get("admin_token") or ""
        # 仅在该 QQ 群响应；留空=所有群
        self.group_id = str(cfg.get("group_id") or "1077185480").strip()
        # 机器人管理员(配置项，非 QQ 群管)：可无限获取，绕过每日限领
        self.admins = self._parse_admins(cfg.get("admins"))
        self.triggers = {"/获取卡密", "获取卡密", "/获取卡密 ", "获取卡密 "}

    @staticmethod
    def _parse_admins(raw):
        if not raw:
            return set()
        if isinstance(raw, (list, tuple)):
            return {str(x).strip() for x in raw if str(x).strip()}
        return {s.strip() for s in str(raw).replace("，", ",").replace(" ", ",").split(",") if s.strip()}

    # 监听全部群消息再精确匹配文本，不依赖唤醒前缀配置，触发最稳。
    @filter.event_message_type(filter.EventMessageType.GROUP_MESSAGE)
    async def on_group_message(self, event: AstrMessageEvent):
        text = (event.message_str or "").strip()
        if text not in self.triggers:
            return

        gid = str(event.get_group_id() or "")
        if self.group_id and gid != self.group_id:
            return  # 非指定群，不响应

        qq = str(event.get_sender_id() or "")
        if not qq:
            yield event.plain_result("无法获取你的 QQ，发卡失败")
            return
        if not self.admin_token:
            yield event.plain_result("发卡未配置(缺少 admin_token)，请联系管理员")
            return

        unlimited = qq in self.admins
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.backend_url}/api/bot/daycard",
                    json={"qq": qq, "unlimited": unlimited},
                    headers={"X-Admin-Token": self.admin_token, "Content-Type": "application/json"},
                    timeout=aiohttp.ClientTimeout(total=15),
                ) as resp:
                    data = await resp.json(content_type=None)
        except Exception as e:  # noqa: BLE001
            logger.error(f"[edlflash_card] 请求后台失败: {e}")
            yield event.plain_result("发卡服务暂时不可用，请稍后再试")
            return

        if data.get("ok"):
            tag = "·管理员无限领取" if unlimited else "·每人每天1张"
            yield event.plain_result(
                f"✅ 天卡已生成(1天·不可解绑{tag})\n"
                f"卡密：{data.get('card', '')}\n"
                f"请尽快在 App 中输入激活(首次激活即绑定本机)"
            )
        else:
            yield event.plain_result(f"❌ {data.get('error', '发卡失败')}")
