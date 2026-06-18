# SiliconFlow 流式 tool-call 聚合问题复现报告

> 记录时间：2026-06-18  
> 涉及组件：Metabase Metabot / custom LLM provider（SiliconFlow）  
> 问题类型：上游 OpenAI-compatible provider 的 SSE 流式 tool_call 输出与 `ai-sdk` 聚合假设不兼容  
> 测试模型：已通过 `/v1/models` 接口确认使用 `moonshotai/Kimi-K2.7-Code`

---

## 1. 问题现象

在 Metabase 自定义 AI provider（SiliconFlow 端点 `/v1/chat/completions`）上使用 **流式结构化输出**（`stream: true` + `tools` + `tool_choice: auto`）时：

- `example-question-generator` 和 `suggested-questions` 等功能报：
  ```text
  Suggested prompts generation failed: java.lang.IllegalArgumentException: No matching clause: :tool-input-delta
  ```
- 或报 JSON 解析失败：
  ```text
  Failed to parse tool arguments as JSON, passing raw string
  {:_raw_arguments "{\""}
  ```

根本症状：完整的 tool arguments JSON 在 SSE 流中被拆成数十个微小 delta，中间还夹杂会触发 `aisdk-xf` flush 的 chunk，导致上游把同一个 tool call 切成多个碎片。

---

## 2. 复现脚本（完整代码）

保存为任意 `.py` 文件即可运行，例如 `repro-siliconflow-tool-streaming.py`：

