"""PII 마스킹 (TRD 16-5). 1단계 정규식(항상) + 2단계 개체명(LLM)."""

import re
import time
from dataclasses import dataclass
from typing import TYPE_CHECKING

from app import errors
from app.functions.drafts import EntitiesDraft, EntityDraft
from app.meta import build_meta
from app.resources import PiiPattern, PiiPatterns
from app.schemas import EntityType, MaskedEntity, MaskRequest, MaskResponse
from app.text import normalize

if TYPE_CHECKING:
    from app.services import Services

MASK_TIMEOUT_SECONDS = 20.0
MASK_CHUNK_CHARS = 6000
MASK_MAX_TOKENS = 1500
MIN_ENTITY_CHARS = 2
_NON_DIGITS = re.compile(r"\D")
_TOKEN = re.compile(r"\[(?:연락처|이메일|식별번호|링크|이름|지명|직장|학교)\d+\]")


class Numbering:
    """같은 개체에 문서 안에서 같은 번호를 준다. 원문↔토큰 매핑은 저장하지 않는다."""

    def __init__(self) -> None:
        self._tokens: dict[tuple[EntityType, str], str] = {}
        self._counts: dict[str, int] = {}

    def token_for(self, entity_type: EntityType, key: str) -> str:
        found = self._tokens.get((entity_type, key))
        if found is not None:
            return found
        number = self._counts.get(entity_type, 0) + 1
        self._counts[entity_type] = number
        token = f"[{entity_type}{number}]"
        self._tokens[(entity_type, key)] = token
        return token

    def entities(self) -> list[MaskedEntity]:
        return [MaskedEntity(type=t, token=token) for (t, _), token in self._tokens.items()]


@dataclass(frozen=True)
class _Span:
    start: int
    end: int
    pattern: PiiPattern


@dataclass(frozen=True)
class Replacement:
    """원문의 [start, end)가 가린 글의 [masked_start, masked_end) 토큰으로 바뀌었다."""

    start: int
    end: int
    masked_start: int
    masked_end: int


def to_masked_offset(position: int, replaced: list[Replacement], *, is_end: bool) -> int:
    """원문 위치를 가린 글의 위치로 옮긴다.

    가려진 구간 안의 위치는 토큰의 시작(구간의 끝 위치라면 토큰의 끝)으로 옮긴다.
    """
    shift = 0
    for item in replaced:
        if position < item.start or (is_end and position == item.start):
            break
        inside = position <= item.end if is_end else position < item.end
        if inside:
            return item.masked_end if is_end else item.masked_start
        shift = item.masked_end - item.end
    return position + shift


class PiiMasker:
    def __init__(self, patterns: PiiPatterns) -> None:
        self._compiled = [(p, re.compile(p.pattern, re.ASCII)) for p in patterns.patterns]

    def mask(self, text: str, numbering: Numbering | None = None) -> str:
        """정규화한 글에서 찾아 바꾼다. 결과도 정규화된 글이다."""
        return self.mask_with_offsets(text, numbering)[0]

    def mask_with_offsets(
        self, text: str, numbering: Numbering | None = None
    ) -> tuple[str, list[Replacement]]:
        """가린 글과, 어느 구간이 어느 토큰 자리로 바뀌었는지를 함께 돌려준다.

        위치는 정규화한 글(app.text.normalize) 기준이다.
        """
        numbering = numbering or Numbering()
        text = normalize(text)
        parts: list[str] = []
        replaced: list[Replacement] = []
        cursor = 0
        length = 0
        for span in self._spans(text):
            value = text[span.start : span.end]
            token = numbering.token_for(span.pattern.token, _normalize(value, span.pattern))
            before = text[cursor : span.start]
            parts.extend((before, token))
            length += len(before)
            replaced.append(Replacement(span.start, span.end, length, length + len(token)))
            length += len(token)
            cursor = span.end
        parts.append(text[cursor:])
        return "".join(parts), replaced

    def mask_many(self, texts: list[str], numbering: Numbering | None = None) -> list[str]:
        shared = numbering or Numbering()
        return [self.mask(text, shared) for text in texts]

    def has_pii(self, text: str) -> bool:
        return bool(self._spans(normalize(text)))

    def _spans(self, text: str) -> list[_Span]:
        taken: list[_Span] = []
        for pattern, regex in self._compiled:  # 파일 순서가 우선순위
            for match in regex.finditer(text):
                group = 1 if regex.groups >= 1 and match.group(1) is not None else 0
                start, end = match.span(group)
                if start < end and not any(start < s.end and s.start < end for s in taken):
                    taken.append(_Span(start, end, pattern))
        return sorted(taken, key=lambda s: s.start)


