# Moving from a preview to the owner-signed beta

Previews use a shared public development key. The owner-signed beta has a different
certificate, so Android cannot update an earlier preview in place. Uninstalling deletes Svan's
local data and permissions; preserve settings first.

The 0.5.6 beta must retain the original owner key used by the owner-signed 0.5.5 APKs.
If an owner build is already installed, export settings as a precaution and try an in-place
update first; do not uninstall just because this guide describes preview migration. Real-phone
update/grant retention is still unverified. 0.5.6 increases the versionCode from 12 to 13.
Check the actual release asset and signer against its release notes before installing.

1. While the preview is still installed, open **Presets → Export settings**. If the installed
   preview lacks this feature, update it with a compatible latest preview signed by the same
   preview key before exporting. Keep Play Protect enabled; if it blocks that update, stop and
   report the exact message. Do not uninstall before saving what you need.
2. Save the JSON outside Svan and confirm it exists. It includes manual EQ, presets, Svaresa/audio
   preferences and active calibration curves. It does not include recordings, listening votes,
   Android permission grants, signing keys or credentials. Retain a separate copy if useful.
3. Uninstall the preview, then download and verify the owner beta APK against the release notes
   and `SHA256SUMS`. Install with Play Protect enabled. If it warns/blocks, stop and report the
   exact warning; never disable protection or treat a warning as expected beta behavior.
4. Open **Presets → Restore settings** and select the saved JSON. The app validates it before
   applying settings. Confirm the chosen EQ, headphone curve, presets and engine choices.
5. Re-grant Android's audio permission when needed; approve a fresh capture session when starting
   the Audiophile engine. Re-enable notifications/optional overlay if desired and revisit the
   phone's background/battery settings.
6. If using enhanced music detection, repeat the user-authorized Shizuku setup. Shell-report mode
   needs Shizuku running. To remove Shizuku, explicitly tap **Keep enhanced detection without
   Shizuku** and confirm that the grant succeeds first. Shizuku can then be uninstalled;
   turning Developer options off is optional for Svan's retained grant. Export/restore cannot
   preserve DUMP or other Android grants across uninstall.

Start with **Hi-Fi → System effects only**, check an actively playing app, then test other engines
and routes separately. Follow [phone validation](PHONE_VALIDATION.md) and report unverified device
behavior. Future updates with the same signing identity and a valid increasing versionCode should
avoid this migration, but actual device/channel update behavior must be tested. Play signing
Option A can cause another identity change; see [the signing decision](RELEASE_SIGNING.md).
