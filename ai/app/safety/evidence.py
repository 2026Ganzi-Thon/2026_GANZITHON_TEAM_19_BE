"""근거 인용 검증 (TRD 16-2-1). 원문에 없는 문장을 인용으로 지어내지 못하게 한다.

LLM이 돌려준 인용을 그대로 믿지 않고, 원문에서 같은 구절을 찾아 원문 쪽 글자를 쓴다.
따옴표와 띄어쓰기 차이만 허용한다.
"""

MIN_QUOTE_CHARS = 4
MAX_QUOTE_CHARS = 500
_QUOTE_CHARS = "\"'“”‘’「」『』 \t\r\n"


def canonical_quote(quote: str) -> str:
    """따옴표와 띄어쓰기 차이를 없앤 비교용 형태."""
    return " ".join(quote.strip(_QUOTE_CHARS).split())


def _collapse(text: str) -> tuple[str, list[int]]:
    """공백을 한 칸으로 줄인 사본과, 사본의 글자마다 원문에서의 위치."""
    chars: list[str] = []
    offsets: list[int] = []
    gap_at: int | None = None
    for position, char in enumerate(text):
        if char.isspace():
            if chars and gap_at is None:
                gap_at = position
            continue
        if gap_at is not None:
            chars.append(" ")
            offsets.append(gap_at)
            gap_at = None
        chars.append(char)
        offsets.append(position)
    return "".join(chars), offsets


class QuoteIndex:
    """원문 여러 개에서 인용을 찾는다.

    공백을 줄인 사본에서 문자열 검색으로 찾고 원문 위치로 되돌린다. 원문 길이에
    비례하는 시간만 쓰므로, 같은 말이 반복되는 긴 대화에서도 느려지지 않는다.
    """

    def __init__(self, sources: list[str]) -> None:
        self._sources = sources
        self._collapsed = [_collapse(source) for source in sources]

    def locate(self, quote: str) -> tuple[int, str] | None:
        """(source 위치, 원문에서 잘라 낸 글자 그대로의 인용). 없으면 None."""
        needle = canonical_quote(quote)
        if not MIN_QUOTE_CHARS <= len(needle.replace(" ", "")) <= MAX_QUOTE_CHARS:
            return None
        for index, (text, offsets) in enumerate(self._collapsed):
            start = text.find(needle)
            if start >= 0:
                last = start + len(needle) - 1
                return index, self._sources[index][offsets[start] : offsets[last] + 1]
        return None

    def verbatim(self, quotes: list[str], limit: int) -> list[str]:
        """원문에서 찾은 인용만, 원문 글자 그대로, 중복 없이 limit개까지."""
        found: list[str] = []
        for quote in quotes:
            located = self.locate(quote)
            if located is not None and located[1] not in found:
                found.append(located[1])
        return found[:limit]


def locate_quote(quote: str, sources: list[str]) -> tuple[int, str] | None:
    return QuoteIndex(sources).locate(quote)
