#!/usr/bin/env python3
"""Generate the typed event registry and its payload types from contract.json.

The registry is generated because a hand-maintained one drifts: 152 base events
is more than anyone re-checks by hand, and a key that quietly disappears takes
its listeners with it.

Two rules keep the generated file safe to regenerate:

  * A key that already exists keeps its property name and its payload type.
    Renaming `BackendRateChange` to `BackendRatechange` because a mechanical
    rule said so would break every call site for no gain.
  * A payload the contract states as an inline object literal gets a generated
    data class. Named types map to hand-authored ones through NAMED_TYPES.

Run from the module root: python scripts/generate-event-registry.py
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

MODULE = pathlib.Path(__file__).resolve().parent.parent
CONTRACT = MODULE.parent.parent / 'tools' / 'player-contract' / 'contract' / 'contract.json'
EVENTS_DIR = MODULE / 'src' / 'commonMain' / 'kotlin' / 'tv' / 'nomercy' / 'player' / 'core' / 'events'
REGISTRY = EVENTS_DIR / 'CoreEvents.kt'
GENERATED_PAYLOADS = EVENTS_DIR / 'GeneratedEventPayloads.kt'

HEADER = """// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events
"""

# Web named types to the Kotlin type that already carries the same information.
NAMED_TYPES = {
    'void': 'Unit',
    'ActionOptions': 'ActionOptions',
    'TimeState': 'TimeUpdate',
    'PlayerErrorEvent': 'PlayerErrorEvent',
    'CueEventPayload': 'CueEvent',
    'PlaybackMetrics': 'PlaybackMetrics',
    'SubtitleCueChange': 'SubtitleCueChange',
    'SubtitleStyle': 'SubtitleStyle',
    'I': 'PlaylistItem',
    'I[]': 'List<PlaylistItem>',
    'PlayerPhase': 'tv.nomercy.player.core.player.PlayerPhase',
    'RepeatState': 'tv.nomercy.player.core.player.RepeatState',
    'ShuffleState': 'tv.nomercy.player.core.player.ShuffleState',
    'ActionSource': 'String',
    'CastTarget': 'CastTarget',
    'PreventedReason': 'String',
}

# Field-level TypeScript to Kotlin. `number` becomes Double everywhere: the
# contract's type text cannot say which numbers are counts and which are
# seconds, and guessing per field name would be wrong somewhere quiet.
FIELD_TYPES = {
    'boolean': 'Boolean',
    'number': 'Double',
    'string': 'String',
    'unknown': 'Any?',
    'any': 'Any?',
    'I': 'PlaylistItem',
    'I[]': 'List<PlaylistItem>',
    'string[]': 'List<String>',
    'number[]': 'List<Double>',
}

IMPORTS = [
    'import tv.nomercy.player.core.media.PlaylistItem',
    'import tv.nomercy.player.core.player.ActionOptions',
]


def pascal(name: str) -> str:
    parts = re.split(r'[:\-_]', name)
    out = []
    for part in parts:
        if not part:
            continue
        out.append(part[0].upper() + part[1:])
    return ''.join(out)


def read_existing() -> dict[str, tuple[str, str]]:
    """Event name -> (property name, payload type) already in the registry."""
    source = REGISTRY.read_text(encoding='utf-8')
    rows = re.findall(
        r'public val (\w+):\s*EventKey<(.+?)>\s*=\s*EventKey\("([^"]+)"\)',
        source,
    )
    return {name: (prop, ktype) for prop, ktype, name in rows}


def parse_fields(literal: str) -> list[tuple[str, str, bool]]:
    """`{ a: number; b?: string }` -> [(name, ts-type, optional)]."""
    body = literal.strip()[1:-1]
    fields: list[tuple[str, str, bool]] = []
    depth = 0
    current = ''
    for char in body:
        if char in '{<([':
            depth += 1
        elif char in '}>)]':
            depth -= 1
        if char in ';,' and depth == 0:
            fields.append(current)
            current = ''
        else:
            current += char
    fields.append(current)

    parsed: list[tuple[str, str, bool]] = []
    for raw in fields:
        text = ' '.join(raw.split())
        if not text or ':' not in text:
            continue
        name, _, type_text = text.partition(':')
        optional = name.strip().endswith('?')
        parsed.append((name.strip().rstrip('?'), type_text.strip(), optional))
    return parsed


def kotlin_field_type(ts_type: str) -> str:
    text = ts_type.strip().rstrip(';')
    if text in FIELD_TYPES:
        return FIELD_TYPES[text]
    if text in NAMED_TYPES:
        return NAMED_TYPES[text]
    if text.startswith('ReadonlyArray<') and text.endswith('>'):
        return f'List<{kotlin_field_type(text[len("ReadonlyArray<"):-1])}>'
    if text.endswith('[]'):
        return f'List<{kotlin_field_type(text[:-2])}>'
    if '|' in text:
        options = [o.strip() for o in text.split('|')]
        if all(o.startswith("'") for o in options):
            return 'String'
        if 'null' in options:
            rest = [o for o in options if o != 'null']
            if len(rest) == 1:
                return kotlin_field_type(rest[0]) + '?'
        # A genuine union the contract cannot narrow. String is what every
        # caller reads it as, and the alternative is a sealed hierarchy per
        # event that nobody would use.
        return 'String'
    return 'Any?'


def generated_class(event: str, literal: str) -> tuple[str, str]:
    """Returns (class name, Kotlin source)."""
    name = pascal(event) + 'Payload'
    fields = parse_fields(literal)
    if not fields:
        return name, f'public class {name}\n'

    lines = [f'public data class {name}(']
    for field, ts_type, optional in fields:
        ktype = kotlin_field_type(ts_type)
        if optional and not ktype.endswith('?'):
            ktype += '?'
        default = ' = null' if ktype.endswith('?') else ''
        lines.append(f'    val {field}: {ktype}{default},')
    lines.append(')')
    return name, '\n'.join(lines) + '\n'


def payload_type(event: str, text: str, generated: dict[str, str]) -> str:
    stripped = text.strip()
    if stripped.startswith('BeforeEvent<') and stripped.endswith('>'):
        inner = stripped[len('BeforeEvent<'):-1].strip()
        return f'BeforeEvent<{payload_type(event, inner, generated)}>'
    if stripped.startswith('{'):
        name, source = generated_class(event, stripped)
        generated[name] = source
        return name
    if stripped in NAMED_TYPES:
        return NAMED_TYPES[stripped]
    return 'Any?'


def main() -> int:
    contract = json.loads(CONTRACT.read_text(encoding='utf-8'))
    base = [e for e in contract['events'] if e.get('map') == 'base']
    existing = read_existing()

    generated: dict[str, str] = {}
    entries: list[tuple[str, str, str]] = []
    for event in sorted(base, key=lambda e: e['name']):
        name = event['name']
        if name in existing:
            prop, ktype = existing[name]
            entries.append((prop, ktype, name))
            continue
        ktype = payload_type(name, event.get('payload') or 'void', generated)
        entries.append((pascal(name), ktype, name))

    props = [prop for prop, _, _ in entries]
    duplicates = {p for p in props if props.count(p) > 1}
    if duplicates:
        print(f'property name collision: {sorted(duplicates)}', file=sys.stderr)
        return 1

    payload_source = [
        HEADER,
        '',
        '\n'.join(IMPORTS),
        '',
        '// Generated by scripts/generate-event-registry.py from the pinned',
        '// contract. One data class per event whose payload the contract states as',
        '// an inline object literal; named payload types are hand-authored and',
        '// mapped through the script\'s NAMED_TYPES.',
        '//',
        '// Every `number` becomes a Double. The contract\'s type text cannot say',
        '// which numbers are counts and which are seconds, and guessing from the',
        '// field name would be wrong somewhere quiet.',
        '',
    ]
    for name in sorted(generated):
        payload_source.append(generated[name])
    GENERATED_PAYLOADS.write_text('\n'.join(payload_source), encoding='utf-8', newline='\n')

    registry = [
        HEADER,
        '',
        '\n'.join(IMPORTS),
        '',
        '// The typed key registry — Kotlin\'s answer to the web BaseEventMap. Every',
        '// name string is the web key verbatim, because that string is the shared',
        '// identity across the web trio, this library, the docs and the wire. The',
        '// payload type rides on the key, so on(CoreEvents.Time) hands the listener',
        '// a TimeUpdate with no cast and no event-map generic.',
        '//',
        '// Generated by scripts/generate-event-registry.py. A hand-maintained',
        '// registry of 152 keys drifts, and a key that quietly disappears takes its',
        '// listeners with it. Keys that already existed keep their property names',
        '// and payload types, so regenerating never breaks a call site.',
        '//',
        '// Only v2 names appear. The v1 aliases (current, finished, qualityLevels)',
        '// are never registered — they are a compatibility layer the web trio owns.',
        '@Suppress("TooManyFunctions", "LargeClass")',
        'public object CoreEvents {',
    ]
    for prop, ktype, name in entries:
        registry.append(f'    public val {prop}: EventKey<{ktype}> = EventKey("{name}")')
    registry.append('')
    registry.append('    // Every key, for the conformance gate that checks this registry against')
    registry.append('    // the contract it was generated from.')
    registry.append('    public val all: List<EventKey<*>> = listOf(')
    for prop, _, _ in entries:
        registry.append(f'        {prop},')
    registry.append('    )')
    registry.append('}')
    REGISTRY.write_text('\n'.join(registry) + '\n', encoding='utf-8', newline='\n')

    print(f'{len(entries)} keys ({len(existing)} preserved, {len(entries) - len(existing)} new)')
    print(f'{len(generated)} generated payload classes')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
