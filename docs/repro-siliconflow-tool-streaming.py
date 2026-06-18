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
