# Detection regression inputs

The files prefixed `aosp-shaped-` are synthetic fixtures, derived from the android14-release
layouts already documented in AudioFlingerDumpTest. They are **not reports from
Spotify, Amazon Music, YouTube Music, or the TECNO** and do not prove compatibility.
Package names label scenarios. They are independent of the phone fixture below.

The audio fixture covers wrapped records, numeric fields, capture opt-outs on a
sessionless sibling, and stale playback history. The audio-server fixture covers
mixer/Bluetooth, direct/USB, owned effect chains, and multiple tracks. Tests mutate
identifiers and truncation deterministically to reproduce malformed-report failures.

Add sanitized phone excerpts here when available, with device/API, player/output,
provenance and the observed failure. Remove personal data and track metadata.

`tecno-lh7n-2026-10-05-no-dump.txt` is a sanitized, selected excerpt from
the owner's report pasted in this session (5 October 2026). It proves missing
DUMP permission, zero routes and an empty capture allowlist; its cached YouTube
Music verdict does not identify the currently playing app. No raw audio-service
or AudioFlinger tables were available. It cannot establish a parser or output-path
fault, permission-revocation cause, or physical-player compatibility.
