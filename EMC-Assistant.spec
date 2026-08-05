# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['emc_assistant\\menu.py'],
    pathex=[],
    binaries=[],
    datas=[('emc_assistant', 'emc_assistant')],
    hiddenimports=['emc_assistant.emc_assistant', 'emc_assistant.menu'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='EMC-Assistant',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
