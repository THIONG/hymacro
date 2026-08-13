#!/usr/bin/env python3
"""Atajo para seguir arrancando HyMacro con `python main.py`.

La logica vive en el paquete `src/hymacro`. La forma recomendada de ejecutarlo
es `uv run hymacro`.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from hymacro.__main__ import main

if __name__ == "__main__":
    raise SystemExit(main())
