from __future__ import annotations

import json
import logging
from dataclasses import dataclass

import httpx

from ..config import settings
from .broadcast_context_service import BroadcastContext, BroadcastDialogue

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class GeminiSelectionResult:
    selected_message_id: str | None
    reason: str
    confidence: float | None = None


@dataclass(slots=True)
class SentimentResult:
    positive_chat_count: int
    neutral_chat_count: int
    negative_chat_count: int


class GeminiFilterService:
    """Calls Gemini HTTP REST API to select the most entertaining chat from a batch."""

    def __init__(self) -> None:
        self.client: httpx.AsyncClient | None = None

    async def startup(self) -> None:
        if not settings.gemini_api_key:
            logger.warning("GEMINI_API_KEY missing — Gemini filter disabled")
            self.client = None
            return
        self.client = httpx.AsyncClient(
            base_url=settings.gemini_api_base_url,
            timeout=httpx.Timeout(
                connect=settings.gemini_connect_timeout_sec,
                read=settings.gemini_timeout_sec,
                write=settings.gemini_connect_timeout_sec,
                pool=settings.gemini_connect_timeout_sec,
            ),
        )
        logger.info("Gemini filter client ready | model=%s", settings.gemini_model)

    async def shutdown(self) -> None:
        if self.client is not None:
            await self.client.aclose()
            self.client = None

    def is_ready(self) -> bool:
        return self.client is not None

    async def select_chat(
        self,
        candidates: list[dict],
        context: BroadcastContext,
    ) -> GeminiSelectionResult | None:
        """Send candidate batch + broadcast context to Gemini. Return selection or None on failure."""
        if self.client is None:
            return None

        prompt = _build_selection_prompt(candidates, context)
        body = {"contents": [{"parts": [{"text": prompt}]}]}
        url = f"/models/{settings.gemini_model}:generateContent"

        try:
            response = await self.client.post(url, params={"key": settings.gemini_api_key}, json=body)
            response.raise_for_status()
        except httpx.TimeoutException:
            logger.error("Gemini filter timeout")
            return None
        except httpx.HTTPStatusError as exc:
            logger.error("Gemini filter HTTP %d", exc.response.status_code)
            return None
        except Exception:
            logger.exception("Gemini filter unexpected error")
            return None

        try:
            payload = response.json()
            text = _extract_gemini_text(payload)
            verdict = json.loads(text)
            selected_id = verdict.get("selectedMessageId")
            return GeminiSelectionResult(
                selected_message_id=str(selected_id) if selected_id is not None else None,
                reason=str(verdict.get("reason", "")),
                confidence=_safe_float(verdict.get("confidence")),
            )
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            logger.warning("Failed to parse Gemini selection response | err=%s raw=%.300s", exc, response.text)
            return None

    async def analyze_sentiment(self, chats: list[str]) -> SentimentResult | None:
        """Analyze overall sentiment of chat messages using Gemini function calling."""
        if self.client is None:
            return None
        if not chats:
            return None

        prompt = _build_sentiment_prompt(chats)
        body = {
            "contents": [{"parts": [{"text": prompt}]}],
            "tools": [{"functionDeclarations": [SENTIMENT_TOOL_DECLARATION]}],
            "toolConfig": {"functionCallingConfig": {"mode": "ANY"}},
        }
        url = f"/models/{settings.gemini_model}:generateContent"

        try:
            response = await self.client.post(url, params={"key": settings.gemini_api_key}, json=body)
            response.raise_for_status()
        except httpx.TimeoutException:
            logger.error("Gemini sentiment analysis timeout")
            return None
        except httpx.HTTPStatusError as exc:
            logger.error("Gemini sentiment analysis HTTP %d", exc.response.status_code)
            return None
        except Exception:
            logger.exception("Gemini sentiment analysis unexpected error")
            return None

        try:
            payload = response.json()
            logger.info("Gemini sentiment response | chats=%d raw=%.500s", len(chats), response.text)
            parts = payload["candidates"][0]["content"]["parts"]
            for part in parts:
                if "functionCall" in part:
                    args = part["functionCall"]["args"]
                    result = SentimentResult(
                        positive_chat_count=int(args["positiveChatCount"]),
                        neutral_chat_count=int(args["neutralChatCount"]),
                        negative_chat_count=int(args["negativeChatCount"]),
                    )
                    logger.info(
                        "Gemini sentiment parsed | positive=%d neutral=%d negative=%d",
                        result.positive_chat_count,
                        result.neutral_chat_count,
                        result.negative_chat_count,
                    )
                    return result
            logger.warning("No functionCall in Gemini sentiment response | raw=%.500s", response.text)
            return None
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            logger.warning("Failed to parse Gemini sentiment response | err=%s raw=%.500s", exc, response.text)
            return None


# ----------------------------------------------------------------
# Prompt builder
# ----------------------------------------------------------------

