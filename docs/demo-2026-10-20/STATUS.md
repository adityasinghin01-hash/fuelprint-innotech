# FuelPrint connected-demo checkpoint — 20 October 2026

## Final backend closeout — 20 October2026

Independent SPEC/QUALITY review is approved. The persistent local synthetic demo signer is selected and verified, and the owner-authorized AGREEMENT2.4 updates are complete. Final clean package: **1423 tests,0 failures/errors/skips**. Real configured-signer checks and13 packaged CLI cases passed. The refreshed qualification02Windows bundle is ready. Physical Windows and the owner-authorized one SMS test remain pending. All frontend work remains paused. The owner has authorized publication of this verified checkpoint to GitHub `main`. The exact published commit is reported after the remote push is verified. See [backend result](BACKEND-INTEGRATION-RESULTS.md) and [SMS status](NOTIFICATION-STATUS-AND-PHONE-CHECK.md). The entries below are historical checkpoints.

## Latest local backend verification — 20 October 2026

Lane B READY is integrated in candidate A with two additional review fixes and the AI isolation tests. The fresh full backend package passed **1,419 tests, zero failures or skips**. Real HTTP fuel/aerosol/signing/ZIP/tamper, LOG_ONLY and standalone Java verifier checks passed. The Windows bundle is prepared. Independent final review, real signer selection, protected contract text and physical Windows verification remain open. All frontend work is paused and existing app files are preserved. No new commit or push has been made. See [backend result](BACKEND-INTEGRATION-RESULTS.md). The entries below describe earlier checkpoints.

This is an in-progress source checkpoint published at the owner's explicit request. It includes the previously accepted uncommitted backend and the current Lane A candidate. It is not final whole-system acceptance.

Local update after published commit `c236f2e` (not yet pushed): Lane B's D1–D7 protocols were approved with amendments and incorporated by B. A has added the corresponding shared media/signature types and thin Cases forms, independent signature/trust display and ZIP link. Login now shares state with the workbench, and replay disables its controls. Current workspace checks passed: 127 tests, 2 conditional fake-server e2e skips, all type checks and production builds. Browser checks with explicitly mocked protocol data found no page errors or horizontal overflow at 200% zoom; they are not real backend evidence. A's final integration/Windows runner is prepared in the shared evidence directory. Lane B is still implementing its backend; its READY delivery and final integrated qualification remain pending. Further 3D frontend work is paused by Aditya.

Implemented in this checkpoint:
- Server-side rules-based ordering of existing physics survivors, after planning. Candidate facts and learned metadata remain unchanged; no trained model is claimed.
- Minimal Field app with public world geometry, server-tick drone/inlet positions, matching run/epoch checks, readings, pause/resume, approved autopilot controls and explicit human capture review.
- Adapted Operations contribution from ADI238IND/fuelprint-innotech at 473ce064a41fff3b84f6540417655b8141d28292: navigation, investigations, missions, board, scenario controls, conceptual workflow explorer and styles. Null action targets are handled, the existing independent-survey flow is exposed, and daily metrics remain unavailable.
- Thin Cases screens over the existing intake/specimen/custody/lab/human-finding/seal/verify/tamper-copy commands. Raw laboratory teaching inputs are explicitly labelled; blank inputs remain unknown.

Pending:
- Lane B's READY versioned canonicalization/signing/ZIP/offline CLI/aerosol delta. Exact design interfaces are now approved; selection of any actual trusted signer remains a separate technical setup step.
- Real-mode same-run browser rehearsal, final integrated full checks and independent SPEC/QUALITY review.
- Physical second-laptop, phone and Windows checks; external signer trust acceptance.

Verification at publication is recorded in verification.json. Earlier accepted evidence of 1265 Java tests applies to the inherited backend only, not this changed candidate's final qualification. Conditional fake-server e2e tests are skipped in ordinary app runs and require the dedicated fake-server test command. Native graphics and physical-device results must not be inferred from component tests.

No scientific cutoffs, scenario seeds or rejection policies were changed for this demo. Original V4 remains 57/60 raw-compatible versus 8/60 final exact support. SC1/SC3 may remain inconclusive. Learned training, normal-air inference, gameplay, full phone redesign and physical aerosol flight simulation remain deferred. SMS stays LOG_ONLY. All five human-only commands retain their role gates.

## Local setup

Requires Java 17 and Node 22 or later. From the repository:

```sh
cd apps
npm ci
npm run build
cd ../server
./mvnw -q -DskipTests package
cd ..
java -jar server/target/fuelprint-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=real --server.address=127.0.0.1 --fp.data-dir=data/runtime/demo-checkpoint-20261020
```

Open http://localhost:8080/operations/, /field/ and /cases/. Stop with Ctrl+C. Example station PINs are for local teaching only; use the existing stall setup for actual device access. Never commit local configs, keys, databases or downloaded/training data.

The active coordination/evidence directory remains outside the repository. The owner separately authorized this checkpoint commit and push to main; publication does not approve missing schemas, trust anchors or human decisions. The original science2 worktree and both isolated candidates remain preserved.
