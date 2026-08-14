#!/usr/bin/env python3
"""Shortcut so `python main.py` still works. `uv run hymacro` is preferred."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from hymacro.__main__ import main

if __name__ == "__main__":
    raise SystemExit(main())
