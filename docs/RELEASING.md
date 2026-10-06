# Owner runbook: Svan 0.5.5 GitHub beta

Package `app.svan`, versionName `0.5.5`, versionCode `12`. Initial verification preparation changed
only scripts, workflows and documentation. The onboarding production payload at fbc2ff6 is now
privately signed with the recovered original owner key; see the beta notes for its exact hash.
Committing or pushing alone does not publish a GitHub release. Svan's original code is **All rights reserved**, per the owner's
decision; third-party notices remain applicable. See [LICENSE](../LICENSE).

## 1. Build and test before signing privately

Follow [AGENTS.md](../AGENTS.md) for core tests and Android build/lint/unit tests. GitHub CI builds
preview and production-mode fixtures; its disposable test signer is not the owner's release identity.
Wait for core/sanitizers, Android, compatibility and both API 33/34 emulator jobs; read logs and
inspect screenshots. A cancelled job is unverified, not a pass.

`android/scripts/production_release.sh` is an **emulator UI verification script**, not a builder.
CI builds its input with Gradle, then exercises production mode with:

```sh
cd android
bash scripts/production_release.sh emulator-5554 /path/to/production-test.apk /path/to/results
```

It installs the APK on the test emulator, removes prior test-app data, and checks rejected automation
commands, settings migration, engine checks and blind-listening UI. Do not run it on a personal phone.
The existing production Gradle path is documented in [RELEASE_SIGNING.md](RELEASE_SIGNING.md).
The owner signs privately outside CI using that existing process; this preparation adds no signing
step or private-key input. Never distribute the public preview or disposable CI-signed fixture as
an owner release. If using a tested CI payload, privately sign and check payload equivalence as
described in RELEASE_SIGNING.md; do not assume an arbitrary rebuild is the exact tested payload.

For this beta, use `Svan-0.5.5-owner-signed-fbc2ff6.apk`, privately re-signed from the exact
production fixture of CI 37434953170. All 73 ZIP entry contents match the tested payload;
its public record is [v0.5.5-beta-payload.json](releases/v0.5.5-beta-payload.json). No new key was
generated. The original a603146 owner APK is historical and lacks the new onboarding.

App version metadata remains 0.5.5/code 12. Real-phone in-place update/grant retention must be
validated. A future Play update requires an owner-authorized increasing versionCode and
corresponding verifier/notes updates; signing this beta does not change app version metadata.

## 2. Verify the owner APK

Linux requirements: Bash, JDK, Android build-tools 36 (`apksigner`, `aapt2`, `zipalign`), GNU binutils
`readelf`, `unzip`, GNU coreutils and standard text tools. No Python or private signing inputs are
required by the release verifier. Set `ANDROID_HOME` to your SDK, or `SVAN_BUILD_TOOLS_DIR` to its
build-tools directory, then from the repository root:

```sh
bash android/scripts/verify_release.sh /path/to/Svan-0.5.5-owner-signed-fbc2ff6.apk
```

Use the default [public certificate](release-cert.sha256) for an owner release. The optional second
argument exists for CI's public test-certificate fixture; never override it to make an unrecognized
release signer pass. Permission additions **and removals** fail against
[the compiled permission allowlist](release-permissions.txt). Native checks cover ELF load segments
and uncompressed APK ZIP offsets at 16 KB, matching the existing Python gate's requirements.

This specific beta file must print SHA-256
`4272e44c7c033b05789bd6beff51e7b5422e7da375a360e486e8fc310af5702b`.
Do not publish it under that filename/hash if any bytes differ. The workflow checks signer, package,
version, permission set and alignment; its generated checksum reports the actual downloaded bytes,
not a claim of reproducible-build provenance.

## 3. Prepare a GitHub prerelease

1. Ensure the release workflow is available on the repository's **default branch**, as required for
   release/workflow_dispatch events. At preparation time the default branch is the older
   `ccr-208702a3-2mju42`; this work stays on `ccr-f859b567-dgrdoj`. The owner must deliberately make
   the prepared branch the default or arrange an authorized integration before expecting automation.
   No branch-default change, merge or PR is performed here. Until that integration, automatic
   release verification will not run: use the complete local verifier, generate `SHA256SUMS`
   from the final signed APK, and attach it manually. Do not claim workflow verification.
   Download the published assets and repeat local verification before sharing the link.
2. In [Releases](https://github.com/iamhariize-maker/Equalizer-app/releases), draft a new release,
   for example tag `v0.5.5-beta.1`, targeting the prepared commit that contains this verifier/workflow.
   Do not tag `a603146` alone: it does not contain the new scripts. A GitHub tag is not a change to
   the APK's versionName/versionCode.
3. Title it **Svan 0.5.5 — public beta**, select **pre-release**, and use
   [the beta notes](releases/v0.5.5-beta.md). Check their hash/certificate against the file and
   release-cert.sha256. Confirm known limits and migration instructions remain visible.
4. Attach **one APK only**: the owner-signed file. Do not attach private signing ZIPs, keystores,
   recovery credentials, passwords, tokens or disposable CI builds. GitHub Releases are public;
   sharing the URL with a few friends does not restrict access.
5. Publish manually. The release-verify workflow downloads the APK with the default read-only
   contents token, verifies it, generates `SHA256SUMS`, then uploads that public checksum in a
   separate `contents: write` job. Read-only permission cannot upload a release asset. There are
   no custom secrets, signing operations or sensitive comments.
6. Confirm the workflow succeeds and the release now contains `SHA256SUMS`. Download both files
   into one directory and run `sha256sum -c SHA256SUMS`. Share the release URL, not a CI fixture.

An edited release reruns verification. To retry, use Actions → **Verify published beta APK** →
Run workflow with the existing published tag. The uploader checks the APK asset ID/size/digest
has not changed since verification. More than one APK, wrong identity/permissions/version, bad
alignment or a changed asset fails the job and does not upload a new checksum. An old checksum
can remain after a failed edit: remove stale assets/checksums during rollback.

## 4. Roll back a bad beta

Remove the faulty APK and its `SHA256SUMS` asset, mark the release as a prerelease if needed,
and update the notes to say downloads are withdrawn. Do not ask testers to bypass Play Protect.
Publish a corrected beta under a new tag and accurately named file. A later app change needs an
owner-approved higher versionCode plus explicit verifier/notes updates; do not silently replace
the original file while retaining its old hash. Marking a public release as prerelease alone
does not withdraw its assets or undo installations.

## 5. Owner actions before Play

- Retain encrypted signing-key backups and recovery credentials in two secure locations outside
  Git/CI. A certificate or APK cannot recover a lost private key. Never attach the private backup.
- Choose signing [Option A or B](RELEASE_SIGNING.md#owner-decision-required-play-signing-identity).
  No choice is made by this preparation. Test actual channel updates before promising them.
- Review the [Play drafts](play/LISTING.md), finish policy/contact, Data safety and FGS demos,
  and perform real-phone [validation](PHONE_VALIDATION.md). None is submitted by these documents.
- Enable Pages manually if desired: repository Settings → Pages → Deploy from a branch → select
  the prepared branch and `/docs` folder. The expected page is
  `https://iamhariize-maker.github.io/Equalizer-app/privacy.html`; confirm deployment and public
  access before using it. Complete the contact/retention TODOs first. The current app still needs
  a visible policy entry before a Play submission; app code is unchanged in this task.
