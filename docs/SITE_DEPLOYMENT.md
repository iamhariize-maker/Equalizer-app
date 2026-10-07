# Svan website on GitHub Pages

The website is a buildless static site in `docs/`. New assets live in `docs/site-assets/`; app sources and existing documentation remain unchanged.

## Publish the prepared site

Open repository **Settings → Pages**. Under **Build and deployment**, choose **Deploy from a branch**, then select **ccr-f859b567-dgrdoj** and **/docs**, and Save. GitHub performs the Pages build and HTTPS deployment. This requires repository administration access; the connected coding tools cannot change Pages settings.

Expected URL after successful deployment:
https://iamhariize-maker.github.io/Equalizer-app/

Do not describe that URL as live until the Pages deployment succeeds and the homepage responds. The default branch does not need to change. The existing branch is used as required by AGENTS.md; no PR or app merge is necessary.

## Preview locally

From the repository root, run `python3 -m http.server 4173 --directory docs`, then open http://localhost:4173/.

## Change the site

- `docs/index.html`: main product story, release URL, screenshots, feature scope and FAQs.
- `docs/site.css`: responsive layout, local fonts, app colour tokens, motion/reduced-motion rules.
- `docs/site.js`: mobile navigation, keyboard-accessible tabs, feel selector, gallery and dialog.
- `docs/getting-started.html`: beta installation and engine guidance.
- `docs/privacy-notes.html`: beta privacy overview, with unresolved store-policy status explicit.

Version copy and download links must be updated together. Download buttons point to the owner-signed APK hosted under `docs/downloads/` (see below). Font files are self-hosted, with SIL OFL notices in `site-assets/fonts/`. The brand mark is an SVG port of the app's original Poppins-based launcher vector; its existing OFL attribution remains applicable. Original screenshot files were copied unchanged from release CI 37434953170, artifact e2e-results-api34, payload source fbc2ff6. They are emulator/demo captures, not commercial-player or physical-device proof or evidence of the new controls.

## Custom domain later

Add the domain under Pages settings and configure the registrar's DNS using GitHub's instructions. Update canonical/OG image URLs and `sitemap.xml` when the domain is live. The custom 404 homepage link currently targets `/Equalizer-app/` on the GitHub Pages host.

No backend, paid subscription or analytics service is required for this site. The website shows the app and links to its GitHub release; it does not process audio or operate Svan in the browser.

## Owner-authorized audio-quality publication — 7 October 2026

The owner asked for the owner-signed 0.5.6 beta 2 APK to be made available from the website,
after GitHub release uploads failed from both Codex and Claude sessions (`gh release upload`
needs GraphQL, blocked in Claude Code sessions; `uploads.github.com` rejected non-JSON bodies via the
session proxy; earlier 401 from Codex). Draft release `405749853` therefore still has no assets
and remains unpublished; no tag exists.

Instead the exact verified file is hosted on the Pages source `ccr-f859b567-dgrdoj:/docs`:

- `docs/downloads/Svan-0.5.6-owner-signed-281e4d8.apk` — 5495023 bytes, SHA-256
  `84ddca6e6a05344cf3a316945ef86c425461bbdcb74e61cc858ba7269591959e`, unmodified from the handoff bundle.
- `docs/downloads/SHA256SUMS`.

Verified before publishing: bundle checksums and payload comparison; `verify_release.sh` against
`docs/release-cert.sha256` (owner certificate `9cb9daca3b49fbdd17683d45dfb069fa9c6d05e3733934795f92546fef696b0f`,
package `app.svan`, 0.5.6 / code 13, permission allowlist, 16 KB alignment); compiled-manifest
permission check; CI runs 37615943549 and 37615950311 (all nine jobs green on tested source
`281e4d8`); all PR #6 checks green on docs commit `dfd9bc2`. The hosted `release-verify.yml`
workflow has NOT run, because no release asset exists. The site says so rather than claiming it.

The same change adds a “Feature tour” section (`#tour`): the existing, unchanged emulator screenshots
(twelve screens) each carry numbered callout markers drawn as page overlays (the image files are not
modified) with matching explanations, plus an “Under the hood” grid for features that have no screen.
Additive CSS only in `site.css`. No new screenshots exist for the beta 2 controls and none were
fabricated. Explanations are drawn from the repository's own docs and keep their stated limits; measured
figures are synthetic-test results. Phone qualification and live decision-audit limits remain explicit.
Only claim the site is updated after the Pages deployment succeeds and the served APK hash matches.
