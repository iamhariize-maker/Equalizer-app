#!/usr/bin/env bash
# Integration cases against a real signed production-mode CI APK (public cert only).
set -euo pipefail
export LC_ALL=C
[[ $# -eq 1 ]] || { echo 'usage: test_verify_release.sh production-mode-APK' >&2; exit 1; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
apk=$(realpath -- "$1")
bt=${SVAN_BUILD_TOOLS_DIR:?set SVAN_BUILD_TOOLS_DIR to the SDK build-tools directory}
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
"$bt/apksigner" verify --print-certs "$apk" > "$tmp/signature.txt"
awk '/^Signer #[0-9]+ certificate SHA-256 digest: / {print $NF}' "$tmp/signature.txt" > "$tmp/test-cert.sha256"
bash "$root/android/scripts/verify_release.sh" "$apk" "$tmp/test-cert.sha256"
echo 'PASS verifier accepts the signed production-mode fixture (not proof of owner signing)'

# A valid but different public certificate digest must fail for the right reason.
printf '%064d\n' 0 > "$tmp/wrong-cert.sha256"
if bash "$root/android/scripts/verify_release.sh" "$apk" "$tmp/wrong-cert.sha256" > "$tmp/wrong.log" 2>&1; then
    echo 'FAIL verifier accepted the wrong fingerprint' >&2; exit 1
fi
grep -q 'signer certificate fingerprint mismatch' "$tmp/wrong.log"
echo 'PASS verifier rejects a wrong fingerprint'

# In an isolated copy, narrow the reviewed allowlist. The real, signed APK's
# INTERNET declaration is now an additional permission: no tool mocking,
# signing, or app-manifest change is needed to exercise the rejection.
mkdir -p "$tmp/repo/android/scripts" "$tmp/repo/docs"
cp "$root/android/scripts/verify_release.sh" "$tmp/repo/android/scripts/"
sed '/^android.permission.INTERNET$/d' "$root/docs/release-permissions.txt" > "$tmp/repo/docs/release-permissions.txt"
if bash "$tmp/repo/android/scripts/verify_release.sh" "$apk" "$tmp/test-cert.sha256" > "$tmp/extra.log" 2>&1; then
    echo 'FAIL verifier accepted an additional permission' >&2; exit 1
fi
grep -q '^Unexpected permissions:' "$tmp/extra.log"
grep -qx 'android.permission.INTERNET' "$tmp/extra.log"
grep -q 'declared permission list differs from the reviewed allowlist' "$tmp/extra.log"
echo 'PASS verifier rejects an additional declared permission'
