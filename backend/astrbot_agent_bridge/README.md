# AstrBot Agent Bridge

一个随 AstrBot 插件加载自启动的本地 agent 适配层。它不是上游 API 反代：AstrBot 只调用本机 sidecar，sidecar 再执行本机 `reclaude` / `codex` CLI。sidecar 只使用 Python 标准库，插件侧复用 AstrBot 环境里的 `aiohttp`。

## 命令

- `/claude <问题>` 或 `/reclaude <问题>`：调用 `claude_command`
- `/gpt <问题>` 或 `/codex <问题>`：调用 `gpt_command`

## 默认配置

- `sidecar_url`: `http://127.0.0.1:18787`
- `auto_start_sidecar`: `true`
- `claude_command`: `reclaude -p --output-format json`
- `gpt_command`: `codex exec --json --sandbox read-only --skip-git-repo-check`
- `workdir`: 留空时使用插件目录
- `allowed_groups` / `allowed_users`: 留空不限制；生产使用建议至少限制用户或群

如果要让 agent 操作某个项目，把 `workdir` 配成项目根目录。默认 Codex 使用只读 sandbox，适合先在聊天里问答和分析；需要执行修改时再显式调整命令。

## 独立运行

```bash
python3 agent_bridge.py --host 127.0.0.1 --port 18787
```

Smoke test:

```bash
curl -s http://127.0.0.1:18787/health
curl -s -X POST http://127.0.0.1:18787/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"agent":"gpt","prompt":"只回答 OK"}'
```
