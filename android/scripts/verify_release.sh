#!/usr/bin/env bash
# Verify an existing APK; never build, sign, or access a private key.
set -euo pipefail
export LC_ALL=C
fail() { printf 'FAIL release verification: %s\n' "$*" >&2; exit 1; }
trap 'printf "FAIL release verification: command failed at line %s\n" "$LINENO" >&2' ERR
[[ $# -ge 1 && $# -le 2 ]] || fail 'usage: verify_release.sh APK [expected-certificate-file]'
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
apk=$(realpath -- "$1")
[[ -f "$apk" ]] || fail 'APK not found'
expected_file=${2:-"$root/docs/release-cert.sha256"}
[[ -f "$expected_file" ]] || fail 'expected certificate file not found'
expected=$(tr -d '[:space:]:' < "$expected_file" | tr '[:upper:]' '[:lower:]')
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fail 'expected certificate must be one SHA-256 fingerprint'

# An explicit directory supports installed SDKs and isolated CI test fixtures.
bt=${SVAN_BUILD_TOOLS_DIR:-}
if [[ -z "$bt" ]]; then
    sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    [[ -n "$sdk" && -d "$sdk/build-tools" ]] || fail 'set ANDROID_HOME or SVAN_BUILD_TOOLS_DIR'
    bt=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
fi
for tool in apksigner aapt2 zipalign; do
    [[ -x "$bt/$tool" ]] || fail "SDK build-tool unavailable: $tool"
done
for tool in unzip readelf sha256sum awk sort cmp comm realpath; do
    command -v "$tool" >/dev/null || fail "required Unix tool unavailable: $tool"
done
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT

"$bt/apksigner" verify --verbose --print-certs "$apk" > "$tmp/signature.txt" 2>&1 || fail 'apksigner verify rejected the APK'
grep -qx 'Number of signers: 1' "$tmp/signature.txt" || fail 'expected exactly one APK signer'
# SDK 36.1 prints per-scheme "V2 Signer:"; earlier SDKs print "Signer #1".
# All reported schemes must agree on one certificate, never select just the first.
awk '/^(Signer #[0-9]+ |V[0-9.]+ Signer(: | #[0-9]+: ))certificate SHA-256 digest: / {print $NF}' "$tmp/signature.txt" | sort -u > "$tmp/certs.txt"
[[ $(wc -l < "$tmp/certs.txt") -eq 1 ]] || fail 'expected exactly one signer certificate'
actual=$(tr '[:upper:]' '[:lower:]' < "$tmp/certs.txt")
[[ "$actual" =~ ^[0-9a-f]{64}$ ]] || fail 'apksigner did not return a valid certificate fingerprint'
printf 'Signer certificate SHA-256: %s\n' "$actual"
[[ "$actual" == "$expected" ]] || fail 'signer certificate fingerprint mismatch'
printf 'PASS signer certificate\n'

"$bt/aapt2" dump badging "$apk" > "$tmp/badging.txt" || fail 'aapt2 could not inspect the APK'
package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "$tmp/badging.txt")
version_name=$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" "$tmp/badging.txt")
version_code=$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" "$tmp/badging.txt")
[[ "$package" == app.svan ]] || fail 'package must be app.svan'
case "$version_name:$version_code" in
  0.5.5:12|0.5.6:13|0.5.7:14|0.5.8:15|0.5.9:16|0.5.10:17|0.5.11:18) ;;
  *) fail 'unsupported release versionName/versionCode pair' ;;
esac
if grep -Eq 'application-debuggable|testOnly' "$tmp/badging.txt"; then
    fail 'debuggable/testOnly APK is not a beta release'
fi
printf 'PASS package app.svan, versionName %s, versionCode %s\n' "$version_name" "$version_code"

awk -F "'" '/^uses-permission[^:]*:/ {print $2}' "$tmp/badging.txt" | sort -u > "$tmp/actual-permissions.txt"
[[ -s "$tmp/actual-permissions.txt" ]] || fail 'compiled permission list is empty'
if grep -Ev '^[A-Za-z0-9_.]+$' "$tmp/actual-permissions.txt" >/dev/null; then
    fail 'invalid compiled permission name'
fi
[[ -f "$root/docs/release-permissions.txt" ]] || fail 'permission allowlist not found'
sort -u "$root/docs/release-permissions.txt" > "$tmp/expected-permissions.txt"
if ! cmp -s "$tmp/expected-permissions.txt" "$tmp/actual-permissions.txt"; then
    comm -13 "$tmp/expected-permissions.txt" "$tmp/actual-permissions.txt" > "$tmp/extra.txt"
    comm -23 "$tmp/expected-permissions.txt" "$tmp/actual-permissions.txt" > "$tmp/missing.txt"
    if [[ -s "$tmp/extra.txt" ]]; then
        printf 'Unexpected permissions:\n' >&2
        cat "$tmp/extra.txt" >&2
    fi
    if [[ -s "$tmp/missing.txt" ]]; then
        printf 'Missing reviewed permissions:\n' >&2
        cat "$tmp/missing.txt" >&2
    fi
    fail 'declared permission list differs from the reviewed allowlist'
fi
printf 'PASS declared permission allowlist\n'

# Same ELF load/ZIP-offset requirements as check_native_alignment.py, using
# SDK zipalign and GNU readelf rather than a Python dependency.
"$bt/zipalign" -c -P 16 4 "$apk" >/dev/null || fail 'APK ZIP alignment is not 16 KB compliant'
unzip -Z1 "$apk" > "$tmp/entries.txt" || fail 'cannot list APK entries'
grep '\.so$' "$tmp/entries.txt" > "$tmp/libraries.txt" || fail 'APK has no native libraries'
count=0
while IFS= read -r entry; do
    [[ "$entry" =~ ^lib/[A-Za-z0-9_-]+/[A-Za-z0-9_.+-]+\.so$ ]] || fail 'unexpected native library path'
    unzip -p "$apk" "$entry" > "$tmp/library.so" || fail "cannot read native library: $entry"
    readelf -W -l "$tmp/library.so" > "$tmp/elf.txt" 2>/dev/null || fail "invalid native ELF: $entry"
    awk '$1 == "LOAD" {print $2, $3, $NF}' "$tmp/elf.txt" > "$tmp/loads.txt"
    [[ -s "$tmp/loads.txt" ]] || fail "no ELF load segments: $entry"
    while read -r offset address alignment; do
        for value in "$offset" "$address" "$alignment"; do
            [[ "$value" =~ ^0x[0-9a-fA-F]+$ ]] || fail "invalid ELF load header: $entry"
        done
        (( alignment >= 16384 && (alignment & (alignment - 1)) == 0 && (offset - address) % 16384 == 0 )) ||
            fail "native ELF load segment is not 16 KB aligned: $entry"
    done < "$tmp/loads.txt"
    count=$((count + 1))
done < "$tmp/libraries.txt"
printf 'PASS 16 KB ELF and APK ZIP alignment (%s native libraries)\n' "$count"
printf 'APK SHA-256: %s\n' "$(sha256sum -- "$apk" | awk '{print $1}')"
printf 'PASS release verification\n'