SYSTEM_INSTRUCTION = """너는 라이브 방송 채팅 선별자다.
목표: 후보 채팅 목록 중에서 현재 방송 맥락에 맞고 AI 캐릭터가 반응하기 가장 재미있는 채팅 1개만 선택하라.

선택 우선순위:
1. AI 캐릭터를 직접 부르거나 지목한 채팅
2. 현재 방송 흐름과 직접 관련된 질문/요청
3. 최근 대화에 자연스럽게 이어지는 채팅
4. 중복/스팸이 아닌 구체적이고 정보량 있는 채팅

선택 제외:
- "ㅋㅋㅋ", "ㅎㅎ", "와", "ㄷㄷ", "???", "!!!" 같은 저정보 반응성 채팅
- 같은 의미의 중복 채팅
- 욕설/도배/복붙성 채팅
- 현재 방송 맥락과 무관한 채팅

반드시 JSON만 출력하라. 설명이나 마크다운을 추가하지 마라.
출력 형식: {"selectedMessageId": "후보채팅의 id 또는 null", "reason": "선택 이유", "confidence": 0.0~1.0}
적합한 채팅이 없으면 selectedMessageId를 null로 하라."""


def _build_selection_prompt(
    candidates: list[dict],
    context: BroadcastContext,
) -> str:
    parts: list[str] = [SYSTEM_INSTRUCTION, ""]

    # Character info
    if context.character_raw:
        character = context.character_raw
        char_lines: list[str] = []
        name = character.get("characterName")
        if name:
            char_lines.append(f"이름: {name}")
        persona = character.get("characterPersonality")
        if persona and isinstance(persona, dict):
            char_lines.append(f"페르소나: {json.dumps(persona, ensure_ascii=False)}")
        trigger = character.get("characterTriggerWords")
        if trigger:
            char_lines.append(f"트리거 단어: {trigger}")
        style = character.get("characterSpeechStyle")
        if style:
            char_lines.append(f"말투: {style}")
        if char_lines:
            parts.append("[캐릭터 정보]")
            parts.extend(char_lines)
            parts.append("")

    # Summary
    if context.summary_text:
        parts.append("[방송 요약]")
        parts.append(context.summary_text)
        parts.append("")

    # Recent dialogues
    if context.recent_dialogues:
        parts.append("[최근 대화]")
        for i, d in enumerate(context.recent_dialogues, 1):
            label = _subject_label(d.subject)
            parts.append(f"{i}. [{label}] {d.content}")
        parts.append("")

    # Candidate chats
    parts.append("[후보 채팅]")
    for c in candidates:
        parts.append(f"- id={c['messageId']} | 닉네임={c['nickname']} | 내용={c['content']}")
    parts.append("")

    return "\n".join(parts)


def _subject_label(subject: str) -> str:
    labels = {
        "STREAMER": "스트리머",
        "VIEWER": "시청자",
        "AI_CHARACTER": "AI캐릭터",
        "DONATION": "후원",
        "SYSTEM_SUMMARY": "요약",
    }
    return labels.get(subject, subject)


# ----------------------------------------------------------------
# Gemini response parsing
# ----------------------------------------------------------------


def _extract_gemini_text(payload: dict) -> str:
    candidates = payload.get("candidates", [])
    if not candidates:
        raise KeyError("No candidates in Gemini response")
    parts = candidates[0].get("content", {}).get("parts", [])
    if not parts:
        raise KeyError("No parts in Gemini response")
    return parts[0].get("text", "")


def _safe_float(value: object) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


# ----------------------------------------------------------------
# Sentiment analysis
# ----------------------------------------------------------------

SENTIMENT_TOOL_DECLARATION = {
    "name": "analyze_chat_sentiment",
    "description": "채팅 메시지 목록의 긍정/중립/부정 채팅 개수를 분석하여 반환한다.",
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "positiveChatCount": {
                "type": "NUMBER",
                "description": "긍정적인 채팅의 개수",
            },
            "neutralChatCount": {
                "type": "NUMBER",
                "description": "중립적인 채팅의 개수",
            },
            "negativeChatCount": {
                "type": "NUMBER",
                "description": "부정적인 채팅의 개수",
            },
        },
        "required": ["positiveChatCount", "neutralChatCount", "negativeChatCount"],
    },
}


def _build_sentiment_prompt(chats: list[str]) -> str:
    parts: list[str] = [
        "다음 채팅 메시지들의 전체적인 분위기를 분석해줘.",
        "analyze_chat_sentiment 툴을 호출하여 positiveChatCount(긍정 채팅 개수), neutralChatCount(중립 채팅 개수), negativeChatCount(부정 채팅 개수)를 반환해줘.",
        "",
        "[채팅 메시지]",
    ]
    for i, chat in enumerate(chats, 1):
        parts.append(f"{i}. {chat}")
    return "\n".join(parts)


gemini_filter_service = GeminiFilterService()