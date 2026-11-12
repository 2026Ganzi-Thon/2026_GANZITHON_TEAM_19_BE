"""규칙 엔진과 정규식 마스킹이 함께 쓰는 글자 정규화.

백엔드 Java 엔진도 같은 순서로 같은 표를 적용해야 한다 (risk_lexicon.yaml,
pii_patterns.yaml 머리말). 유니코드 버전에 따라 달라지지 않도록 표를 직접 둔다.

 1. NFC (자모로 풀린 한글을 음절로 합친다. ㅋㅋ·ㅠㅠ 같은 호환 자모는 그대로 둔다)
 2. 전각 ASCII U+FF01~U+FF5E → 반각 U+0021~U+007E
 3. 공백류 → 보통 공백 U+0020
 4. 제로폭 문자 삭제
"""

import unicodedata

FULLWIDTH_START = 0xFF01
FULLWIDTH_END = 0xFF5E
FULLWIDTH_OFFSET = 0xFEE0
SPACE_LIKE = (0x00A0, 0x1680, *range(0x2000, 0x200B), 0x202F, 0x205F, 0x3000)
ZERO_WIDTH = (0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF)

_TABLE: dict[int, int | None] = {
    **{code: code - FULLWIDTH_OFFSET for code in range(FULLWIDTH_START, FULLWIDTH_END + 1)},
    **dict.fromkeys(SPACE_LIKE, 0x20),
    **dict.fromkeys(ZERO_WIDTH),
}


def normalize(text: str) -> str:
    return unicodedata.normalize("NFC", text).translate(_TABLE)
