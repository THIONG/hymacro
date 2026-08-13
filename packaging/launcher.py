"""Punto de entrada para PyInstaller.

No se puede congelar `src/hymacro/__main__.py` directamente: PyInstaller lo
ejecutaria como script suelto y los imports relativos del paquete fallarian.
"""

from hymacro.__main__ import main

if __name__ == "__main__":
    raise SystemExit(main())
