# Cases evidence v2, signing, ZIP and offline verification

Normative extension of AGREEMENT.md version 2.4, adopted under Aditya Singh’s explicit delegation on 20 October 2026. This incorporates the approved amended protocol; its test and implementation notes describe validation, not additional permissions. Selecting the local synthetic demo signer is separately recorded outside this contract. Physical-device tests are never implied.

## 1. Compatibility rules (unchanged legacy)

- `fp.evidence/1` sealing, member bytes, manifest bytes, export fields (`signature: null`, `signature_reason: "NOT_SIGNED_UNTIL_FINALE"`) and verification results stay byte-for-byte and behaviour-for-behaviour unchanged. `CanonicalJson` is not modified.
- Legacy packs are never re-labelled, re-serialised, rounded or re-signed. No claim is made that legacy manifests are RFC 8785; B1 adds a test that only *reports* whether sampled legacy manifests happen to equal restricted-JCS bytes, with no behavioural dependency on that answer.
- Extreme legacy decimals (`1e1000`, `1e-1000`, huge integers, ordinary decimals) in members keep verifying from stored bytes.
- Default configuration (no config file) = format 1, UNSIGNED. All 429 accepted Cases tests must keep passing unchanged.

## 2. Manifest v2 and the restricted canonical profile

### 2.1 Profile `FP-JCS-RESTRICTED-1`

A restricted profile of RFC 8785. It is RFC 8785 output only for this domain; we do not claim general RFC 8785 support.

Accepted JSON values: object, array, string, `true`, `false`, `null`, and integers `n` with `-(2^53-1) <= n <= 2^53-1` written without fraction or exponent.

Output rules (identical to RFC 8785 for this domain):
- Object members sorted by UTF-16 code units of the key (`String.compareTo`); no whitespace.
- Strings: escape `"` `\` as `\"` `\\`; `\b \f \n \r \t`; other U+0000–U+001F as `\u00xx` (lower-case hex); everything else literal UTF-8 (including `/`, U+007F, U+2028).
- Integers as minimal decimal digits; `-0` is not an accepted input form.

Refused, with an explicit error and never rounded or coerced:
- any number with a fraction, exponent, or outside the safe-integer range (`UNSUPPORTED_NUMBER`);
- lone surrogates or invalid UTF-8 (`INVALID_UNICODE`);
- duplicate object keys (`DUPLICATE_PROPERTY`);
- trailing tokens or bytes after the value (`TRAILING_CONTENT`);
- nesting deeper than 64 (`TOO_DEEP`);
- input over the size limit (`TOO_LARGE`: manifest 1 MiB, signature envelope 16 KiB).

Verification parses bytes with a strict decoder (`CodingErrorAction.REPORT`, Jackson `STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`, float tokens rejected at token level). It then re-serialises and requires byte equality with the input (`NOT_CANONICAL` otherwise).

Interface (package-private, `fuelprint.cases.JcsManifest`):
```java
static byte[] bytes(JsonNode value)          // throws JcsManifest.Unsupported(code, text)
static JsonNode parseCanonical(byte[] bytes)  // throws JcsManifest.Unsupported; checks re-serialised equality
```
Tests: RFC 8785 string serialization (section 3.2.2.2) and property ordering (section 3.2.3) examples, Appendix B number samples that fall inside the safe-integer domain (and explicit refusal of the ones that do not), UTF-16 vs code-point ordering (`"\u20ac"`, `"\ud83d\ude00"` keys), every refusal code, and the safe-integer boundaries `±(2^53-1)` (accepted) and `±2^53` (refused).

### 2.2 Manifest v2 fields (exact key set; unknown keys refused)

```json
{
  "schema": "fp.evidence/2",
  "canonicalization": "FP-JCS-RESTRICTED-1",
  "member_encoding": "fp-canonical-json/1",
  "case_id": "uuid",
  "case_revision": 7,
  "created_utc": "2026-10-20T11:00:00.000Z",
  "members": [{"path": "payload/records/case/<uuid>-r7.json", "length": 1234, "sha256": "64 hex"}],
  "limitations": ["..."],
  "signing": {"policy": "SIGN_REQUIRED", "algorithm": "Ed25519", "key_fingerprint_sha256": "64 hex"}
}
```
- `member_encoding` states truthfully that members are the project's exact-decimal canonical JSON (unchanged `CanonicalJson`), not RFC 8785. Members are hashed and verified as exact stored bytes.
- `signing` is `null` for an unsigned v2 pack. When signed, it binds the intended signer fingerprint inside the signed bytes, so a key substitution is detectable.
- Limitations for v2 replace "Unsigned integrity record only" with one of these exact sentences:
  - Signed: "Signed integrity record: a valid signature from a trusted key shows these stored bytes are unchanged since that key signed them, not when, or that any measurement is true."
  - Unsigned v2: the existing unsigned sentence.

