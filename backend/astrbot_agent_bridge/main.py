import asyncio
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Optional

import aiohttp

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star, register


def to_bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on", "是", "启用"}


def to_int(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


@register("astrbot_agent_bridge", "EdlFlash", "本地 Agent Bridge", "0.1.0")
class AstrBotAgentBridge(Star):
    def __init__(self, context: Context, config=None):
        super().__init__(context)
        cfg = config or {}
        self.plugin_dir = Path(__file__).resolve().parent
        self.sidecar_host = str(cfg.get("sidecar_host") or "127.0.0.1")
        self.sidecar_port = to_int(cfg.get("sidecar_port"), 18787)
        self.sidecar_url = (
            cfg.get("sidecar_url") or f"http://{self.sidecar_host}:{self.sidecar_port}"
        ).rstrip("/")
        self.auto_start_sidecar = to_bool(cfg.get("auto_start_sidecar"), True)
        self.request_timeout = to_int(cfg.get("request_timeout"), 300)
        self.bridge_token = str(cfg.get("bridge_token") or "")
        self.allowed_groups = self._parse_csv(cfg.get("allowed_groups"))
        self.allowed_users = self._parse_csv(cfg.get("allowed_users"))
        self.claude_models = self._parse_csv(cfg.get("claude_models") or "sonnet,opus,haiku,fable")
        self.gpt_models = self._parse_csv(
            cfg.get("gpt_models") or "gpt-5.5,gpt-5.4,gpt-5.4-mini,gpt-5.3-codex-spark"
        )
        self.default_claude_model = str(cfg.get("default_claude_model") or "sonnet")
        self.default_gpt_model = str(cfg.get("default_gpt_model") or "gpt-5.5")
        self.claude_command = str(
            cfg.get("claude_command") or "reclaude -p --output-format json"
        )
        self.gpt_command = str(
            cfg.get("gpt_command") or "codex exec --json --sandbox read-only --skip-git-repo-check"
        )
        self.workdir = str(cfg.get("workdir") or self.plugin_dir)
        self.max_output_chars = to_int(cfg.get("max_output_chars"), 6000)
        self.sidecar_proc: Optional[subprocess.Popen] = None

        if self.auto_start_sidecar:
            self.sidecar_proc = self._start_sidecar()

    def _start_sidecar(self) -> Optional[subprocess.Popen]:
        script = self.plugin_dir / "agent_bridge.py"
        env = os.environ.copy()
        env.update(
            {
                "AGENT_BRIDGE_HOST": self.sidecar_host,
                "AGENT_BRIDGE_PORT": str(self.sidecar_port),
                "AGENT_BRIDGE_TOKEN": self.bridge_token,
                "AGENT_BRIDGE_CLAUDE_COMMAND": self.claude_command,
                "AGENT_BRIDGE_GPT_COMMAND": self.gpt_command,
                "AGENT_BRIDGE_CLAUDE_MODELS": ",".join(sorted(self.claude_models)),
                "AGENT_BRIDGE_GPT_MODELS": ",".join(sorted(self.gpt_models)),
                "AGENT_BRIDGE_DEFAULT_CLAUDE_MODEL": self.default_claude_model,
                "AGENT_BRIDGE_DEFAULT_GPT_MODEL": self.default_gpt_model,
                "AGENT_BRIDGE_WORKDIR": self.workdir,
                "AGENT_BRIDGE_TIMEOUT": str(self.request_timeout),
                "AGENT_BRIDGE_MAX_OUTPUT_CHARS": str(self.max_output_chars),
            }
        )

        try:
            return subprocess.Popen(
                [sys.executable, str(script)],
                cwd=str(self.plugin_dir),
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
        except Exception as exc:
            logger.error(f"[astrbot_agent_bridge] sidecar 启动失败: {exc}")
            return None

    async def terminate(self):
        if self.sidecar_proc and self.sidecar_proc.poll() is None:
            self.sidecar_proc.terminate()
            try:
                await asyncio.wait_for(asyncio.to_thread(self.sidecar_proc.wait), timeout=5)
            except asyncio.TimeoutError:
                self.sidecar_proc.kill()

    @filter.event_message_type(filter.EventMessageType.ALL)
    async def on_message(self, event: AstrMessageEvent):
        text = (event.message_str or "").strip()
        if text in {"/models", "/模型"}:
            if not self._allowed(event):
                yield event.plain_result("你没有权限使用 Agent bridge")
                return
            yield event.plain_result(await self._models_text())
            return

        agent, model, prompt = self._parse_command(text)
        if not agent:
            return
        if not self._allowed(event):
            yield event.plain_result("你没有权限使用 Agent bridge")
            return
        if not prompt:
            yield event.plain_result(f"用法：/{agent} [模型] <问题>")
            return

        try:
            result = await self._chat(agent, model, prompt)
        except Exception as exc:
            logger.error(f"[astrbot_agent_bridge] sidecar 请求失败: {exc}")
            yield event.plain_result("Agent bridge 暂时不可用")
            return

        output = result.get("output") or result.get("error") or "无输出"
        yield event.plain_result(output)

    @staticmethod
    def _parse_command(text: str) -> tuple[str, str, str]:
        aliases = {
            "claude": ("claude", "reclaude"),
            "gpt": ("gpt", "codex"),
        }
        for agent, names in aliases.items():
            for name in names:
                prefix = f"/{name}"
                if text == prefix:
                    return agent, "", ""
                if text.startswith(prefix + " "):
                    rest = text[len(prefix) :].strip()
                    return agent, "", rest
        return "", "", ""

    @staticmethod
    def _parse_csv(value: Any) -> set[str]:
        if not value:
            return set()
        if isinstance(value, (list, tuple, set)):
            return {str(item).strip() for item in value if str(item).strip()}
        normalized = str(value).replace("，", ",").replace(" ", ",")
        return {item.strip() for item in normalized.split(",") if item.strip()}

    def _allowed(self, event: AstrMessageEvent) -> bool:
        group_id = str(event.get_group_id() or "")
        sender_id = str(event.get_sender_id() or "")
        if self.allowed_groups and group_id not in self.allowed_groups:
            return False
        if self.allowed_users and sender_id not in self.allowed_users:
            return False
        return True

    def _extract_model(self, agent: str, prompt: str) -> tuple[str, str]:
        words = prompt.split(maxsplit=1)
        if not words:
            return "", ""
        models = self.claude_models if agent == "claude" else self.gpt_models
        candidate = words[0]
        if candidate in models:
            return candidate, words[1].strip() if len(words) > 1 else ""
        return "", prompt

    async def _chat(self, agent: str, model: str, prompt: str) -> dict[str, Any]:
        model, prompt = self._extract_model(agent, prompt) if not model else (model, prompt)
        headers = {"Content-Type": "application/json"}
        if self.bridge_token:
            headers["X-Agent-Bridge-Token"] = self.bridge_token
        timeout = aiohttp.ClientTimeout(total=self.request_timeout + 10)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.post(
                f"{self.sidecar_url}/v1/chat",
                json={"agent": agent, "model": model, "prompt": prompt},
                headers=headers,
            ) as resp:
                return await resp.json(content_type=None)

    async def _models_text(self) -> str:
        headers = {}
        if self.bridge_token:
            headers["X-Agent-Bridge-Token"] = self.bridge_token
        timeout = aiohttp.ClientTimeout(total=10)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(f"{self.sidecar_url}/v1/models", headers=headers) as resp:
                data = await resp.json(content_type=None)
        rows = data.get("data") if isinstance(data, dict) else None
        if not isinstance(rows, list):
            return "无法获取模型列表"
        claude = [item.get("id") for item in rows if item.get("owned_by") == "claude"]
        gpt = [item.get("id") for item in rows if item.get("owned_by") == "gpt"]
        return "可用模型：\nClaude: " + ", ".join(claude) + "\nGPT: " + ", ".join(gpt)
