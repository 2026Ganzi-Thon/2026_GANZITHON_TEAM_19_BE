"""내부 계약 ai/openapi.json 생성 (TRD 16-11). 결과를 표준 출력으로 낸다.

make ai-openapi                                        # ai/openapi.json 갱신
python -m app.export_openapi > /tmp/ai-openapi.json    # CI에서 커밋본과 비교
"""

import json
import sys

from app.main import create_app
from app.resources import AI_ROOT

OPENAPI_PATH = AI_ROOT / "openapi.json"


def render() -> str:
    return json.dumps(create_app().openapi(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> None:
    # 운영체제와 관계없이 UTF-8, 줄바꿈 LF로 내보낸다.
    sys.stdout.buffer.write(render().encode("utf-8"))


if __name__ == "__main__":
    main()
