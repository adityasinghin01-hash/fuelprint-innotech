# Final Round 1 backend software closeout — 20 October 2026

The three owner-delegated computer tasks are complete: **independent SPEC/QUALITY approval**, **persistent local demo signer selected and verified**, and **protected contract documentation updated to2.4**. The selected Round1 backend software passes the final integrated build and configured-signer checks. Physical Windows verification and the owner's one SMS test remain unperformed. Frontend work remains paused. At qualification closeout no publication or deployment had been performed; the owner has since authorized committing and pushing this verified checkpoint to GitHub `main`.

## Verification

- Final clean package: **1423 tests in113 suites;0 failures/errors/skips**. Includes576 Cases tests,102 AI tests and123 alert tests. Ten reset/completion lifecycle checks and six scenario/automation combinations passed with their declared scientific/synthetic limits.
-166 focused tests passed after meaningful failing regressions. Independent review approved all437 source hashes, four additional correction-file hashes and three contract-document hashes. Final build/rehearsal preserved those exact source and contract hashes.
- R9 fixed fractional/string/boolean/nonpositive/overflowing revision coercion and v1 structural number overflow. R10 now reports non-object records as named integrity failures instead of ClassCastException/internal error1. Measurement decimals and existing CanonicalJson are unchanged. A misleading unsigned explanation for failed signed packs was corrected.
- Prior R7 ZIP central/local metadata consistency and R8 media custody cycle corrections remain in place; R1–R6 are closed. No remaining required review findings.
- Jar SHA-256: `218997a21838d4e43edc61de0c29cb27ff309ac14c56a29467e2b8598595d574` (39,269,268 bytes).
- Preserved original science2 baseline:631 verified files. B's26-file delivery, original snapshots, AGENTS.md and all reference thresholds are unchanged. Thirteen prior UI-qualified app files retain their earlier hashes. No frontend code was changed.

## Contract and signer

The current user explicitly delegated signing-key selection and protected contract-document updates. Only candidate A's AGREEMENT.md and its two new Cases extension documents were changed. AGREEMENT2.4 adopts the approved amended evidence/signing/ZIP/CLI and separate aerosol media/custody/lab interfaces, with review corrections for exact status codes, below-LOQ result use and DECIMAL128 field semantics. No reference scientific threshold, scenario, human-only role or protected original worktree was changed.

Selected signer: **FuelPrint Round1 local synthetic demo signer**, Ed25519. Public fingerprint (SHA-256 of DER SubjectPublicKeyInfo):

`a1e82619b0083fb7013d4678f5c52d0c9bbbf6781f86305140367a875ed3194d`

Private/public key, trust policy and explicit signed-v2 config are stored outside the repository under `/Users/aditya/.config/fuelprint/evidence-signing/round1-20261020/`. Directory mode0700, private-key mode0600. No private key appears in checked logs, HTTP records, export members or the Windows bundle. No private-key backup was created by this work. The normal missing-config default remains unsigned v1; signed demo launches explicitly select the external config. The key is trusted for this synthetic local demo under the owner's delegation, not as proof of personal identity, legal suitability, trusted time or physical measurement truth.

## Real configured-signer check

Run `744c4043-4c30-4365-a79c-493e4a0cc36a`; new case `c046abb1-5634-4eee-b780-975005816a78`, export `590d8f58-4e3a-4937-b4c1-88638648c9de`. The final jar re-verified the exact old fuel pack (hash unchanged), verified the old temporary-key signed pack as VALID but UNTRUSTED under the new trust policy, then created a separate declared synthetic20L aerosol case through ordinary station commands. Wrong role/stale revision were rejected; repeating the media command returned the same receipt. Custody, PASS/USABLE lab and a fixed scripted INCONCLUSIVE finding were saved. The new pack signed and verified VALID/TRUSTED with the selected persistent key. Unauthorized/wrong-run downloads were refused, the tamper copy failed, and the original export record/bytes remained unchanged. No learned model version was created.

The database was cloned from the stopped prior real-mode qualification, preserving real captured screening records. It was not a fabricated flight fixture. On the first attempt I assumed a restored paused run's live world clock was already loaded. It was not, so the server correctly rejected a negative collection interval. The failed script/requests are retained in failed-signer-verification-01/. The normal presenter Resume/Pause commands loaded the saved clock, and the rerun passed without a backend source change. After a restart, resume the run normally before new clock-dependent work. This is a documented launch prerequisite, not an invented simulation time.

All13 verifier cases below ran as real Java processes from offline-clean/, with an empty environment and no server context, config or private key:

