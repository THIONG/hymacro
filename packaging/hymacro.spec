# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller recipe for the Windows executable.

onedir is used rather than onefile on purpose: onefile extracts itself into
%TEMP% on startup, and that behaviour is exactly what trips Windows Defender for
a program that also hooks the keyboard.
"""

from pathlib import Path

ROOT = Path(SPECPATH).parent  # noqa: F821
SRC = ROOT / "src"

a = Analysis(  # noqa: F821
    [str(ROOT / "packaging" / "launcher.py")],
    pathex=[str(SRC)],
    binaries=[],
    datas=[
        (str(SRC / "hymacro" / "data" / "config.default.json"), "hymacro/data"),
    ],
    hiddenimports=["keyboard"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "tkinter",
        "unittest",
        "pydoc",
        "email",
        "http",
        "xml",
        "pytest",
    ],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)  # noqa: F821

exe = EXE(  # noqa: F821
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="HyMacro",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(ROOT / "icon.ico"),
)

coll = COLLECT(  # noqa: F821
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="HyMacro",
)
