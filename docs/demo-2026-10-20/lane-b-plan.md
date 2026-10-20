# Lane B: Antara remaining Cases backend implementation plan

> **For agentic workers:** Use executing-plans task by task and the master plan's cross-chat protocol. No extra workers automatically. This plan does not claim any of the additions are implemented.

**Goal:** Complete the remaining documented Antara Cases backend: compatible versioned RFC8785 use, Ed25519/key trust, bounded ZIP/standalone Java verification and separate aerosol media/QC/export.

**Architecture:** Keep accepted fp.evidence/1 bytes and verification unchanged. Add an explicitly versioned manifest/signature envelope, with external keys and independently supplied trust anchors. An offline verifier validates archive members without a live server/database. Aerosol is a distinct Cases material workflow; it is not a fuel specimen or proof of new flight aerosol physics.

**Tech stack:** Java17, existing Jackson/RecordStore/CommandBus/BlobStore/JdbcTemplate stack, Ed25519, bounded java.util.zip. Any new canonicalization dependency or schema/API change needs its concrete proposal reviewed before dependent code.

## Baseline, authority and ownership

Read TWO-DAY-DEMO-PLAN-20261020.md, AGENTS.md, contracts/AGREEMENT.md, docs/plan/03-cases-guide.md and E/ANTARA-WORK-INVENTORY-20261020.md. N1–N5 are already implemented with429Cases tests within1265total; don't rebuild them. SC1 may stay INCONCLUSIVE; no forced SUPPORTED finding or successful learning-loop story.

