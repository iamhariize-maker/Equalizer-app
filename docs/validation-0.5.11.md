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
a scroll position below that text. Emulator results remain pending and will be
recorded before delivery.

Unverified on the owner's phones: commercial-player track changes, OEM background
survival, USB/Bluetooth route coverage, vendor FFT and Equalizer equivalence,
sample/true peaks, latency, CPU/battery and listening preference. No sound-quality
or BS.1770 conformance claim is made. See [implementation and trial steps](INTEGRATED_LAB_0.5.11.md).
