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

Version copy and download links must be updated together. The live site still links `v0.5.5-beta.1`; prepared links use the published `v0.5.6-beta.1` until beta 2 hosting succeeds. Font files are self-hosted, with SIL OFL notices in `site-assets/fonts/`. The brand mark is an SVG port of the app's original Poppins-based launcher vector; its existing OFL attribution remains applicable. Original screenshot files were copied unchanged from release CI 37434953170, artifact e2e-results-api34, payload source fbc2ff6. They are emulator/demo captures, not commercial-player or physical-device proof or evidence of the new controls.

## Custom domain later

Add the domain under Pages settings and configure the registrar's DNS using GitHub's instructions. Update canonical/OG image URLs and `sitemap.xml` when the domain is live. The custom 404 homepage link currently targets `/Equalizer-app/` on the GitHub Pages host.

No backend, paid subscription or analytics service is required for this site. The website shows the app and links to its GitHub release; it does not process audio or operate Svan in the browser.

## Owner-authorized audio-quality publication — 7 October 2026

The owner explicitly requested direct upload of the owner-signed update and concise update notes,
leaving website design and new screenshot evidence for later. This authorizes only the necessary
website-docs publication on the configured Pages source `ccr-f859b567-dgrdoj:/docs`, as an exception
to AGENTS' app-working-branch restriction. Application development remains on the Codex branch;
do not merge that branch into the site branch or change the default branch/Pages configuration.

The prepared website changes are limited to `index.html`, `getting-started.html` and this deployment
record. After successful hosting/verification, switch download copy and links to the separate
owner-signed `v0.5.6-beta.2` prerelease; it retains the tested app version 0.5.6/code13. Prepared links
currently stay on the existing beta 1 because the beta 2 asset upload is blocked by HTTP 401 at
uploads.github.com (draft release `405749853`, no assets). Live Pages has not been changed.
Existing beta assets, CSS, JavaScript, screenshots and privacy
documents stay unchanged. Phone qualification and live decision-audit limits remain explicit.
Only claim the site is updated after the Pages build/deployment succeeds and its HTTPS content
shows the new notes and correct release link. Release verification must pass before site publication.
