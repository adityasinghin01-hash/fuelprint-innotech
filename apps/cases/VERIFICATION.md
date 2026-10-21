# Cases frontend delivery

This contribution builds on integrated main `13495d4` (agreement v2.4). It adds the Cases cinematic
introduction and complete frontend routes while retaining the integrated owner's workbench components.
Backend, shared contracts, Field and shared source are unchanged. The workspace lockfile includes the
Cases dependencies; source models, posters and asset-generation scripts are included. Blender working
files, dependency folders, secrets, runtime databases, test signer keys and local caches are excluded.

## Implemented

- Continuous stylized estate → monitoring → human approval → drone screening → return → Cases story.
  Native scroll is reversible; keyboard chapters, skip entry, pause, static fallback and reduced motion
  keep Cases accessible. The illustration never sends operational commands or asserts scientific proof.
- Casebook, CA01–CA06, custody/timeline, laboratory, human findings, seven verification axes and guide.
- v2.4 aerosol collection/custody/lab integration, separate material identity and exact result citations.
- Closed-case history viewer; separate signature validity and external trust; original/copy ZIP links.
- Replay, stale, interrupted and mismatched-run mutation guards. Scientific calculations remain on the server.

## Validation and remaining work

Cases production build and **50 frontend tests passed**, one optional live integration test skipped. A clean-install `npm ci --dry-run --ignore-scripts` check also passed.
The source package retains the upstream 19 workbench tests alongside the new Cases tests. The lazy
Three.js chunk remains approximately 661 kB minified (171 kB gzip).

Direct desktop/mobile captures below document the completed visual pass. The latest v2.4 additions
have component coverage and a production build; they have not completed a populated real-server browser
rehearsal. No full system acceptance is claimed.

Real Windows backend checks exposed failures in signing tests that concatenate backslash paths into
JSON and a separate-process archive test that could not read its pack. Backend files were not changed.
The opt-in `e2e/integrated-review.mjs` HTTP rehearsal stopped at `NEEDS_NEW_APPROVAL`; it did not complete
the capture-to-export chain. It resets its dedicated test run and must never target the stall server.

Before merge: finish real same-run SC1/SC2/SC3 browser workflows; check signed/unsigned ZIPs with the
packaged verifier; show tampered copy failure and original success; resolve/report Windows backend
failures. Physical phone performance, OS reduced motion, actual 200% zoom and a physical second-laptop
verification demonstration remain open. Scenario outcomes must come from real records, not forced
supported/rejected/inconclusive endings.

## Direct screenshots

These captures use synthetic teaching data and precede the latest v2.4 integration additions.

| View | Desktop | Phone |
| --- | --- | --- |
| Hero | [Image](review/desktop-hero.jpg) | [Image](review/mobile-hero.jpg) |
| Monitoring | [Image](review/desktop-monitoring.jpg) | [Image](review/mobile-monitoring.jpg) |
| Takeoff | [Image](review/desktop-takeoff.jpg) | [Image](review/mobile-takeoff.jpg) |
| Flight | [Image](review/desktop-flight.jpg) | [Image](review/mobile-flight.jpg) |
| Air screening | [Image](review/desktop-sampling.jpg) | [Image](review/mobile-sampling.jpg) |
| Evidence transition | [Image](review/desktop-evidence-transition.jpg) | [Image](review/mobile-evidence-transition.jpg) |
| Casebook | [Image](review/desktop-casebook.jpg) | [Image](review/mobile-casebook.jpg) |
| Guide | [Image](review/desktop-guide.jpg) | [Image](review/mobile-guide.jpg) |
