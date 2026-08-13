# -*- mode: python ; coding: utf-8 -*-
"""Receta de PyInstaller para el ejecutable de Windows.

Se usa --onedir (COLLECT) en vez de --onefile a proposito: el modo onefile se
auto-extrae en %TEMP% al arrancar, y esa heuristica es justo la que dispara los
falsos positivos de Windows Defender en un programa que ademas engancha el
teclado. Onedir arranca mas rapido y molesta menos al antivirus.
"""

from pathlib import Path

ROOT = Path(SPECPATH).parent  # noqa: F821 - SPECPATH lo inyecta PyInstaller
SRC = ROOT / "src"

a = Analysis(  # noqa: F821
    [str(ROOT / "packaging" / "launcher.py")],
    pathex=[str(SRC)],
    binaries=[],
    datas=[
        # Plantilla usada para regenerar config.json si el usuario lo borra.
        (str(SRC / "hymacro" / "data" / "config.default.json"), "hymacro/data"),
    ],
    hiddenimports=["keyboard"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        # Nada de esto se usa; fuera del bundle baja el tamano y la superficie de AV.
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
    upx=False,  # UPX dispara aun mas antivirus de los que ya se disparan
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
