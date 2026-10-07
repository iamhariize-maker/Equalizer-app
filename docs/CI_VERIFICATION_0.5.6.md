# Svan 0.5.6: green CI, owner signing pending

Release source: `64b5ae05181bab2d52535525e5d613cad2fbb78e` on PR #6.
Both the [push run 37573374711](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37573374711)
and [PR run 37573378002](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37573378002)
passed all nine jobs on 7 October 2026. Later documentation changes do not alter the APK source.
PR #6 is not merged and the default branch is unchanged.

## Evidence reviewed

The source run's API 33/34 result archives were downloaded and checked against GitHub's
artifact SHA-256 digests. Result files contain these counts, with zero failures on each API:

| Suite | PASS per API |
| --- | ---: |
| Routing and measured audio | 41 |
| Detection | 13 |
| EQ workspace | 9 |
| Precision controls | 8 |
| Quality | 4 |
| Continuity/detail JNI | 4 |
| Production-mode public UI/native checks | 4 |
| First-run onboarding | 8 |
| Detection fallbacks | 3 |
| Source filtering | 3 |
| Live setup/status | 6 |

Both package dumps confirm `android.permission.DUMP: granted=true` after the real
keep-enhanced button. After the shell server is stopped and Shizuku uninstalled,
hidden-player processing measures -6.3 dB (expected -6.3 ±1 dB). Both final live-status
screenshots visibly show routed Audiophile playback and -12.0/-24.0 dBFS capture peaks.
Representative boot, splash, EQ, new-knob and blind-listening screenshots were also reviewed.

CI's core job passes 105 normal and 105 ASan/UBSan tests plus two TSan concurrency tests.
Android builds/lint/unit tests and nine Python assertions pass. All five compatibility
jobs pass on APIs 29, 30, 33, 35 and 36. Production compiled-capability and four-library
16 KB ELF/ZIP checks pass for APK/AAB; verifier rejection checks remain intact.

Synthetic production native checks measure selective reduction -1.5 dB, correction-fit
RMS about 0.0304 dB and matched-listening difference below 0.1 dB without boosting either
comparison. These do not establish listener preference, end-to-end Bluetooth delay,
OEM behavior or commercial-player compatibility.

## Exact production fixture retained for signing

- Artifact: `Svan-production-test-build`, ID `11461863363`, source push run `37573374711`.
- Artifact ZIP SHA-256: `51695e6a1bd129b28090f2696503c9a490f75baf245b009970768478d433e945`.
- APK: `Svan-production-test.apk`, 5,352,903 bytes, package `app.svan`, version `0.5.6`/13.
- CI APK SHA-256: `aef85684525b055c28427c11a1c2f983d0fd5e6fad3182e20755e498a2cd7ab0`.
- ZIP entries: 73. Uncompressed payload digest:
  `e826c5e1be8eb598c337f2e77dfbb87cdcbdbe2d358993cc54582a7da6dd03cb`.
- Digest algorithm: SHA-256 of UTF-8 compact JSON mapping sorted ZIP entry names to their
  uncompressed SHA-256 values, with sorted keys and comma/colon separators.

This fixture uses a disposable CI signer and is **not a public release asset**. It must
be privately re-signed with the original owner key, then pass the owner release verifier
and an identical-payload comparison. Do not rebuild its payload or publish its CI signer.

## Publication blocker

Drive is connected, but project-name, keystore/signing and recent-archive metadata searches
did not locate the original private signing backup. No private key was downloaded or used,
no replacement generated, and no 0.5.6 release created. The owner must supply the original
backup's Drive link or exact location. Its certificate must match
`9cb9daca3b49fbdd17683d45dfb069fa9c6d05e3733934795f92546fef696b0f` before signing.
The final owner-signed APK hash/payload record and published-release verification are pending.

Owner phone evidence remains positive and separate: current-preview Spotify/playback and
BHIM/GPay after Shizuku removal, with Developer options still enabled. Final-beta updates,
grant retention, Bluetooth, LG V60 and other payment-app/phone combinations need phone tests.
See [investigation](RELEASE_INVESTIGATION_0.5.6.md) and [release readiness](RELEASE_READINESS.md).