### 2.3 Version dispatch

The verifier reads `schema` from the strictly parsed manifest and dispatches:
- `fp.evidence/1` → existing legacy path, unchanged.
- `fp.evidence/2` → v2 checks.
- Offline (pack) verification has no export record, so the expected manifest hash is the pack's own manifest; manifest tampering is then detected through the signature (signed) or through member/lineage inconsistency, and is otherwise undetectable for an unsigned pack. This limit is printed in `reasons` for unsigned packs.
- Anything else, or an export record whose `schema` differs from the manifest's → integrity FAIL with `UNSUPPORTED_VERSION` / `MANIFEST_INVALID`.

A malformed v2 manifest never falls back to v1.

## 3. Configuration (external, never committed)

Spring property `fp.cases.evidence-config`, default `server/config/evidence.json` (resolved like `sms.json`, relative to the repository root). A missing file means format 1 / UNSIGNED / no trust anchors. Example (Lane A places an `evidence.example.json` beside `sms.example.json` if wanted; B does not edit resources):

```json
{
  "format": 2,
  "signing_policy": "SIGN_REQUIRED",
  "private_key_path": "/Users/<user>/fp-keys/evidence-ed25519.pk8.pem",
  "public_key_path": "/Users/<user>/fp-keys/evidence-ed25519.spki.pem",
  "trust_anchors_path": "/Users/<user>/fp-keys/evidence-trust.json"
}
```

Example file: Lane A placed `server/config/evidence.example.json` (v1 UNSIGNED); the live `server/config/evidence.json` is git-ignored. Invalid configuration or signing failure never falls back to unsigned sealing.

Supported combinations (anything else is `EVIDENCE_CONFIG_INVALID`):

| format | signing_policy | Sealing result |
|---|---|---|
| 1 (default) | UNSIGNED (default) | legacy v1, unsigned (current behaviour) |
| 2 | UNSIGNED | v2 manifest, `signing: null`, export `signature_reason: "UNSIGNED_BY_POLICY"` |
| 2 | SIGN_REQUIRED | v2 manifest, Ed25519 signature; refuses to seal if signing cannot succeed |

Refused combinations, documented with reasons:
- format 1 + any signing: v1 has no signature slot.
- `SIGN_IF_CONFIGURED`: a silent downgrade.
- Unknown formats or policies, and unknown config keys.

Invalid config does not stop the server. Every `seal_export` (including automation) is REJECTED with `EVIDENCE_CONFIG_INVALID` plus a key-free reason. Verification keeps working.

