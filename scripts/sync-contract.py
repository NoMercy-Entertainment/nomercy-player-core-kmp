#!/usr/bin/env python3
"""Refresh the vendored contract from the generator.

The registry generator and the conformance test both read contract/contract.json
so this repo runs standalone in CI, where the monorepo path does not exist. Only
works inside the monorepo, which is the only place the generator's output lives.
"""

import pathlib
import shutil
import sys

MODULE = pathlib.Path(__file__).resolve().parent.parent
GENERATED = MODULE.parent.parent / 'tools' / 'player-contract' / 'contract' / 'contract.json'
VENDORED = MODULE / 'contract' / 'contract.json'

if not GENERATED.exists():
    print(f'no generated contract at {GENERATED} — run this inside the monorepo', file=sys.stderr)
    raise SystemExit(1)

shutil.copyfile(GENERATED, VENDORED)
print(f'synced {VENDORED}')
