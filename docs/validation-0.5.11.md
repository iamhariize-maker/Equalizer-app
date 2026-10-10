# 0.5.11 preview validation

Local native core: **179 tests, 0 failed checks**. Android JVM: **431 tests,
0 failures**. Existing Python screenshot/control/host-meter helpers: **17 passed**.
The integrated planner's response callback matches the original peak planner's
controls and error to 1e−9; a frozen −6 dB response passes its numerical guard.
A 31.5 Hz, +6 dB, Q8 target survives logarithmic response interpolation within
0.01 dB. This verifies the numerical target, not a vendor's realised response.
Lifecycle regressions cover same-ID reopen, duplicate CLOSE deadlines, different
IDs, reused ownership, bounded pending state and shutdown. These are synthetic
tests, not Apple Music or YouTube Music device qualification.

The final full local debug/release build, lint and unit command passed.
Lint reported **0 errors, 61 warnings**. The final run includes the single-timer
close reaper, capture-start drain and logarithmic target regression.
The six bundled reference model files match their SHA-256 manifest. The standalone
planner/WAV host checks also pass; those are reference checks, not phone output.

The uploaded preview and repository preview key have matching certificate
SHA-256 `fd7955d137a0a85f218c144e26ea4c0c5bc69f0d7b2c82257016212fefa8c72a`.
The candidate retains package `app.svan` and raises versionCode to 18.
APK v2 signature verification, 16 KB ZIP/native-library alignment and the manifest
permission-policy check pass. This preview can update the supplied preview APK.

CI adds seven integrated-Lab/DUMP-free runtime assertions on API 33 and 34, and
Mint Circuit to the existing native appearance/screenshots suite. All prior test
assertions remain. The capture-recovery screenshot search now returns to the top
before searching for the same exact policy text; the earlier branch run retained
a scroll position below that text.

The first [CI run at app commit 88a230e](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/38043767633)
passes the Android/production build gates, native sanitizers and release install
checks on API 29/30/33. Both API 33/34 pass all **14 DUMP-free detection checks**;
API 34 also passes all **14 capture-recovery checks**. API 33 loses its emulator
during capture recovery; API 35/36 lose their emulator during smoke checks. The
new Lab harness incorrectly accepted a preceding player's connection on API 34
and stops at `StopIteration` before the new assertions. Its follow-up waits for
its own healthy route and a fresh status receipt, and captures the Lab pages.
No application source changes in that follow-up. The [focused frozen-APK workflow](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/38045228410)
checks source equality before testing the original built artifact. API 34 passes
all **seven integrated-Lab checks**: basic discovery, same-session reopen churn,
64 unique-bin controls, the existing effect owner, fitted-chain churn, restoring
normal architecture on a curve edit, and real CLOSE expiry. Both API 33 focused
attempts lose the emulator before those assertions (`error: closed`, then TCP
5554 connection refused). API 33 Lab/capture qualification therefore remains open;
its passing install and basic-detection results do not substitute for it.

The first run's native appearance job passes **47 debug and 42 production UI
checks**. Mint Circuit is exercised on all five main screens and the Svaresa
panel; debug checks confirm theme changes preserve saved sound. Cold-launch theme
restoration and 2× text navigation also pass. Reviewed screenshots include the
production Mint Circuit main screens, Svaresa, applied Lab Shape/Engine/Measure,
and large-text Lab/section navigation. Controls remain readable and reachable.

The exact delivered preview comes from first-run artifact **11667241403**, built
from **88a230e874b663566c5391814d299d796b77cb3b**. Its APK SHA-256 is
`48450cfb13b2e2a87db51f2a2ae8e9c83213b403af378e69c634400dd3ecc910`.
The fixture/research follow-up is **f63440e6d9d8f2d0e8c5fea4e98222c1eef04841**;
application, native core, model assets and build configuration are unchanged.
The full end-to-end matrix is still running; this is not an all-green matrix claim.

Unverified on the owner's phones: commercial-player track changes, OEM background
survival, USB/Bluetooth route coverage, vendor FFT and Equalizer equivalence,
sample/true peaks, latency, CPU/battery and listening preference. No sound-quality
or BS.1770 conformance claim is made. See [implementation and trial steps](INTEGRATED_LAB_0.5.11.md).