Key handling:
- `private_key_path`: PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`, Ed25519 OID 1.3.101.112).
- `public_key_path`: X.509 SubjectPublicKeyInfo PEM. Java 17 cannot derive an Ed25519 public key from a private key.
- Both files must be at most 4 KiB, readable regular files, and resolve (real path) outside the repository root (`KEY_INSIDE_REPOSITORY` otherwise).
- At load, the pair is checked by signing and verifying a fixed probe in memory (`KEY_PAIR_MISMATCH`).

Under `SIGN_REQUIRED`, `seal_export` is REJECTED (no export record, case stays FINDING) on any of these codes:
- `SIGNING_KEY_MISSING`
- `SIGNING_KEY_UNREADABLE`
- `SIGNING_KEY_INVALID`
- `SIGNING_KEY_UNSUPPORTED_ALGORITHM`
- `KEY_INSIDE_REPOSITORY`
- `KEY_PAIR_MISMATCH`
- `EVIDENCE_SIGNING_FAILED`

Keys are never generated by the server. Key bytes, PEM text and paths' file contents never appear in records, events, logs, receipts, packs or exceptions. Reasons name the code and the config key only. Tests create temporary keys in a JUnit temp directory outside the repo.

Trust anchors file (max 16 KiB, max 64 entries; strict parse):
```json
{"schema": "fp.evidence-trust/1", "trusted": [{"key_fingerprint_sha256": "64 lower-case hex", "label": "text <= 128"}]}
```
The fingerprint is the lower-case hex SHA-256 of the DER SubjectPublicKeyInfo bytes (44 bytes for Ed25519). Shown on screens as the full hex plus a 12-character prefix. Creating or accepting a real anchor is Aditya's separate gate.

## 4. Signature envelope and export record

Signature is detached: the signed message is the exact stored manifest bytes, so the signature is not part of what it signs. The envelope is stored as its own blob (restricted-JCS bytes) and appears in the ZIP as `signature.json`:
```json
{
  "schema": "fp.evidence-signature/1",
  "algorithm": "Ed25519",
  "manifest_sha256": "64 hex",
  "public_key_spki_b64": "base64 of DER SPKI",
  "key_fingerprint_sha256": "64 hex",
  "signature_b64": "base64 of 64-byte signature"
}
```

New fields on the v2 `export` record (v1 export records unchanged):
- `schema`: `"fp.evidence/2"`
- `signing_policy`: `"UNSIGNED" | "SIGN_REQUIRED"`
- `signature`: `null` when unsigned, otherwise the locator `{"path":"signature.json","length":n,"sha256":"hex","algorithm":"Ed25519","key_fingerprint_sha256":"hex"}`
- `signature_reason`: `"UNSIGNED_BY_POLICY"` or `null`

The signing step runs inside the `seal_export` command after members and manifest are stored and read back. Any failure rejects the whole command and saves no records; content-addressed blobs left behind are harmless, as today. Role, idempotency, `expected_revision`, `case_revision`, the auto_seal check and `cause` marker are unchanged. `tamper_copy` copies the signature locator unchanged; the copy still fails integrity on the altered member.

Interfaces (package-private):
```java
// EvidenceSigning
static byte[] sign(byte[] manifest, PrivateKey key);              // Ed25519 only
static boolean verify(byte[] manifest, byte[] signature, PublicKey key);
// EvidenceTrust
static String fingerprint(PublicKey key);                          // hex SHA-256 of getEncoded() SPKI
static EvidenceTrust load(Path anchors);                           // strict; refusal codes as above
boolean trusts(String fingerprint);
```

## 5. Verification result (seven axes retained)

**[A-amend 3] Signed-intent binding.** The verifier requires agreement among: manifest `signing` declaration (algorithm, fingerprint), envelope `algorithm`/`key_fingerprint_sha256`, actual SHA-256 of the embedded SPKI, envelope `manifest_sha256` vs actual manifest bytes, and the Ed25519 signature. A manifest declaring signing with no envelope is `SIGNATURE_INVALID` (`SIGNED_INTENT_ENVELOPE_MISSING`) and never passes, even with `--allow-unsigned`. A manifest with `signing: null` (or a v1 manifest) accompanied by a signature envelope is refused (`UNEXPECTED_SIGNATURE_ENVELOPE`, archive refusal in the ZIP; `SIGNATURE_INVALID` in the server). An embedded public key supplies verification material only.

The seven axes stay exactly the same names. `key_trust` values:

| key_trust | Meaning |
|---|---|
| `NOT_SIGNED` | v1, or v2 with `signing: null` |
| `TRUSTED` | signature valid over exact manifest bytes AND fingerprint is in the externally supplied anchors |
| `UNTRUSTED_KEY` | signature valid but fingerprint absent from anchors, or no anchors configured |
| `SIGNATURE_INVALID` | signature does not verify, envelope fingerprint ≠ embedded key's actual fingerprint, envelope `manifest_sha256` ≠ manifest, or manifest `signing.key_fingerprint_sha256` ≠ envelope fingerprint |
| `UNVERIFIED` | located envelope bytes unreadable, malformed, non-canonical or unsupported algorithm; signed intent with a missing envelope locator is INVALID / SIGNATURE_INVALID, not UNVERIFIED |

A new, separate `signature_check` object holds both results apart:
```json
{"validity": "VALID|INVALID|NOT_SIGNED|UNVERIFIED", "trust": "TRUSTED|UNTRUSTED|NOT_EVALUATED", "trust_source": "SERVER_CONFIG|CLI_ARGUMENT|NONE", "key_fingerprint_sha256": "hex or null", "reason": "text"}
```
An embedded public key alone never yields TRUSTED. `integrity` keeps meaning "stored bytes match the stored manifest" and is computed independently of the signature. `trusted_time` stays `UNVERIFIED`, `measurement_qualification` stays `SYNTHETIC_SCOPE_ONLY`, `legal_suitability` stays `NOT_ASSESSED`. The verification report adds `verifier_version: "fp-evidence-verify/1"`.

## 6. ZIP pack

Layout, written in this order:
1. `manifest.json` (exact stored manifest bytes)
2. `signature.json` (only when signed)
3. payload members at their manifest `path`, in manifest order

Metadata entries are exactly those two names, at most 2, counted separately from at most 500 payload members. There are no directory entries, comments or extra files. Members use DEFLATED or STORED, and member bytes equal the stored blob bytes.

**[A-amend 1] Writer spools before success.** `EvidenceArchive.prepare(StoredRecord export, BlobStore blobs, Path tempDir): Path` builds the complete ZIP in a private temporary file (owner-only permissions), applying the compressed/expanded/per-entry limits while writing, validating every source blob against its storage metadata (export record locator length + SHA-256; manifest + signature locators too), and closes/finalizes the ZIP before returning. Any failure deletes the temporary file and throws a coded refusal (`EVIDENCE_MISSING`, `EVIDENCE_STORAGE`, `EVIDENCE_TOO_LARGE`). The HTTP layer commits headers only after `prepare` succeeds, streams the file, and deletes it in `finally` (success, failure, client disconnect). Original exports and labelled tamper copies are both downloadable: a tamper copy's altered member blob is validated against the copy's own locator (so it is served as-is with its deliberate manifest mismatch); nothing is repaired or mutated. Nothing is extracted to filesystem paths. A network interruption during transfer can still truncate the response; the CLI rejects truncated input (exit 3).

Reader limits, enforced while reading with byte counters (never trusting ZIP header sizes):

| Limit | Value |
|---|---|
| Compressed archive | ≤ 64 MiB |
| Total entries | ≤ 502; payload ≤ 500, metadata ≤ 2 |
| Per payload entry, expanded | ≤ 1 MiB |
| `manifest.json` | ≤ 1 MiB |
| `signature.json` | ≤ 16 KiB |
| Total expanded | ≤ 64 MiB |
| JSON depth | ≤ 64 |
| Entry name length | ≤ 200 |

Refusals (archive is rejected):
- **Names:** any name other than the two metadata names or the `EvidenceVault.MEMBER_PATH` grammar. This covers absolute paths, `..`, empty or `.` segments, backslash, drive letters and UNC paths, NUL, and non-ASCII.
- **Collisions:** duplicate or case-colliding names, and directory entries.
- **Entry formats:** encrypted entries, entries using any method other than STORED/DEFLATED, and entries whose data descriptor or size disagrees with the bytes read.
- **Structure:** truncated, corrupt or non-ZIP input. The central-directory entry list (`ZipFile`) must equal the local-header stream list (`ZipInputStream`) in names, order and count.
- **Members:** an undeclared payload entry (in the ZIP but not in the manifest) or a missing member (in the manifest but not the ZIP).

Nothing is extracted to disk.

## 7. Standalone verifier CLI

Class `fuelprint.cases.EvidenceVerifyCli` (public, `main`, no Spring context, no database, no network, no environment keys; uses only Jackson and the JDK). It shares pure verification code with the server (`EvidenceVerifier` refactored into a store-free core taking a member-bytes lookup).

Launch from the packaged server jar, with no pom change. Feasibility is to be confirmed in B3; if it fails, B sends Lane A an exact packaging proposal.
```
java -cp fuelprint-server-<version>.jar \
  -Dloader.main=fuelprint.cases.EvidenceVerifyCli \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --pack PATH.zip [--trust-sha256 HEX]... [--trust-file PATH] [--allow-unsigned]