| Case | Exit | Result |
| --- | ---: | --- |
| signed-trusted | 0 | PASS |
| signed-untrusted | 8 | FAIL |
| signed-untrusted-allow-unsigned | 8 | FAIL |
| tampered | 5 | FAIL |
| legacy-default | 8 | FAIL |
| legacy-explicit-unsigned | 0 | PASS |
| invalid-record-shape | 5 | FAIL |
| fractional-revision | 6 | FAIL |
| overflow-case-revision | 5 | FAIL |
| invalid-signature | 7 | FAIL |
| stripped-signature-allow-unsigned | 7 | FAIL |
| truncated | 3 | FAIL |
| missing | 2 | USAGE_ERROR |

The prior live same-run flight/capture,20L and negative/below-LOQ teaching tests, LOG_ONLY board notification, and backend WebSocket/reconnect evidence remain archived in qualification01. Field, AI and notification production bytes were unchanged by this final verifier-only correction. This final batch does not claim new real-mode browser, phone or3D acceptance.

## Notification service

The SMS backend is built: opt-in subscriptions, smoke/board triggers, checked wording/template fallback, rate/daily limits, Android local gateway adapter and private contact handling.123 alert tests passed, including15 gateway-adapter tests and five real-adapter-flow tests against a LOCAL MOCK gateway. Those tests and LOG_ONLY records do not prove physical SMS delivery. The optional external wording provider is not configured in the verified demo; approved templates remain functional.

The sender is an Android gateway phone with a working SMS-capable SIM and a backend connection. Current subscriptions accept Indian(+91) mobile numbers. Recipients can be elsewhere on different mobile operators and receive ordinary carrier SMS in Android/iPhone messaging apps without our website, app or Wi-Fi, where SMS coverage/service is available. The implemented adapter treats a gateway2xx as accepted/SENT; actual recipient receipt is a separate physical check, not a delivery/read guarantee. This is not iMessage/WhatsApp/push messaging.

The owner volunteered a number for ONE test and confirmed Android availability, then requested all physical steps at the end of current work. No raw number was stored in these artifacts, no real SMS gateway config was enabled, and no actual SMS was sent. configure-sms-gateway.py collects gateway settings privately on the Mac into an external mode0600 file, keeps LOG_ONLY and dailycap1, and sends nothing. The one deliberate TEST can be sent after that setup, with routine contact-table consent handling and cleanup; no other recipients are authorized. See NOTIFICATION-STATUS-AND-PHONE-CHECK.md.

## Windows handoff and remaining physical steps

Use the refreshed qualification02 bundle; qualification01's bundle is superseded.

- ZIP: `/Users/aditya/dev/fuelprint-backend-execution-2026-10-18/workflow-backend/two-day-demo-20261020/integration/backend-qualification-02/windows-backend-verification.zip` (36,546,344 bytes).
- SHA-256: `7b1b04e4d27de5a81558d1c16295bd1660ce254a6feaadb46aa115c725244641`.
- Contains final verifier.jar, new signed original.zip, separate tampered.zip, public selected-demo fingerprint, PowerShell runner, README and SHA256SUMS.json. No private key, config, database or contacts. ZIP/member hashes were checked on the Mac.
- Copy/extract on Windows, confirm Java17+, run the README's exact PowerShell command and return windows-verifier-results/windows-result.json. It must show trusted original0, original without anchor8, tampered member5. A Mac pass is not Windows proof.
- For the authorized SMS test, install/start the official Android gateway Local Server with SEND_SMS and an active SIM, configure its credentials privately on the Mac, then send only the agreed test and observe actual receipt. Follow the user's requested step-by-step guidance after this computer closeout. No credentials or full phone numbers should be pasted into chat or public artifacts.

Physical checks, three-app frontend completion, connected3D/browser/phone rehearsal and final exhibition readiness are separate from this backend software closeout. Trained ranker, full gameplay, gravimetric pre/post weighing and physical drone aerosol capture remain deferred. Original V4 stays57/60 raw-compatible versus8/60 final exact support; no supported result was forced.

## State and reproducibility

All Java/writer/demo reservations are clear. The verification server is stopped and its data/evidence preserved. server.json records the exact final jar launch command, data path and explicit external signing config. start-server.py intentionally refuses to overwrite prior data/logs; a future restart must use the coordinator and a fresh log/registered process. stop-server.py only stops its exact registered process.

Backend check used `python3 <P>/coordinate.py A java <A>/server <fresh-log> ./mvnw -q clean package`. Final signer check used the same global reservation around verify-final-signer.py. No rerun is needed unless new changes justify it. Detailed evidence: full-build-result.json/full-xml, focused-result.json/focused-xml, independent-final-review.json, source-before/after manifests, contract-after-review-hashes.json, real-rehearsal-result.json, final-signer-records.json, offline-clean/*.stdout.json, final-audit.json and windows-bundle-receipt.json.

Recorded 2026-10-20T13:47:03.334497+00:00; elapsed3.84h from the persistent start; deadline2026-10-22T09:56:51.855805+00:00. No clock reset, Git publication or frontend edit.
