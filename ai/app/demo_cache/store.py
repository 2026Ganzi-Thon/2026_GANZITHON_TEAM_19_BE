"""시연 캐시 저장소 (TRD 16-8).

app/demo_cache/<함수>/<입력 해시>.json. 입력 해시가 정확히 같을 때만 쓴다.
기동할 때 한 번 메모리로 읽어 두므로 요청 중에는 파일을 읽지 않는다.
"""

import hashlib
import json
import re
from pathlib import Path
from typing import Any

from pydantic import BaseModel

CACHE_ROOT = Path(__file__).resolve().parent
_HASH = re.compile(r"^[0-9a-f]{64}$")
_FN = re.compile(r"^[a-z_]+$")


def input_hash(payload: BaseModel | bytes) -> str:
    if isinstance(payload, bytes):
        return hashlib.sha256(payload).hexdigest()
    canonical = json.dumps(
        payload.model_dump(mode="json"), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class DemoCache:
    def __init__(self, entries: dict[tuple[str, str], dict[str, Any]], *, enabled: bool) -> None:
        self._entries = entries
        self.enabled = enabled

    @classmethod
    def load(cls, *, enabled: bool, root: Path = CACHE_ROOT) -> "DemoCache":
        entries: dict[tuple[str, str], dict[str, Any]] = {}
        if enabled:
            for path in root.glob("*/*.json"):
                if _FN.fullmatch(path.parent.name) and _HASH.fullmatch(path.stem):
                    entries[(path.parent.name, path.stem)] = json.loads(
                        path.read_text(encoding="utf-8")
                    )
        return cls(entries, enabled=enabled)

    def get(self, fn: str, key: str) -> dict[str, Any] | None:
        return self._entries.get((fn, key)) if self.enabled else None

    def __len__(self) -> int:
        return len(self._entries)


def save_entry(fn: str, key: str, response: BaseModel, root: Path = CACHE_ROOT) -> Path:
    if not _FN.fullmatch(fn) or not _HASH.fullmatch(key):
        raise ValueError("invalid cache key")
    path = root / fn / f"{key}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(response.model_dump(mode="json"), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return path