def _normalize(value: str, pattern: PiiPattern) -> str:
    if pattern.normalize == "digits":
        return _NON_DIGITS.sub("", value)
    return value.lower()


def _chunks(text: str, limit: int = MASK_CHUNK_CHARS) -> list[str]:
    chunks: list[str] = []
    current = ""
    for raw_line in text.splitlines(keepends=True):
        line = raw_line
        while len(line) > limit:  # 줄바꿈 없는 긴 줄
            if current:
                chunks.append(current)
                current = ""
            chunks.append(line[:limit])
            line = line[limit:]
        if len(current) + len(line) > limit:
            chunks.append(current)
            current = ""
        current += line
    if current:
        chunks.append(current)
    return chunks


def apply_entities(text: str, entities: list[EntityDraft], numbering: Numbering) -> str:
    """개체명을 긴 것부터 토큰으로 바꾼다. 이미 치환된 토큰 안쪽은 건드리지 않는다."""
    usable = {
        e.text.strip(): e.type
        for e in entities
        if len(e.text.strip()) >= MIN_ENTITY_CHARS and not _TOKEN.search(e.text)
    }
    result = text
    for value in sorted(usable, key=len, reverse=True):
        if value not in result:
            continue
        token = numbering.token_for(usable[value], value)
        pieces = _TOKEN.split(result)
        tokens = _TOKEN.findall(result)
        replaced = [piece.replace(value, token) for piece in pieces]
        result = "".join(
            piece + (tokens[i] if i < len(tokens) else "") for i, piece in enumerate(replaced)
        )
    return result


async def mask_pii(req: MaskRequest, services: "Services") -> MaskResponse:
    started = time.perf_counter()
    prompt = services.prompts["mask_pii"]
    numbering = Numbering()
    masked = services.masker.mask(req.text, numbering)

    entities: list[EntityDraft] = []
    tokens_in = tokens_out = 0
    failures: list[errors.AIError] = []
    chunks = _chunks(masked)
    deadline = started + MASK_TIMEOUT_SECONDS
    for chunk in chunks:
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            failures.append(errors.timeout())
            break
        try:
            result = await services.gateway.complete(
                system=prompt.text,
                user=f"<user_text>\n{chunk}\n</user_text>",
                schema=EntitiesDraft,
                timeout=remaining,
                max_tokens=MASK_MAX_TOKENS,
            )
        except errors.AIError as exc:
            failures.append(exc)
            continue
        entities.extend(result.parsed.entities)
        tokens_in += result.tokens_in
        tokens_out += result.tokens_out

    if failures and len(failures) >= len(chunks):
        raise failures[0]  # 전부 실패하면 백엔드가 정규식 폴백으로 처리한다

    text = apply_entities(masked, entities, numbering)
    # 재검사: 원문 개체나 정규식 대상이 결과에 남아 있으면 다시 가리고 partial로 알린다.
    leftover = any(
        len(e.text.strip()) >= MIN_ENTITY_CHARS and e.text.strip() in text for e in entities
    )
    if services.masker.has_pii(text):
        text = services.masker.mask(text, numbering)
    return MaskResponse(
        text=text,
        entities=numbering.entities(),
        partial=bool(failures) or leftover,
        meta=build_meta(
            services,
            prompt.version,
            started,
            tokens_in=tokens_in,
            tokens_out=tokens_out,
            fallback=bool(failures),
        ),
    )
