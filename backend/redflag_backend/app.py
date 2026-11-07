"""RedFlag Cloud API.

GET  /healthz      -> status + persistent count of real Anthropic API requests
POST /v1/analyze   -> text/event-stream: `event: trace` per step, then `event: report`
                      (RiskReport JSON, schema-validated) or `event: error`.
Run: uvicorn redflag_backend.app:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import asyncio
import json
from typing import Any, Callable, Literal

import httpx
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from . import pipeline
from .config import get_settings
from .store import Store

KEEPALIVE_S = 5.0


class LiteObservation(BaseModel):
    claimed_brand: str | None = Field(default=None, max_length=200)
    has_credential_form: bool = False
    title: str | None = Field(default=None, max_length=500)
    lite_score: int | None = Field(default=None, ge=0, le=100)


class Statement(BaseModel):
    model_config = ConfigDict(extra="forbid")
    question_id: Literal["U1", "U2", "U3", "U4"]
    chip_ids: list[str] = Field(default_factory=list, max_length=8)
    text: str = Field(default="", max_length=1200)


class PastedMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(max_length=1200)


class DeviceJudgment(BaseModel):
    model_config = ConfigDict(extra="forbid")
    scenario: str | None = Field(default=None, max_length=40)
    consistency: str | None = Field(default=None, max_length=40)
    sender_risk: str | None = Field(default=None, max_length=40)
    guided: bool = False


class UserContext(BaseModel):
    """What the user handed over (manual_url_check.md §5). extra=forbid: e.g. raw notification chats are rejected."""
    model_config = ConfigDict(extra="forbid")
    source_category: str = Field(default="unknown", max_length=40)
    statements: list[Statement] = Field(default_factory=list, max_length=8)
    pasted_messages: list[PastedMessage] = Field(default_factory=list, max_length=2)
    device_judgment: DeviceJudgment | None = None


class AnalyzeRequest(BaseModel):
    url: str = Field(min_length=1, max_length=4096)
    lite_observation: LiteObservation | None = None
    user_context: UserContext | None = None
    trigger: Literal["auto", "user"] = "user"
    locale: Literal["en", "ko"] = "en"


def default_client_factory() -> Callable[[], Any] | None:
    s = get_settings()
    if not s.anthropic_api_key:
        return None

    def make():
        import anthropic

        # max_retries=0: one SDK call == one real HTTP request (budget accounting is exact).
        return anthropic.AsyncAnthropic(api_key=s.anthropic_api_key, max_retries=0)

    return make


def create_app(client_factory: Callable[[], Any] | None | Literal["default"] = "default",
               store: Store | None = None) -> FastAPI:
    app = FastAPI(title="RedFlag Cloud", version="0.1.0")
    settings = get_settings()
    app.state.store = store or Store(settings.cache_path, settings.cache_ttl_s)
    app.state.client_factory = default_client_factory() if client_factory == "default" else client_factory

    @app.get("/healthz")
    async def healthz():
        s = get_settings()
        st: Store = app.state.store
        sandbox_ok = False
        try:
            async with httpx.AsyncClient(timeout=1.5) as c:
                sandbox_ok = (await c.get(s.sandbox_url.rstrip("/") + "/healthz")).status_code == 200
        except Exception:
            pass
        return {
            "ok": True,
            "model": s.model,
            "effort": s.effort,
            "demo_mode": s.demo_mode,
            "safe_browsing_mode": (("live (Google Safe Browsing v5)" if s.safe_browsing_api_key else "stub (no key)")
                                   if s.safe_browsing_mode == "auto" else s.safe_browsing_mode),
            "anthropic_key_configured": app.state.client_factory is not None,
            "sandbox_reachable": sandbox_ok,
            "anthropic_requests_total": st.api_requests_total(),
            "anthropic_requests_today": st.api_requests_today(),
            "daily_opus_cap": s.daily_opus_cap,
            "limits": {"max_rounds": s.max_rounds, "wall_clock_s": s.wall_clock_s, "cache_ttl_s": s.cache_ttl_s},
        }

    @app.post("/v1/analyze")
    async def analyze(req: AnalyzeRequest):
        queue: asyncio.Queue = asyncio.Queue()
        tracer = pipeline.Tracer(sink=lambda ev: queue.put_nowait(("trace", ev)))
        lite = req.lite_observation.model_dump() if req.lite_observation else None
        user_ctx = req.user_context.model_dump() if req.user_context else None  # memory only, never stored

        async def run():
            try:
                rep = await pipeline.analyze(url=req.url, lite_observation=lite, user_context=user_ctx, settings=get_settings(),
                                             store=app.state.store, client_factory=app.state.client_factory,
                                             emit=tracer)
                queue.put_nowait(("report", rep))
            except pipeline.BadRequest as e:
                queue.put_nowait(("error", {"code": "bad_request", "message": str(e)}))
            except Exception as e:  # report is never sent half-built or unvalidated
                queue.put_nowait(("error", {"code": "internal", "message": f"{type(e).__name__}: {str(e)[:200]}"}))
            finally:
                queue.put_nowait(None)

        task = asyncio.create_task(run())

        async def stream():
            # Padding comment defeats proxy buffering heuristics; then one frame per event.
            yield ": redflag sse\n\n"
            while True:
                try:
                    item = await asyncio.wait_for(queue.get(), timeout=KEEPALIVE_S)
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
                    continue
                if item is None:
                    break
                name, data = item
                yield f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
            await task

        return StreamingResponse(stream(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no",
                                          "Connection": "keep-alive"})

    return app


app = create_app()
