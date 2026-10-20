# FuelPrint connected-demo checkpoint — 20 October 2026

This is an in-progress source checkpoint published at the owner's explicit request. It includes the previously accepted uncommitted backend and the current Lane A candidate. It is not final whole-system acceptance.

Implemented in this checkpoint:
- Server-side rules-based ordering of existing physics survivors, after planning. Candidate facts and learned metadata remain unchanged; no trained model is claimed.
- Minimal Field app with public world geometry, server-tick drone/inlet positions, matching run/epoch checks, readings, pause/resume, approved autopilot controls and explicit human capture review.
- Adapted Operations contribution from ADI238IND/fuelprint-innotech at 473ce064a41fff3b84f6540417655b8141d28292: navigation, investigations, missions, board, scenario controls, conceptual workflow explorer and styles. Null action targets are handled, the existing independent-survey flow is exposed, and daily metrics remain unavailable.
- Thin Cases screens over the existing intake/specimen/custody/lab/human-finding/seal/verify/tamper-copy commands. Raw laboratory teaching inputs are explicitly labelled; blank inputs remain unknown.

Pending:
- Lane B's reviewed versioned canonicalization/signing/ZIP/offline CLI/aerosol delta and owner decisions on its exact interfaces and trust anchor. No Lane B readiness manifest was available at this checkpoint.
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
