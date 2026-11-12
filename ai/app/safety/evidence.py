"""근거 인용 검증 (TRD 16-2-1). 원문에 없는 문장을 인용으로 지어내지 못하게 한다.

LLM이 돌려준 인용을 그대로 믿지 않고, 원문에서 같은 구절을 찾아 원문 쪽 글자를 쓴다.
따옴표와 띄어쓰기 차이만 허용한다.
"""

import re

MIN_QUOTE_CHARS = 4
MAX_QUOTE_CHARS = 500
_QUOTE_CHARS = "\"'“”‘’「」『』 \t\r\n"


def canonical_quote(quote: str) -> str:
    """따옴표와 띄어쓰기 차이를 없앤 비교용 형태."""
    return " ".join(quote.strip(_QUOTE_CHARS).split())


def locate_quote(quote: str, sources: list[str]) -> tuple[int, str] | None:
    """(source 위치, 원문에서 잘라 낸 글자 그대로의 인용). 없으면 None."""
    words = quote.strip(_QUOTE_CHARS).split()
    needle_length = sum(len(word) for word in words)
    if not MIN_QUOTE_CHARS <= needle_length <= MAX_QUOTE_CHARS:
        return None
    pattern = re.compile(r"\s+".join(re.escape(word) for word in words))
    for index, source in enumerate(sources):
        match = pattern.search(source)
        if match is not None:
            return index, match.group(0)
    return None


def verbatim_quotes(quotes: list[str], sources: list[str], limit: int) -> list[str]:
    """원문에서 찾은 인용만, 원문 글자 그대로, 중복 없이 limit개까지."""
    found: list[str] = []
    for quote in quotes:
        located = locate_quote(quote, sources)
        if located is not None and located[1] not in found:
            found.append(located[1])
    return found[:limit]