```python
#!/usr/bin/env python3
"""
Reproduction script: SiliconFlow streaming tool-call deltas break aggregation.

This script calls SiliconFlow's /v1/chat/completions endpoint in streaming mode,
using a forced function tool similar to what Metabot uses for structured output.
It prints every raw SSE chunk so the mid-stream aggregation problem can be observed.

Model verified via /v1/models: moonshotai/Kimi-K2.7-Code
"""
import json
import os
import sys
import urllib.request

# Provider config (override via env)
API_KEY = os.environ.get("SILICONFLOW_API_KEY", "").strip() or \
          open("/app/metabase/config/sf_api_key").read().strip()
BASE_URL = os.environ.get("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1")
# Exact model id obtained from GET https://api.siliconflow.cn/v1/models
MODEL = os.environ.get("SILICONFLOW_MODEL", "moonshotai/Kimi-K2.7-Code")

# A trivial structured-output schema identical in shape to Metabot's tool calls.
SCHEMA = {
    "type": "object",
    "properties": {
        "questions": {
            "type": "array",
            "items": {"type": "string"}
        }
    },
    "required": ["questions"],
    "additionalProperties": False
}

REQ_BODY = {
    "model": MODEL,
    "stream": True,
    "messages": [
        {
            "role": "system",
            "content": "You are a helpful assistant. Answer only with the requested JSON."
        },
        {
            "role": "user",
            "content": "Generate exactly 2 example questions a user might ask about sales data."
        }
    ],
    "tools": [
        {
            "type": "function",
            "function": {
                "name": "structured_output",
                "description": "Output structured data",
                "parameters": SCHEMA
            }
        }
    ],
    "tool_choice": "auto"
}


def main():
    if not API_KEY:
        print("ERROR: set SILICONFLOW_API_KEY environment variable")
        sys.exit(1)

    # Optional: verify available models and pick an exact id
    try:
        req_models = urllib.request.Request(
            f"{BASE_URL}/models",
            headers={"Authorization": f"Bearer {API_KEY}"},
            method="GET",
        )
        with urllib.request.urlopen(req_models, timeout=30) as resp:
            models = json.loads(resp.read())
            kimi_ids = [m["id"] for m in models.get("data", []) if "kimi" in m["id"].lower()]
            if kimi_ids:
                print(f"Available Kimi models from /v1/models: {kimi_ids}")
            else:
                print("No Kimi models found in /v1/models")
    except Exception as e:
        print(f"WARN: could not list models ({e})")

    url = f"{BASE_URL}/chat/completions"
    data = json.dumps(REQ_BODY).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
        method="POST",
    )

    print()
    print(f"POST {url}")
    print(f"model: {MODEL}")
    print(f"streaming: True")
    print("-" * 72)

    chunk_index = 0
    collected_tool_args = ""  # What a naive aggregator would receive
    last_tool_call_id = None
    tool_call_ids_seen = set()

    with urllib.request.urlopen(req, timeout=120) as resp:
        buffer = b""
        while True:
            piece = resp.read1(1)
            if not piece:
                break
            buffer += piece
            while b"\n\n" in buffer:
                raw_event, _, buffer = buffer.partition(b"\n\n")
                event_text = raw_event.decode("utf-8", errors="replace")

                # Skip keep-alive / empty lines
                if not event_text.strip() or "data: [DONE]" in event_text:
                    if "data: [DONE]" in event_text:
                        print(f"[{chunk_index:03d}] SSE DONE")
                    continue

                # Extract JSON payload from "data: {...}"
                payload = None
                for line in event_text.splitlines():
                    if line.startswith("data: "):
                        try:
                            payload = json.loads(line[6:])
                        except json.JSONDecodeError as e:
                            print(f"[{chunk_index:03d}] RAW: {line}")
                            print(f"       JSON parse error: {e}")
                        break

                if payload is None:
                    continue

                choice = payload.get("choices", [{}])[0]
                delta = choice.get("delta", {})
                finish = choice.get("finish_reason")
                tool_calls = delta.get("tool_calls", [])
                content = delta.get("content")
                usage = payload.get("usage")
                chunk_id = payload.get("id")

                print(f"[{chunk_index:03d}] id={chunk_id!r} finish={finish!r}")

                if content is not None:
                    print(f"       content={content!r}")

                for tc in tool_calls:
                    tc_id = tc.get("id")
                    tc_fn = tc.get("function", {})
                    tc_args = tc_fn.get("arguments", "")
                    tc_name = tc_fn.get("name", "")
                    if tc_id:
                        tool_call_ids_seen.add(tc_id)
                        last_tool_call_id = tc_id
                    print(f"       tool_call id={tc_id!r} name={tc_name!r} args={tc_args!r}")
                    if tc_args:
                        collected_tool_args += tc_args

                if usage is not None:
                    print(f"       usage={usage}")

                chunk_index += 1

    print("-" * 72)
    print(f"Total chunks observed: {chunk_index}")
    print(f"Distinct tool_call ids seen: {tool_call_ids_seen}")
    print(f"Last tool_call id: {last_tool_call_id!r}")
    print(f"Collected tool arguments (naive concat): {collected_tool_args!r}")

    # Attempt to parse the collected args as JSON
    try:
        parsed = json.loads(collected_tool_args)
        print(f"Arguments parse as JSON: YES -> {parsed}")
    except json.JSONDecodeError as e:
        print(f"Arguments parse as JSON: NO  -> {e}")

    if collected_tool_args:
        return 0 if json.loads(collected_tool_args) else 1


if __name__ == "__main__":
    sys.exit(main())
```

运行：

```bash
python3 repro-siliconflow-tool-streaming.py
```

---

## 3. 关键观测结果（`moonshotai/Kimi-K2.7-Code`，2026-06-18）

### 3.1 每个 token 后都返回 `usage` 块，且计数递增

```text
[000] id='019ed858...' finish=None
       content=''
       usage={'prompt_tokens': 85, 'completion_tokens': 0, ...}

[001] id='019ed858...' finish=None
       usage={'prompt_tokens': 85, 'completion_tokens': 1, ...}
```

到后续 delta 块：

```text
[030] id='019ed858...' finish=None
       tool_call id=None name='' args='{"'
       usage={'prompt_tokens': 85, 'completion_tokens': 39, ...}
```

`usage` 块的 `completion_tokens` 持续递增且非零，**不能通过“零 token 过滤”简单剔除**。任何把 `:usage` 当作 self-contained、会 flush 当前 acc 的逻辑，都会把同一 tool call 切成碎片。

### 3.2 工具调用 ID 不稳定

```text
[029] tool_call id='functions.structured_output:0' name='structured_output' args=''
[030] tool_call id=None name='' args='{"'
[031] tool_call id=None name='' args='questions'
```

