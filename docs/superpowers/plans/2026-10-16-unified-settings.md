# Unified Settings Integration Plan

> **For agentic workers:** Implement this plan inline, preserving the supplied staged feature and latest main. User authorized integration and prior main delivery.

**Goal:** Combine local backend connection, text model and speech settings without dropping any existing feature.

**Architecture:** Keep backend address in localStorage and credentials in sessionStorage. Both Axios and interview fetch clients read the selected backend on every request. One settings page contains independent backend, model and speech controls.

**Tech Stack:** Vue 3, Node test runner, Spring Boot, Maven, Chrome acceptance runner.

## Constraints
- Keep 12+1 role-specific interviews and reports intact.
- Speech key goes only to speech routes; model key goes only to text model routes. Backend health probe carries no keys.
- Retain main CORS origins and local-backend documentation. Speech check proves TTS connectivity, not ASR authorization.
- Existing staged files backed up under /private/tmp/settings-speech-before-integration before edits.

## Task 1 — Shared routing and merged settings
- [x] Add failing regression tests: after client creation change backend address twice; both fetch (including binary TTS) and Axios use the current address; credentials stay separated; restoring default falls back to constructor address.
- [x] Restore getLocalApiBaseUrl/setLocalApiBaseUrl alongside speech settings, and main Axios base resolution. Update test harness imports. Keep speech headers out of ordinary Axios routes.
- [x] Merge backend address validation, save/check/reset into Settings.vue above model and speech cards. Keep backend preference separate from credential clearing. Add stable data-testid controls and test successful/failed validation and all three sections.
- [x] Retain main API defaults/CORS/docs; document speech request header and check endpoint. Run npm test and npm run build.

## Task 2 — Integration validation and delivery
- [x] Extend Chrome checks for saving backend address, model and speech test calls, credential isolation, persistence across navigation and all existing interview/report scenarios.
- [x] Run mvn package, Node tests, frontend production build and Chrome acceptance. Inspect staged changes against main, commit and push only after all checks pass.

## Validation and review

- RED: unified-settings.test.js failed for absent backend preference API and backend Settings state. GREEN: covered dynamic JSON/binary TTS routing, Axios changes, key isolation, backend validation, independent credential clearing, storage failure and stale result clearing.
- Final npm test: 118 passed, 0 failed. npm run build: passed.
- Maven package (Java17): 199 passed, 0 failures/errors; package passed. Existing Surefire shutdown delay warning remains unrelated to functionality.
- Chrome acceptance: 59 checks passed, including backend save/probe, separate model/speech tests, reload persistence, 390px layout and both technical/non-technical 13-question/report flows. Browser API/vendor calls are mocked; no live provider credentials used.
- Independent reviewer approved; minor stale success message issue fixed and re-reviewed with no outstanding findings.
- Remote main re-fetched before delivery; base bec694b. All pre-existing staged settings/speech work is intentionally included in this authorized integration.
