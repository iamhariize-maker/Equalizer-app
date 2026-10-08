You are releasing Svan 0.5.6 (versionCode 13) as an owner-signed public beta.
Repo: iamhariize-maker/Equalizer-app. Work from PR #6, branch `claude/codex-audio-crackling-amplifier-gkj007`. Read AGENTS.md and docs/HANDOFF.md first. Their release rules apply.

## Gate: do not start until all of these are true
1. CI on the PR head commit is fully green: every job, including emulator-e2e on API 33 and 34, compat on 29/30/33/35/36, and the detection checks (13 PASS).
   - If any job is red, STOP and report which check failed. Do not sign anything.
   - A failed API 36 smoke check has been an infrastructure issue before. Re-run it once; a second failure is real.
2. The APK you sign comes from a green run whose commit has the same source as the PR head. Commits that only touch `docs/` (this prompt, release notes) don't change the APK. If any code, script or workflow file changed after that run, wait for a new green run.

## Key handling rules (non-negotiable)
- The owner's original private signing backup is in the owner's Google Drive. Use the Drive connector to find and download it. If you can't find it, or the Drive connector isn't available, STOP and ask the owner. Do not generate a new key.
- If you need the keystore or key password and you don't have it, ask the owner. Never guess it, and never write it to a file, log, commit, PR, issue, release note or CI secret.
- Download the keystore to a new empty temp directory outside the repo. Never commit it, upload it, or put it in a GitHub workflow or secret. Delete it, and any copies, as soon as signing is finished.
- Never print the key, the passwords or the keystore's contents. Print only the public certificate SHA-256.
- Before signing, confirm the keystore's certificate SHA-256 equals `docs/release-cert.sha256` (`9cb9daca3b49fbdd17683d45dfb069fa9c6d05e3733934795f92546fef696b0f`). If it differs, STOP. That is the wrong key, and signing with it would stop existing 0.5.5 users from updating.

## Steps
1. Download the CI artifact `Svan-production-test-build` from the green run. It is the production R8 build, signed with a disposable CI key, that the e2e tests exercised. Do not rebuild it. The payload must stay the tested one.
2. Re-sign that exact APK with the owner key: `apksigner sign` (v2, with a build-tools version that supports it), then `zipalign` check (16 KB native-library alignment must hold). Signing changes only the signature, not the application payload.
3. Verify with `android/scripts/verify_release.sh <apk>`. It must pass: one signer, certificate = `docs/release-cert.sha256`, package app.svan, versionName 0.5.6, versionCode 13, the compiled permission allowlist (no notification listener, SMS or accessibility), 16 KB alignment.
4. Compare every ZIP entry's contents with the unsigned CI APK, as in `docs/releases/v0.5.5-beta-payload.json`. All entries must match, and only the signature files may differ.
5. Name the file `Svan-0.5.6-owner-signed-<short-commit>.apk`. Compute its SHA-256.
6. Write `docs/releases/v0.5.6-beta.md` and `docs/releases/v0.5.6-beta-payload.json` (same fields as the 0.5.5 record: apk name, sha256, size, certificate sha256, source commit, CI run id, zip entry count, payload digest, native library hashes). Add `SHA256SUMS-v0.5.6-beta.1`. Commit these docs to the PR branch (no secrets, no key).
7. Publish a GitHub **pre-release** `v0.5.6-beta.1` titled "Svan 0.5.6 — public beta", with the signed APK attached. Base the notes on `docs/releases/v0.5.5-beta.md` and state only what was measured:
   - Backing vocals and Binaural effects knobs (side-channel only; mono sum preserved).
   - Crackle fixes: output cushion, capture backlog, restart-volume fix.
   - Whole-phone EQ fallback for players that hide their audio session.
   - Direct Shizuku route for phones like HiOS where Shizuku's helper process fails.
   - One-tap "Keep enhanced detection without Shizuku": user-initiated, grants Svan its own DUMP once, and Shizuku can then be uninstalled. Real-phone result: not yet verified on HiOS or any phone other than the owner's TECNO LH7n, so say so.
   - Notification-listener player recognition was removed because Play Protect flagged it.
   - Keep the existing warnings: never turn off Play Protect; export settings before updating; real-phone, Bluetooth and payment-app behavior is unverified; this is not a malware certification.
   - The owner key is the same as 0.5.5, so an in-place update should work. Say "should" until it is verified on a phone.
   - Do not claim sound-quality improvements you haven't measured. Say what the tests measured (the dB checks) and that listening preference is unverified.
8. Check that the `release-verify` workflow runs on the published release and passes. If it fails, say so and unpublish or mark the release as a draft until it is fixed.
9. Do NOT merge PR #6 and do NOT change the default branch. The owner merges.

## Final report
Give the release URL, the APK SHA-256, the signer certificate SHA-256 (public), the CI run id used, and confirm that the keystore and any passwords were deleted and never left your temp directory. List anything you could not verify.
