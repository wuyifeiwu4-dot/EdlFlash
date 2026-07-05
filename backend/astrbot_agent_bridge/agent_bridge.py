import argparse
import json
import os
import subprocess
import shlex
import time
import uuid
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlparse


@dataclass(frozen=True)
class BridgeConfig:
    host: str
    port: int
    token: str
    claude_command: str
    gpt_command: str
    claude_models: tuple[str, ...]
    gpt_models: tuple[str, ...]
    default_claude_model: str
    default_gpt_model: str
    workdir: str
    timeout: int
    max_output_chars: int


def parse_args() -> BridgeConfig:
    parser = argparse.ArgumentParser(description="AstrBot local agent bridge")
    parser.add_argument("--host", default=os.getenv("AGENT_BRIDGE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("AGENT_BRIDGE_PORT", "18787")))
    parser.add_argument("--token", default=os.getenv("AGENT_BRIDGE_TOKEN", ""))
    parser.add_argument(
        "--claude-command",
        default=os.getenv("AGENT_BRIDGE_CLAUDE_COMMAND", "reclaude -p --output-format json"),
    )
    parser.add_argument(
        "--gpt-command",
        default=os.getenv(
            "AGENT_BRIDGE_GPT_COMMAND",
            "codex exec --json --sandbox read-only --skip-git-repo-check",
        ),
    )
    parser.add_argument(
        "--claude-models",
        default=os.getenv("AGENT_BRIDGE_CLAUDE_MODELS", "sonnet,opus,haiku,fable"),
    )
    parser.add_argument(
        "--gpt-models",
        default=os.getenv(
            "AGENT_BRIDGE_GPT_MODELS",
            "gpt-5.5,gpt-5.4,gpt-5.4-mini,gpt-5.3-codex-spark",
        ),
    )
    parser.add_argument(
        "--default-claude-model",
        default=os.getenv("AGENT_BRIDGE_DEFAULT_CLAUDE_MODEL", ""),
    )
    parser.add_argument(
        "--default-gpt-model",
        default=os.getenv("AGENT_BRIDGE_DEFAULT_GPT_MODEL", ""),
    )
    parser.add_argument("--workdir", default=os.getenv("AGENT_BRIDGE_WORKDIR", ""))
    parser.add_argument("--timeout", type=int, default=int(os.getenv("AGENT_BRIDGE_TIMEOUT", "300")))
    parser.add_argument(
        "--max-output-chars",
        type=int,
        default=int(os.getenv("AGENT_BRIDGE_MAX_OUTPUT_CHARS", "6000")),
    )
    args = parser.parse_args()
    return BridgeConfig(
        host=args.host,
        port=args.port,
        token=args.token,
        claude_command=args.claude_command,
        gpt_command=args.gpt_command,
        claude_models=parse_csv(args.claude_models),
        gpt_models=parse_csv(args.gpt_models),
        default_claude_model=args.default_claude_model.strip(),
        default_gpt_model=args.default_gpt_model.strip(),
        workdir=args.workdir,
        timeout=args.timeout,
        max_output_chars=args.max_output_chars,
    )


def parse_csv(value: str) -> tuple[str, ...]:
    return tuple(item.strip() for item in value.replace("，", ",").split(",") if item.strip())


def split_command(command: str, prompt: str, model: str) -> list[str]:
    parts = shlex.split(command)
    if not parts:
        raise ValueError("agent command is empty")
    if model and not contains_model_arg(parts):
        parts.extend(["--model", model])
    return [*parts, prompt]


def contains_model_arg(parts: list[str]) -> bool:
    return any(part == "--model" or part == "-m" or part.startswith("--model=") for part in parts)


def clean_output(text: str, limit: int) -> str:
    output = text.strip()
    if limit > 0 and len(output) > limit:
        output = output[:limit].rstrip() + "\n...[输出已截断]"
    return output


def parse_cli_output(stdout: str) -> tuple[Optional[bool], str, str]:
    if not stdout.strip():
        return None, "", ""
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError:
        jsonl_output = parse_jsonl_agent_output(stdout)
        if jsonl_output:
            return True, jsonl_output, ""
        return None, stdout, ""
    if isinstance(data, dict):
        event_output = extract_agent_message(data)
        if event_output:
            return True, event_output, ""
        result = data.get("result")
        output = result if isinstance(result, str) else stdout
        if data.get("is_error") is True:
            return False, output, str(data.get("stop_reason") or "cli error")
        return True, output, ""
    return None, stdout, ""


def parse_jsonl_agent_output(stdout: str) -> str:
    messages: list[str] = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            text = extract_agent_message(event)
            if text:
                messages.append(text)
    return "\n".join(messages)


def extract_agent_message(event: dict[str, Any]) -> str:
    item = event.get("item")
    if not isinstance(item, dict) or item.get("type") != "agent_message":
        return ""
    text = item.get("text")
    return text.strip() if isinstance(text, str) else ""


def run_agent(command: str, prompt: str, model: str, config: BridgeConfig) -> dict[str, Any]:
    workdir = config.workdir.strip()
    if workdir:
        path = Path(workdir).expanduser()
        if not path.is_dir():
            return {"ok": False, "error": f"workdir 不存在: {path}", "output": ""}
        cwd = str(path)
    else:
        cwd = None

    try:
        proc = subprocess.run(
            split_command(command, prompt, model),
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=config.timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": f"执行超时({config.timeout}s)", "output": ""}

    if proc.returncode == 0:
        ok, output, error = parse_cli_output(proc.stdout)
        return {
            "ok": True if ok is None else ok,
            "error": error,
            "output": clean_output(output, config.max_output_chars),
        }

    message = proc.stderr.strip() or proc.stdout.strip() or f"CLI 退出码 {proc.returncode}"
    return {
        "ok": False,
        "error": f"CLI 退出码 {proc.returncode}",
        "output": clean_output(message, config.max_output_chars),
    }


class AgentBridgeHandler(BaseHTTPRequestHandler):
    config: BridgeConfig

    def log_message(self, _format: str, *_args: Any) -> None:
        return

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self.respond(
                {
                    "ok": True,
                    "agents": ["claude", "gpt"],
                    "workdir": self.config.workdir,
                    "models": self.model_payload(),
                }
            )
            return
        if path == "/v1/models":
            if not self.authorized():
                self.respond_openai_error("unauthorized", 401)
                return
            self.respond({"object": "list", "data": self.openai_models()})
            return
        self.respond({"ok": False, "error": "not found", "output": ""}, 404)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path == "/v1/chat/completions":
            self.chat_completions()
            return
        if path not in {"/v1/chat", "/run"}:
            self.respond({"ok": False, "error": "not found", "output": ""}, 404)
            return
        if not self.authorized():
            self.respond({"ok": False, "error": "unauthorized", "output": ""}, 401)
            return

        try:
            data = self.read_json()
        except ValueError as exc:
            self.respond({"ok": False, "error": str(exc), "output": ""}, 400)
            return

        agent = str(data.get("agent") or data.get("provider") or "").strip().lower()
        model = str(data.get("model") or "").strip()
        prompt = str(data.get("prompt") or "").strip()
        agent, model, error = self.resolve_agent_model(agent, model)
        if error:
            self.respond({"ok": False, "error": error, "output": ""}, 400)
            return
        if not prompt:
            self.respond({"ok": False, "error": "prompt is required", "output": ""}, 400)
            return

        command = self.config.claude_command if agent == "claude" else self.config.gpt_command
        try:
            result = run_agent(command, prompt, model, self.config)
        except (OSError, ValueError) as exc:
            result = {"ok": False, "error": str(exc), "output": ""}
        result["agent"] = agent
        result["model"] = model
        self.respond(result)

    def chat_completions(self) -> None:
        if not self.authorized():
            self.respond_openai_error("unauthorized", 401)
            return
        try:
            data = self.read_json()
        except ValueError as exc:
            self.respond_openai_error(str(exc), 400)
            return
        if data.get("stream"):
            self.respond_openai_error("stream is not supported by this local bridge", 400)
            return

        agent = str(data.get("agent") or data.get("provider") or "").strip().lower()
        model = str(data.get("model") or "").strip()
        agent, model, error = self.resolve_agent_model(agent, model)
        if error:
            self.respond_openai_error(error, 400)
            return

        prompt = messages_to_prompt(data.get("messages"))
        if not prompt:
            self.respond_openai_error("messages are required", 400)
            return

        command = self.config.claude_command if agent == "claude" else self.config.gpt_command
        try:
            result = run_agent(command, prompt, model, self.config)
        except (OSError, ValueError) as exc:
            self.respond_openai_error(str(exc), 500)
            return
        if not result.get("ok"):
            self.respond_openai_error(result.get("output") or result.get("error") or "agent failed", 502)
            return
        self.respond(openai_chat_response(model, result.get("output") or ""))

    def authorized(self) -> bool:
        if not self.config.token:
            return True
        if self.headers.get("X-Agent-Bridge-Token") == self.config.token:
            return True
        auth = self.headers.get("Authorization") or ""
        return auth == f"Bearer {self.config.token}"

    def resolve_agent_model(self, agent: str, model: str) -> tuple[str, str, str]:
        if not agent:
            agent = self.infer_agent(model)
        if agent not in {"claude", "gpt"}:
            return "", "", "unsupported agent"

        model = model or self.default_model(agent)
        if model and not self.model_allowed(agent, model):
            return "", "", f"model is not allowed for {agent}: {model}"
        return agent, model, ""

    def infer_agent(self, model: str) -> str:
        if model in self.config.claude_models:
            return "claude"
        if model in self.config.gpt_models:
            return "gpt"
        lowered = model.lower()
        if lowered.startswith("claude") or lowered in {"sonnet", "opus", "haiku", "fable"}:
            return "claude"
        return "gpt"

    def default_model(self, agent: str) -> str:
        if agent == "claude":
            return self.config.default_claude_model
        return self.config.default_gpt_model

    def model_allowed(self, agent: str, model: str) -> bool:
        models = self.config.claude_models if agent == "claude" else self.config.gpt_models
        return not models or model in models

    def model_payload(self) -> dict[str, list[str]]:
        return {
            "claude": list(self.config.claude_models),
            "gpt": list(self.config.gpt_models),
        }

    def openai_models(self) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        for owner, models in (("claude", self.config.claude_models), ("gpt", self.config.gpt_models)):
            rows.extend({"id": model, "object": "model", "owned_by": owner} for model in models)
        return rows

    def read_json(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length") or "0")
        except ValueError as exc:
            raise ValueError("invalid content length") from exc
        if length <= 0:
            raise ValueError("empty body")
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("invalid json") from exc
        if not isinstance(data, dict):
            raise ValueError("json body must be an object")
        return data

    def respond(self, payload: dict[str, Any], status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def respond_openai_error(self, message: str, status: int) -> None:
        self.respond(
            {
                "error": {
                    "message": message,
                    "type": "agent_bridge_error",
                    "code": status,
                }
            },
            status,
        )


def messages_to_prompt(messages: Any) -> str:
    if not isinstance(messages, list):
        return ""
    parts: list[str] = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        role = str(message.get("role") or "user")
        content = normalize_content(message.get("content"))
        if content:
            parts.append(f"{role}: {content}")
    return "\n\n".join(parts)


def normalize_content(content: Any) -> str:
    if isinstance(content, str):
        return content.strip()
    if not isinstance(content, list):
        return ""
    parts: list[str] = []
    for item in content:
        if isinstance(item, dict):
            text = item.get("text")
            if isinstance(text, str) and text.strip():
                parts.append(text.strip())
    return "\n".join(parts)


def openai_chat_response(model: str, content: str) -> dict[str, Any]:
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }


def main() -> None:
    config = parse_args()
    AgentBridgeHandler.config = config
    server = ThreadingHTTPServer((config.host, config.port), AgentBridgeHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
