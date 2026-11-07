"""Scripted fake of anthropic.AsyncAnthropic (only client.beta.messages.create is used)."""
from __future__ import annotations

import asyncio
import copy
from types import SimpleNamespace

from anthropic.types.beta import BetaMessage

MODEL = "claude-opus-5-5"

VALID_FEATURES = {
    "evidence": ["© DBS Bank Ltd. All rights reserved."],
    "claimed_brand": "DBS",
    "brand_matches_domain": "no",
    "page_type": "login",
    "requested_actions": ["enter_password"],
    "urgency_or_threat": True,
    "too_good_to_be_true": False,
    "addresses_ai_analyzer": False,
    "verdict": "malicious",
    "confidence": "high",
    "form_posts_cross_site": True,
    "downloads_attempted": False,
    "official_domain_found": "dbs.com.sg",
}


def msg(content: list[dict], stop: str) -> BetaMessage:
    return BetaMessage.model_validate({
        "id": "msg_fake", "type": "message", "role": "assistant", "model": MODEL, "content": content,
        "stop_reason": stop, "stop_sequence": None, "usage": {"input_tokens": 10, "output_tokens": 5},
    })


def thinking() -> dict:
    return {"type": "thinking", "thinking": "", "signature": "sig"}


def web_search(query: str, sid: str = "srvtoolu_1", with_result: bool = True) -> list[dict]:
    blocks = [{"type": "server_tool_use", "id": sid, "name": "web_search", "input": {"query": query}}]
    if with_result:
        blocks.append({"type": "web_search_tool_result", "tool_use_id": sid, "content": [
            {"type": "web_search_result", "url": "https://www.dbs.com.sg/personal/default.page", "title": "DBS Bank",
             "encrypted_content": "enc", "page_age": None},
            {"type": "web_search_result", "url": "https://en.wikipedia.org/wiki/DBS_Bank", "title": "DBS Bank - Wikipedia",
             "encrypted_content": "enc2", "page_age": None},
        ]})
    return blocks


def tool_use(name: str, inp: dict, tid: str) -> dict:
    return {"type": "tool_use", "id": tid, "name": name, "input": inp}


def submit(features: dict | None = None, tid: str = "toolu_submit") -> dict:
    return tool_use("submit_report", features or VALID_FEATURES, tid)


class FakeMessages:
    def __init__(self, script, delay: float = 0.0):
        self.script = list(script)
        self.calls: list[dict] = []
        self.delay = delay

    async def create(self, **kwargs):
        self.calls.append(copy.deepcopy({k: v for k, v in kwargs.items() if k != "messages"}) |
                          {"messages": [dict(m) for m in kwargs["messages"]]})
        if self.delay:
            await asyncio.sleep(self.delay)
        if not self.script:
            raise AssertionError("fake Anthropic script exhausted")
        item = self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class FakeClient:
    def __init__(self, script, delay: float = 0.0):
        self.messages_api = FakeMessages(script, delay)
        self.beta = SimpleNamespace(messages=self.messages_api)

    @property
    def calls(self):
        return self.messages_api.calls