```
- `--trust-sha256` may repeat (≤ 64). `--trust-file` uses the §3 anchors format.
- `--allow-unsigned` is the explicit option for an integrity-only success on a pack that declares no signing (v1, or v2 `signing: null`) and carries no envelope; it never excuses signed intent with a missing, invalid or untrusted signature. The output still says `key_trust: NOT_SIGNED` and `"trust": "NOT_EVALUATED"` plainly. It never permits exit 0 for an untrusted or invalid signature.

Exit codes:

| Code | Meaning |
|---|---|
| 0 | `PASS`: archive OK, integrity PASS, lineage PASS, and either the signature is VALID + TRUSTED, or the pack is unsigned and `--allow-unsigned` was given |
| 1 | Internal error |
| 2 | Usage error (bad or missing arguments, unreadable pack path) |
| 3 | Archive refused (malformed, truncated, unsafe or duplicate name, limit exceeded, undeclared or missing member) |
| 4 | Unsupported version (manifest, signature envelope or algorithm) |
| 5 | Integrity FAIL (member or manifest bytes, non-canonical v2 manifest) |
| 6 | Lineage FAIL with integrity PASS |
| 7 | Signature INVALID or UNVERIFIED |
| 8 | Signature valid but untrusted, or unsigned without `--allow-unsigned` |

Precedence when several apply: the highest-priority category is reported, in this order: 2 > 3 > 4 > 5 > 6 > 7 > 8 > 0. Code 1 overrides all. All detected findings still appear in the JSON.

Stdout is one bounded JSON object (member checks capped at 500 entries; reasons capped at 200 strings of at most 512 characters):
```json
{"verifier":"fp-evidence-verify/1","pack":{"sha256":"hex","bytes":n},"schema":"fp.evidence/2","result":"PASS|FAIL","exit_code":0,
 "archive_check":{"status":"PASS|REFUSED","code":null,"payload_entries":n,"metadata_entries":n,"expanded_bytes":n},
 "axes":{"integrity":"PASS","key_trust":"TRUSTED","lineage":"PASS","trusted_time":"UNVERIFIED","custody_documentation":"PASS","measurement_qualification":"SYNTHETIC_SCOPE_ONLY","legal_suitability":"NOT_ASSESSED"},
 "signature_check":{"validity":"VALID","trust":"TRUSTED","trust_source":"CLI_ARGUMENT","key_fingerprint_sha256":"hex","reason":"..."},
 "member_checks":[{"path":"...","status":"PASS"}],"reasons":["..."],
 "scope_note":"Offline check of these bytes only. A valid signature verifies against the configured key; external trust policy determines whether that key is trusted. This does not establish a person's identity, signing time, measurement truth, or legal suitability."}
