# CI image provenance

All PNGs are unchanged captures from `e2e-results-api34`, run
[37414766431](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37414766431),
source commit `49f33488efb7d3a432dad426d91fde99821f8825`. Native size: 320 × 640.
Synthetic players and emulator settings do not establish phone compatibility.
Debugging off/on/unknown files are explicitly labelled **UI test fixtures**.

The source column is relative to that downloaded artifact. Hashes identify these PNG bytes,
not APKs or certificates. Later UI fixes are identified in the verification record.

| Image | Artifact source | PNG SHA-256 |
| --- | --- | --- |
| [first-run.png](first-run.png) | `onboarding/first-run.png` | `107bd6595732f988c131296cdbc509e9c7a033a000f692193b2ea3842c5cec74` |
| [player-unreachable.png](player-unreachable.png) | `onboarding/status-unreachable.png` | `c572c640c7e9460a921febe799aead984dad5279773ecb9d0642b9fbe761c169` |
| [system-effects.png](system-effects.png) | `onboarding/status-system-effects.png` | `43d5577d97edccdace7f64c388d724e7af073ac782be1782f2c6287f63fa2991` |
| [wizard-install-live.png](wizard-install-live.png) | `detection/wizard-install.png` | `64d1760903b9c2d02301218834cab186ba593ee7c8f4f483b827ef90dce474be` |
| [wizard-authorize-live.png](wizard-authorize-live.png) | `detection/wizard-authorization.png` | `2a3ed2979a8d3a363422840d79a5ed65c6aa4acc5dc224ed484f136bf3d3d2c1` |
| [wizard-finish-live.png](wizard-finish-live.png) | `detection/wizard-finish.png` | `ab33cad097d38e73af32f13fab9837e59f3fc484be277864f2d2f7aa71a6e69f` |
| [debugging-off-fixture.png](debugging-off-fixture.png) | `screens/8-setup-finish-off-detail.png` | `fb4a88f7c927a23913e455bdc66ac85207660f2c23c25b65fbc699a17b989e2a` |
| [debugging-on-fixture.png](debugging-on-fixture.png) | `screens/8-setup-finish-on-detail.png` | `3e61d8367872019845a3f4027c8a5f947922b9899362b13c0b5d82024475d8c3` |
| [debugging-unknown-fixture.png](debugging-unknown-fixture.png) | `screens/8-setup-finish-unknown-detail.png` | `4c535a988045c835bbd6bdd03d5b2aa502cad04ef1e8c7b525c2baa6c4bbf045` |
