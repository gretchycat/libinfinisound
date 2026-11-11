#!/usr/bin/env python3
"""Check the declared system-native inputs before packaging the Termux tracker bridge."""
from pathlib import Path
import json,subprocess
root=Path(__file__).resolve().parents[1]
for name,expected in json.loads((root/'termux-native-dependencies.json').read_text()).items():
    actual=subprocess.check_output(['dpkg-query','-W','-f','${Version}',name],text=True).strip()
    if actual!=expected:raise SystemExit(f'{name}: expected {expected}, installed {actual}; update the native dependency manifest explicitly')