```
Custody documentation is computed from member bytes only (the export record's sealed summary is not in the pack).

Test plan (B3): valid signed pack exits 0 with the trusted fingerprint and 8 without it. Then:
- altered member → 5;
- altered manifest → 5;
- altered signature or envelope → 7;
- wrong key → 7;
- untrusted key → 8;
- missing trust → 8;
- unsigned with and without `--allow-unsigned` → 0 or 8;
- duplicate, traversal, extra, missing, case-colliding names, encrypted entry, oversized entry, decompression bomb, truncated file and bad version → 3 or 4;
- usage errors → 2.

The CLI runs as a real `java` subprocess in an empty temporary directory with no server, database or config. This is a same-machine clean-process check, not the second-laptop gate.

## 8. Download controller (decided: option (a), B-owned)

`fuelprint.cases.EvidencePackController` (B-owned bounded extension; no core edit, no core→cases dependency).

- `GET /api/exports/{export_id}/pack.zip?run_id=<uuid>`.
- Session: inject public `fuelprint.api.SessionService`, call `resolve(HttpServletRequest)` → `fuelprint.core.command.Session`. No dependency on `CookieSessionService`; never creates a session. Payload/header role or actor claims are ignored.
- Authorization first, before any record lookup: require a resolved, non-automation station session (`stationId() != null`) whose `roles()` contains `Role.EXPORTER` (multi-role stations allowed). Otherwise 403 `{"reason_code":"NOT_ALLOWED"}`.
- Then: malformed `export_id`/`run_id` → 400 `INVALID`; unknown export or export of another run → 404 `NOT_FOUND`; state not `SEALED`/`TAMPER_DEMO_COPY` → 400 `NOT_A_PACKABLE_EXPORT`; preparation refusal → 409 `EVIDENCE_MISSING` / 500 `EVIDENCE_STORAGE` / 413 `EVIDENCE_TOO_LARGE` as JSON, before any ZIP bytes. Reasons never contain secret contents or filesystem paths. No record writes.
- Success headers: `Content-Type: application/zip`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Content-Disposition: attachment; filename="fp-evidence-<first 8 of export_id>-<ORIGINAL|TAMPER_DEMO_COPY>.zip"` built only from validated IDs and the fixed label.
- Tests: no session, guest/visitor, non-exporter, automation-equivalent, wrong run, unknown, bad id, wrong state, corrupt/missing later member blob → non-2xx JSON with no partial ZIP; original and tamper copy downloads parse with the CLI reader.

The CLI scope note (section 7) is, exactly: "Offline check of these bytes only. A valid signature verifies against the configured key; external trust policy determines whether that key is trusted. This does not establish a person's identity, signing time, measurement truth, or legal suitability."

**[A-amend 3] Mutation expectations.** Tests assert the actual affected axes and documented precedence rather than forcing a code: offline there is no external manifest expectation, so an altered but still canonical manifest whose member list still matches the members surfaces as signature `INVALID` (exit 7); one whose member entries no longer match the bytes surfaces as integrity FAIL (exit 5, with the signature failure also listed). On the server the export record's `manifest_sha256` still detects it as integrity FAIL. All safely detected findings stay listed.