Own server/src/main/java/fuelprint/cases/** and matching tests, optional additive migrationsV300–V399 after confirming no collision. Avoid a new table if append-only records suffice. CLI lives in cases/**; A owns pom/tool-wrapper/download-route/shared-type/Field changes. No apps/cases edits by B. Never modify contracts/**, AGENTS.md, science or real keys.

Wait for A's BASE_READY before writes; use the identical accepted dirty snapshot and the shared writer/Java protocol. Publish progress and protocols to laneB-status.json. Never duplicate A's Maven run or edit source while either chat owns Java.

## B0: Review protocol proposals before dependent implementation

**Create:** E/two-day-demo-20261020/proposals/evidence-v2.md and aerosol-cases.md with exact fields, examples, version negotiation, rejection behavior, config names and API/command changes. A reviews shared integration effects; Aditya decides protected/shared contract changes. Proposals are writable evidence/docs, not contracts edits.

Proposed evidence shape: retain legacy fp.evidence/1 unchanged. New fp.evidence/2 has a manifest whose metadata fields fit the RFC8785/I-JSON number domain; member hashes refer to exact STORED payload bytes, not recanonicalized records. Archive members can preserve project exact-decimal JSON and must not be falsely labelled wholly RFC8785. Signature metadata sits outside signed manifest bytes, avoiding a self-referential signature. If the accepted specification instead requires every new member to be JCS, explicitly reject unrepresentable new values or use an approved typed-decimal representation; never round evidence or silently relabel old packs.

Proposed signature envelope: format/version, algorithm=Ed25519, detached signature, public key encoding and SHA256 fingerprint over its specified DER SubjectPublicKeyInfo bytes, manifest hash. Verification signs/verifies the raw saved manifest bytes; public-key inclusion alone does not establish trust. Private key comes from an external configured path; keys are not generated silently in normal startup. Default unsigned legacy path stays usable; signing-required configuration fails clearly when key is absent/invalid. Any key/trust format/API status names must be concretely accepted before implementation.

Proposed aerosol scope: distinct media record, case/run/site lineage, collection start/end and valid duration, flow/volume units and provenance, raw pre/post/blank/duplicate/recovery mass values, QC and limited-below-LOQ result, custody and exports. Inputs are documented human-recorded SYNTHETIC_TEACHING values for this Cases scope; no Field flight capture or real measurement is implied. Propose exactly which existing commands accept media and which new command/type is necessary; never overload a fuel payload while silently changing its meaning.

The declared profile is TEACH-AEROSOL-S-1:2L/min,600valid_s,LOQ0.1ug,blankmax0.02ug,RPD20%,recovery80–120%,hold300sim_s. Effective collection volume at2L/min for600valid_s is20L=0.020m³. Confirm blank/LOQ/RPD/recovery formulas and time semantics against source profile before implementing. Never multiply valid seconds by litres/minute without dividing by60.

- [ ] Publish both exact proposals and gate dependency matrix promptly. Continue only independent contract-compatible implementation while awaiting a required decision. No claim that this planning document approves new wire schemas.

## B1: Versioned canonicalization with legacy compatibility

**Files:** Keep existing CanonicalJson.java behavior. Create cases/JcsManifest.java and test JcsManifestTest.java. Modify EvidenceVault.java/EvidenceVerifier.java only through explicit format dispatch and accepted new envelope fields. Extend EvidenceVaultTest.java/EvidenceVaultCompatibilityTest.java.

**Proposed internal interface:** `JcsManifest.bytes(JsonNode manifest): byte[]`; accepts only the approved new manifest domain and returns RFC8785 bytes, or throws an explicit unsupported-domain exception. A pinned conformant library is preferred over an improvised Double.toString serializer; A coordinates any pom change. Existing CanonicalJson.bytes remains legacy exact-decimal behavior.

- [ ] Add failing official RFC8785 number/string/property-order vectors, Unicode/surrogate refusal and unsupported numeric-domain tests. Confirm license/attribution for adopted implementation.
- [ ] Preserve and test legacy sealed members/manifests as exact bytes: ordinary decimals, huge exact integers,1e1000 and1e-1000. Both valid old packs and old tamper cases retain their results; verification does not regenerate member bytes.
- [ ] Implement explicit version dispatch. Unknown versions fail clearly. No fallback that interprets malformed v2 as valid legacy.
- [ ] Run focused canonicalization/vault tests with captured exit/results under the global Java reservation; release the reservation before source edits.

## B2: Ed25519 and external trust

**Create:** cases/EvidenceSigning.java, EvidenceTrust.java and matching tests. **Modify:** EvidenceVault.java, EvidenceVerifier.java, ExportHandlers.java and relevant evidence scope notes to distinguish unsigned integrity/signature/trust axes without overwriting old recorded notes.

**Proposed internal interfaces:** `EvidenceSigning.sign(byte[] manifest, PrivateKey key): byte[]`; `EvidenceSigning.verify(byte[] manifest, byte[] signature, PublicKey key): boolean`; `EvidenceTrust.fingerprint(PublicKey key): String` (lowercase hex SHA256 of specified encoded key); trust is evaluated separately against external allowed fingerprints.

- [ ] First write tests proving exact-byte verification and wrong/tampered key/message behavior. Example Java17 crypto oracle, using temporary test keys only:

```java
var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
byte[] manifest = "{\"version\":2}".getBytes(StandardCharsets.UTF_8);
byte[] signature = EvidenceSigning.sign(manifest, pair.getPrivate());
assertTrue(EvidenceSigning.verify(manifest, signature, pair.getPublic()));
byte[] changed = manifest.clone();
changed[changed.length - 2] ^= 1;
assertFalse(EvidenceSigning.verify(changed, signature, pair.getPublic()));
var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
assertFalse(EvidenceSigning.verify(manifest, signature, other.getPublic()));
```

- [ ] Implement bounded external-key/config parsing, missing/malformed/unsupported algorithm rejection and explicit unsigned mode. Never log/copy private key bytes or commit generated test fixtures containing a real key.
- [ ] Separate cryptographic validity and key trust. A valid signature with an unknown fingerprint is untrusted; it cannot become trusted merely because the archive carries that key. No signature establishes trusted time, physical truth or legal suitability.
- [ ] Integrate with existing seal_export/verify_export revisions/role/idempotency/automation; keep old unsigned exports verifiable and no record mutation in place. Tests cover wrong role, stale revision, repeat command, signing failure rollback and missing blobs.
- [ ] Publish envelope/config/trust documentation and focused test results to A. Actual owner trust-anchor acceptance remains a disclosed gate.

## B3: Safe ZIP and standalone Java verifier

**Create:** cases/EvidenceArchive.java, EvidenceVerifyCli.java; tests EvidenceArchiveTest.java, EvidenceVerifyCliTest.java. **Modify:** EvidenceVerifier.java to extract reusable pure verification shared by server/CLI without live-store access. A supplies an approved bounded read-only download adapter/tool launcher if required; B sends exact proposed patch and authentication/run-scope expectations.

**Proposed internal interfaces:** `EvidenceArchive.write(OutputStream, manifestBytes, memberSources, signatureEnvelope)` and `EvidenceArchive.readAndVerify(InputStream, VerificationOptions): VerificationResult`; define the concrete records in this package after approved format decision. `EvidenceVerifyCli.main(String[] args)` accepts `--pack PATH --trust-sha256 HEX` plus an explicit unsigned-legacy inspection option. Output bounded JSON with each check axis; nonzero exit for malformed/integrity/signature failures. For signed v2, untrusted key cannot exit as a trusted pass. A legacy-integrity-only success must print unsigned/untrusted plainly.

Proposed bounds, subject to checking current exported sizes before fixing constants: at most500payload members plus declared metadata entries;1MiB uncompressed per payload;64MiB cumulative uncompressed;64MiB compressed input;JSON depth64. Read streams with counters rather than trusting ZIP headers. Reject duplicate/aliased paths, absolute paths,backslashes,drive-letter/UNC paths,NUL,empty/dot/dot-dot segments,undeclared/extra/missing members,unsupported/encrypted entries and malformed lengths/hashes. Never extract to arbitrary paths. Reject before exceeding limits; no unbounded readAllBytes on archive input. Manifest/signature metadata also have finite size limits.

- [ ] Add failing valid-pack, altered-member, altered-manifest, altered-signature, wrong/untrusted/missing key, duplicate entry, traversal, extra/missing file, oversized/decompression-limit, truncated ZIP and bad-version tests.
- [ ] Implement bounded streaming archive writer/reader and shared verification. Blob lookups stay exact-hash/length checked; reads cannot expose another run's data. New export transport never includes phone/private/truth/model data.
- [ ] Package CLI launch with Java17; A coordinates classpath/distribution and pom changes. Test actual exit codes and machine-readable output using a clean directory with no server/DB/environment key, including correctly signed and tampered copies. Same-machine isolation is not a second physical laptop claim.
- [ ] Deliver pack, verifier distribution/launch instructions and results manifest to A. Deleting a pack must not destroy original stored evidence; tamper demonstrations always use copies.

## B4: Separate aerosol media, loading and QC

**Create:** cases/AerosolMath.java, AerosolHandlers.java and matching tests. **Modify:** CasesMethod.java, CasesRecords.java, CasesHandlers.java, LabHandlers.java, EvidenceVault.java/EvidenceVerifier.java only as the approved record/command/lineage design requires. A owns any needed Field/public API change; no synthetic aerosols are injected as fuel or as real sensor observations.

**Consumes:** Approved media lineage and explicit raw mass/blank/flow/time/QC inputs, TEACH-AEROSOL-S-1 profile and same-run append-only records. **Produces:** Distinct media/custody/lab results with canonical units, finite derived values, QC status/reasons, below-LOQ limited state and export references. Missing raw values remain null+reason.

- [ ] Write boundary math tests:20L from2L/min*600s; exact blank maximum and just above; exact LOQ and just below; duplicate RPD threshold and zero-denominator case;80/120%recovery endpoints and just outside;hold-time boundary and interrupted/insufficient collection. Negative/nonfinite/impossible inputs are refused explicitly.
- [ ] Implement formulas with exact unit conversions and declared decimal rounding. Preserve raw values; never clamp negative corrected mass to zero without an accepted explicit rule and visible reason.
- [ ] Implement ordinary command handlers with existing bus/session/roles/revision/idempotency and append-only custody; refuse cross-case/run/specimen/media references. Fuel and aerosol are separate identities; a reused fuel specimen cannot pass as media.
- [ ] Extend export allowlist/lineage/privacy checking for approved aerosol record types, including every referenced collection/custody/lab revision. Test sealed aerosol pack offline, bad-QC/limited finding behavior and tampered copies. A supported finding is never automatically invented from passing lab QC.
- [ ] Verify all pre-existing fuel/lab/finding/sealing paths remain valid. Publish collection provenance and explicit absence of new airborne aerosol physics. If required producer inputs are unavailable, mark the exact end-to-end dependency blocked rather than fabricate it.

## B5: Regression, independent handoff and review

- [ ] Run targeted new tests after genuine failing tests/implementation; use serial Maven and release the source lock for fixes. Then run the Cases package regression once on current B bytes, recording fresh suite counts and failures/skips rather than reusing429baseline as new evidence.
- [ ] Self-check export privacy, legacy bytes, exact decimals, signed/unsigned/trusted/untrusted axes, ZIP resource bounds, Java17 clean-process behavior, aerosol/fuel lineage and all human-only gates. Verify private keys/config never enter deliverables.
- [ ] Publish laneB-status.json READY only when all required local tests pass and proposals needed for delivered behavior are accepted. Include full file before/after hashes, test commands/logs, protocol/config decisions, distribution paths and any remaining physical gates. Unfinished features are listed explicitly.
- [ ] Stop source writes and release an actor slot. Lane A/fresh independent reviewer reviews the complete delta before integration, without the author approving their own work. Resolve review findings under the shared locks, then retest affected behavior and update hashes.
- [ ] Support final integrated rehearsal only on coordinator request; do not launch a second full Java run. Full Antara software completion and whole-product completion are separate acceptance entries.

Full remaining B software estimate remains24–40focused hours, not a promise that it fits a short training wait. Preserve signed format/contract/trust decisions and physical second-laptop checks as explicit gates; no commit/merge/push/deploy/freeze.
