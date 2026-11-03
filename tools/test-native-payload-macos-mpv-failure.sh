#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$repo/build"
fixture="$(mktemp -d "$repo/build/mac-mpv-failure.XXXXXX")"
mkdir -p "$fixture/brew/opt/mpv/lib" "$fixture/bin"
printf 'fixture\n' > "$fixture/brew/opt/mpv/lib/libmpv.dylib"
touch "$fixture/stamp"

cat > "$fixture/bin/otool" <<'EOF'
#!/usr/bin/env bash
printf '%s:\n' "$2"
EOF
cat > "$fixture/bin/install_name_tool" <<'EOF'
#!/usr/bin/env bash
[ "${FAIL_TOOL:-}" = install_name_tool ] && exit 42
exit 0
EOF
cat > "$fixture/bin/codesign" <<'EOF'
#!/usr/bin/env bash
[ "${FAIL_TOOL:-}" = codesign ] && exit 42
exit 0
EOF
chmod +x "$fixture/bin/"*

for tool in install_name_tool codesign; do
    if FAIL_TOOL="$tool" PATH="$fixture/bin:$PATH" bash "$repo/tools/native-payload-macos-mpv.sh" \
        "$fixture/stamp" test "$fixture/brew" "$fixture/out-$tool" > "$fixture/output-$tool" 2>&1; then
        echo "FAIL macOS payload assembler ignored $tool failure"
        exit 1
    fi
    echo "PASS macOS payload assembler rejected $tool failure"
done