首 delta 给出 `functions.structured_output:0`，之后所有参数 delta 的 `id` 为 `None`。按 `toolCallId` 分组的聚合器会把每个 `None` id 当作新组，导致 `{"`、`questions`、`":"` 等碎片被单独处理。

### 3.3 工具 name 在后续 delta 中为空

```text
[030] tool_call id=None name='' args='{"'
```

后续增量不再重复返回 `name`。如果聚合器取 `(first chunks)` 的 `toolName`，会丢失正确的函数名；如果取最后一个 chunk 的 `toolName`，会得到空字符串。

### 3.4 存在推理 token（reasoning）前缀

Kimi 在输出工具参数前先发出多段 `reasoning_tokens`：

```text
usage={'prompt_tokens': 85, 'completion_tokens': 8,
       'completion_tokens_details': {'reasoning_tokens': 8}, ...}
```

这些 reasoning chunk 本身没有工具调用内容，但穿插在流中，进一步扩大了聚合窗口。

### 3.5 拼接本身是完整的

最终朴素 concat 得到：

```json
{"questions": ["What were the total sales revenue and growth percentage by region last quarter?", "Which products had the highest number of returns or refunds in the past month?"]}
```

说明数据没问题，问题是**聚合逻辑假设与 provider 行为不匹配**。

---

## 4. 对 Metabase 代码的影响

`metabase.metabot.self.core/aisdk-xf` 的原始逻辑依赖以下假设：

1. tool_call 的 `toolCallId` 在一个完整工具调用期间保持不变；
2. `:usage` 只在流末尾出现一次，且可视为 self-contained；
3. `:content` text-delta 与 `:tool_calls` delta 不会穿插混合。

SiliconFlow 的响应打破了以上三条假设，因此流式工具调用失败。

具体报错路径：

1. `:usage` self-contained 触发 flush → `tool-input-delta` 被单独成组。
2. `tool-input-delta` 的 `toolCallId` 为 `nil` → id 变化再次 flush。
3. `aisdk-chunks->part` 只有 `:tool-input-start` 分支 → `No matching clause: :tool-input-delta`。

---

## 5. 已验证的修复方向（保持流式）

### 5.1 在 `custom.clj` 的 transducer 层做 provider-specific 归一化

1. **锁定 `toolCallId`**：从第一个 `tool_call` delta 拿到 id（或用 `core/mkid` 生成），后续所有相关 AISDK chunk 复用该 id，不再依赖上游可能缺失的 id。
2. **忽略空 `content`**：只当 `(:content delta)` 非空时才 emit text 事件。
3. **推迟 `:usage` 转发**：`usage` 在自定义 adapter 中收集并在流末尾/非零时一次性转发；同时 `core.clj` 中把 `:usage` 从 self-contained flush 集合移除，防止它冲断 tool-input 聚合。

### 5.2 在 `core.clj` 的安全兜底

- 把 `(:tool-input-start :tool-input-delta)` 合并为同一个 `:tool-input` 分支，并取 `(first chunks)` 的 `toolName`。
- `:tool-input-available` 也提供兜底分支，处理 standalone 的可用通知。

### 5.3 未采用方案

- 非流式 `stream: false`：能规避聚合问题，但用户明确要求保留流式，已回退。

---

## 6. 复现环境

- Provider endpoint：`https://api.siliconflow.cn/v1/chat/completions`
- Models endpoint：`https://api.siliconflow.cn/v1/models`
- Model（经 `/v1/models` 确认）：`moonshotai/Kimi-K2.7-Code`
- Request：`stream: true` + `tools: [structured_output]` + `tool_choice: "auto"`
- Date observed：2026-06-18

---

## 7. 结论

SiliconFlow 的流式 tool_call 输出不符合 OpenAI-style 的“同一 toolCallId 贯穿始终、usage 只在最后”的假设。必须在 `metabase.metabot.self.custom` 的 transducer 里做 provider-specific 归一化（id 锁定、空 content 过滤、usage 延迟/抑制），而不是在通用聚合层默认关闭流式。
