"""PyInstaller entry point.

`src/hymacro/__main__.py` cannot be frozen directly: PyInstaller would run it as
a loose script and the package relative imports would fail.
"""

from hymacro.__main__ import main

if __name__ == "__main__":
    raise SystemExit(main())
