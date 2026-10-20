# Teaching aerosol media, custody and lab results

Normative extension of AGREEMENT.md version 2.4, adopted under Aditya Singh’s explicit delegation on 20 October 2026. This incorporates the approved amended protocol; its test and implementation notes describe validation, not additional permissions. Selecting the local synthetic demo signer is separately recorded outside this contract. Physical-device tests are never implied.

## 1. Scope and honesty boundary

- Aerosol filter media are a separate Cases material identity. They are never a fuel `specimen`, never a `screening_record`, and never a drone/sensor reading.
- **Inputs:** every collection and lab number is entered by a person through a command. Records label them `origin: "SYNTHETIC_TEACHING"` and `input_basis: "HUMAN_RECORDED_SYNTHETIC_TEACHING"`. No new airborne capture physics is implied. The server never generates aerosol values.
- **Field link:** Field has no aerosol capture method today. Every media record therefore has `attempt_ref: null` and `attempt_ref_reason: "FIELD_AEROSOL_CAPTURE_NOT_AVAILABLE"`, set by the server. A payload `attempt_ref` is refused. No Field attempt is invented.
- **Size class:** `size_class: "UNCLASSIFIED"`. No PM2.5/PM10 qualification (questions trace FP31).
- **Thresholds:** the profile's thresholds are used exactly as declared. None are tuned. Fuel behaviour is unchanged.

Declared profile (`contracts/reference/method-profiles.json`, loaded the same way `CasesMethod` loads the fuel profile; startup fails clearly if the profile is absent or invalid):

| Field | Value |
|---|---|
| `id` | TEACH-AEROSOL-S-1 |
| `origin` | SYNTHETIC_TEACHING |
| `size_class` | UNCLASSIFIED |
| `flow_L_min` | 2 |
| `valid_s` | 600 |
| `analyte` | sulfur_mass |
| `loq_ug` | 0.1 |
| `blank_max_ug` | 0.02 |
| `duplicate_RPD_max_percent` | 20 |
| `recovery_percent` | [80, 120] |
| `hold_s` | 300 |

`oracle_efficiency` is never read, as with the fuel oracle mass. Material is `SYNTHETIC_UNCLASSIFIED_AEROSOL`, a declared enum value in `data-contract-catalog.json`.

## 2. Records (new types; append-only revision rows in the generic RecordStore; no migration)

### 2.1 `media`
| Field | Value |
|---|---|
| `case_id` | UUID of a same-run case |
| `media_type` | `"FILTER"` |
| `material` | `"SYNTHETIC_UNCLASSIFIED_AEROSOL"` |
| `method_id` | `"TEACH-AEROSOL-S-1"` |
| `size_class` | `"UNCLASSIFIED"` |
| `container`, `seal_id` | text ≤ 256, required |
| `location_label` | text ≤ 256, required; a human description of where the filter sampled. Not a factory label; S01–S06 are refused here so media cannot carry a training-site label |
| `collection` | object, see §4.1: raw human inputs exactly as sent, plus server checks |
| `collection_computed` | `volume_L`, `volume_m3` (binary64 numbers or null), `computed_decimal` {`volume_L`,`volume_m3`} (plain decimal strings), `calculation_precision: "DECIMAL128_HALF_EVEN"`, `computed_reasons` |
| `collection_reasons` | string[] |
| `attempt_ref` | `null` |
| `attempt_ref_reason` | `"FIELD_AEROSOL_CAPTURE_NOT_AVAILABLE"` |
| `origin` | `"SYNTHETIC_TEACHING"` |
| `input_basis` | `"HUMAN_RECORDED_SYNTHETIC_TEACHING"` |
| `authority_basis` | `"INSPECTOR_DEMO"` |
| `collector_id` | session actor label |
| `current_holder` | actor label |
| `current_media_custody_event_id` | UUID |
| `seal_state` | `INTACT`/`BROKEN`/`MISSING` |
| `custody_exceptions` | string[] |
| `recorded_utc` | instant |
| `recorded_sim_time_s` | number or null |
| `recorded_sim_time_reason` | null or `CURRENT_RUN_CLOCK_UNAVAILABLE` |
| `case_ref` | exact ref |

### 2.2 `media_custody_event` (insert-only; never revised)
`media_id`, `previous_event_id` (null for the first), `from_holder`, `to_holder`, `seal_state`, `event_time_utc`, `recorded_utc`, `recorded_by`, `exceptions[]` and optionally `attempted_to_holder`. The rules are identical to fuel `append_custody`: the same exception set and seal agreement; a non-holder needs an exception; RECIPIENT_ABSENT/REFUSED/CORRECTION do not transfer; a correction refers only to the immediately previous event. It is a separate record type, so fuel custody chains, counts and screens are unaffected.

### 2.3 `media_lab_result`
| Field | Value |
|---|---|
| `media_id` | |
| `method_id` | |
| `origin` | `"SYNTHETIC_TEACHING"` |
| `input_basis` | `"HUMAN_RECORDED_SYNTHETIC_TEACHING"` |
| `qc_inputs` | as sent, or null |
| `raw` | |
| `computed` | |
| `computed_decimal` | plain decimal strings of the DECIMAL128/HALF_EVEN result |
| `calculation_precision` | `"DECIMAL128_HALF_EVEN"` |
| `computed_reasons` | |
| `qc_status` | `PASS`/`FAIL`/`PENDING` |
| `qc_checks` | `{flow, collection, blank, net, duplicate, recovery, hold}` → `{status, reasons[]}` |
| `qc_reasons[]` | |
| `diagnostics[]` | |
| `reporting` | `REPORTED`/`BELOW_LOQ`/null |
| `reporting_reason` | |
| `result_use` | `USABLE` / `LIMITED_BELOW_LOQ` / `NOT_USABLE` / `PENDING` |
| `hold_time_s` | |
| `hold_time_reason` | |
| `collection_end_sim_s` | |
| `analysis_sim_time_s` | |
| `analysis_sim_time_reason` | |
| `media_ref`, `case_ref`, `custody_ref` | exact refs (custody_ref → `media_custody_event`) |
| `method_limits` | the exact profile values used |
| `analyzed_by` | |
| `limitations[]` | always includes "Synthetic teaching aerosol filter measurement, entered by a person; no airborne capture was simulated." and "A usable aerosol result describes only this filter; it does not show that any named source caused the plume." |

### 2.4 `case` additions (written only by the new commands)
`media_ids[]` and `media_lab_result_ids[]`. Fuel cases without media are unchanged. Case states:
- `create_media` moves INTAKE → SPECIMEN (the existing "material held" state), as `create_specimen` does.
- `request_media_lab` moves INTAKE/SPECIMEN → LAB.

## 3. Commands (new)

All three go through the core CommandBus with role, then idempotency by `command_id`, then `expected_revision`. All three refuse the `automation` actor because no automation role is listed. All three reject contact text and require the same run, exactly like the fuel handlers. Unknown payload keys are refused.

| Command | Roles | Subject (`expected_revision`) | Payload keys |
|---|---|---|---|
| `create_media` | inspector_demo | case | `case_id`, `material`, `method_id`, `media_type`, `container`, `seal_id`, `location_label`, `collection` |
| `append_media_custody` | inspector_demo, lab_demo | media | `media_id`, `to_holder`, `seal_state`, `event_time_utc`, `exceptions[]` |
| `request_media_lab` | lab_demo | media | `media_id`, `method_id`, `qc_inputs` |

The `collection` object has keys `start_sim_s`, `end_sim_s`, `valid_s`, `flow_L_min`, `status` (`COMPLETE` or `INTERRUPTED`). `qc_inputs` is an object or null, with keys `gross_ug`, `blank_ug`, `dup_a_ug`, `dup_b_ug`, `spike_added_ug`, `spike_recovered_ug`. A payload `mass_mg`, `volume_L`, `attempt_ref` or `site_id` is refused: volume is computed, and a site label is not accepted on media.

Rejections use the existing codes:
- `INVALID` with a plain reason;
- `WRONG_STATE` for a case not in INTAKE/SPECIMEN/LAB/FINDING;
- `NOT_ALLOWED` for a role or non-holder;
- `STALE_REVISION`.

Cross references are refused when they point to another case or run, to a fuel specimen given as `media_id`, or to media given as `specimen_id` in fuel commands (fuel commands already look up `specimen` only). The existing fuel `request_lab` keeps refusing non-fuel methods and materials.

`create_media` is refused on a SEALED case. Fuel specimens may still be added to a sealed case as today; that is existing behaviour.

## 4. Units, formulas and time semantics (exact decimal decisions; limits inclusive)

**[A-amend 2] Precision.** Raw decimal tokens are preserved as sent. Subtraction is exact; every QC decision uses exact arithmetic or exact cross-multiplication of raw values, never a rounded quotient or a display conversion. Non-terminating divisions (e.g. `2 × 599.999 / 60`) use DECIMAL128 with HALF_EVEN; their strings in `computed_decimal` preserve the declared decimal calculation result and do not promise arbitrary mathematical exactness. JSON numbers in `computed`/`collection_computed` are the binary64 projection; if that projection would overflow, be non-finite, or turn a nonzero value into zero, the number is `null` with reason `NOT_REPRESENTABLE` for that projection, the decimal string is retained, and the affected check is PENDING so the result cannot be `USABLE`. Zero, NaN or infinity are never emitted silently. A missing value is `null` with a reason; it is never zero.

Input validation, the same as fuel `LabMath.read`:
- **Missing** (absent or null) → that check is PENDING with reason `<field> is missing`.
- **Invalid** → that check is FAIL with reason `INVALID_INPUT: <field> is not a number / not finite / is negative / outside the supported number range`.

The invalid case covers non-number, non-finite, negative, and out-of-range values (including non-zero underflow) in raw masses, flow or time. A negative raw input is invalid. A negative corrected mass is not (see net below).

### 4.1 Collection (checked at `create_media`; the record is saved even when QC will fail, so the case keeps the history)
Units: simulation seconds of this run for times, actual L/min for flow, L and m³ for volume.

- **Refused at command time (`INVALID`):**
  - `collection` missing or not an object;
  - `status` not `COMPLETE`/`INTERRUPTED`;
  - any time or flow field missing, non-numeric, negative or non-finite;
  - `end_sim_s <= start_sim_s`;
  - `valid_s <= 0`;
  - `valid_s > end_sim_s - start_sim_s` (valid seconds cannot exceed the window);
  - `flow_L_min <= 0`;
  - `end_sim_s` later than the current run clock, when the clock is known.

  If the clock is unknown, the media is saved with `collection.time_check: "UNCHECKED"` and reason `CURRENT_RUN_CLOCK_UNAVAILABLE`.
- `volume_L = flow_L_min × valid_s / 60`, computed with DECIMAL128/HALF_EVEN for non-terminating division; the 2 L/min × 600 s example is exactly 20 L. `volume_m3 = volume_L / 1000`, so 0.020 m³.
- QC checks are evaluated in the lab result from the media's saved collection:
  - **flow:** PASS iff `flow_L_min == 2` exactly; otherwise FAIL `FLOW_NOT_METHOD_FLOW`.
  - **collection:** FAIL `INSUFFICIENT_COLLECTION` if `valid_s < 600`. PASS when `valid_s >= 600` (600 exactly passes). FAIL `COLLECTION_INTERRUPTED` if `status == INTERRUPTED`; this can be reported together with insufficiency.

### 4.2 Lab (`request_media_lab`)
Units: µg for all masses.

**[A-amend 5] Assay input meaning.** `gross_ug` is the sulfur-assay mass reported for the filter extract before blank correction. It is not a bulk filter weight and not a simulated sensor measurement. Gravimetric pre/post filter weighing is deferred (named in the handoff); no pre/post weights are accepted, manufactured or implied, and bulk filter mass is never used to establish sulfur mass.

- **net:** `net_ug = gross_ug − blank_ug`. A negative net is kept unchanged and adds diagnostic `NEGATIVE_CORRECTED_MASS: blank exceeds gross; kept as measured, not clamped`. It is not a QC failure by itself; reporting is then BELOW_LOQ.
- **concentration:** `concentration_ug_m3 = net_ug × 1000 / volume_L`, computed when net and volume are known. `computed_reasons.concentration_ug_m3` explains when it is null.
- **blank:** FAIL `BLANK_ABOVE_LIMIT` iff `blank_ug > 0.02`. Exactly 0.02 passes.
- **LOQ:** `reporting = BELOW_LOQ` iff `net_ug < 0.1`, otherwise `REPORTED` (exactly 0.1 is REPORTED). The measured net and concentration stay visible; nothing becomes zero.
- **duplicate:** `rpd_pct = 200 × |a − b| / (a + b)` on `dup_a_ug`, `dup_b_ug` as entered. If `a + b == 0`, the RPD is null and the check is PENDING (`RPD_UNDEFINED`). It is a FAIL (`DUPLICATE_RPD_ABOVE_LIMIT`) iff `200 × |a − b| > 20 × (a + b)`; exactly 20% passes.
- **recovery:** `recovery_pct = 100 × recovered / added`. If `added == 0`, the check is PENDING (`RECOVERY_UNDEFINED`). It is a FAIL below the range (`RECOVERY_BELOW_LIMIT`) iff `100 × recovered < 80 × added`, and above it (`RECOVERY_ABOVE_LIMIT`) iff `100 × recovered > 120 × added`. Both endpoints pass.
- **hold:** `hold_time_s = analysis_sim_time_s − collection.end_sim_s`, both in the same run.
  - FAIL `HOLD_TIME_EXCEEDED` iff `> 300` (exactly 300 passes).
  - PENDING `HOLD_TIME_UNKNOWN` if either time is unknown, or if analysis is before collection end.
  - Analysis time is the current run clock when the command is applied.
- **Overall `qc_status`:**
  1. PENDING if any check is PENDING, any input is missing, or any arithmetic is undefined.
  2. Otherwise FAIL if any check FAILs.
  3. Otherwise PASS.

  A known FAIL stays visible even while another check is PENDING. This is identical to fuel.
- **`result_use`:**

  | `qc_status` | `reporting` | `result_use` |
  |---|---|---|
  | PASS | REPORTED | `USABLE` |
  | PASS | BELOW_LOQ | `LIMITED_BELOW_LOQ` |
  | FAIL | any | `NOT_USABLE` |
  | PENDING | any | `PENDING` |

Boundary test vectors (B4, each also written just past the boundary):

| Check | Passes | Fails / changes |
|---|---|---|
| Volume | 2 × 600 / 60 = 20 L = 0.020 m³ | — |
| Flow | 2 | 1.999, 2.001 |
| Collection | `valid_s` 600 | 599.999; `INTERRUPTED` |
| Blank | 0.02 | 0.020001 |
| LOQ | net 0.1 REPORTED | 0.099999 BELOW_LOQ |
| Negative net | gross 0.01, blank 0.02 → −0.01 kept, `NEGATIVE_CORRECTED_MASS`, BELOW_LOQ | — |
| RPD | a=0.9, b=1.1 → exactly 20% (200×0.2 = 20×2.0) | a=0.9, b=1.1001 |
| RPD zero | — | a=b=0 → PENDING |
| Recovery | 80 and 120 of 100 added | 79.999, 120.001 |
| Recovery zero | — | added 0 → PENDING |
| Hold | 300 | 300.001; unknown → PENDING; analysis before end → PENDING |
| Negative raw | — | e.g. `blank_ug: -0.01` → INVALID_INPUT FAIL |

## 5. Findings, export lineage and learning-loop isolation

- **Findings:** `FindingHandlers.CITABLE` gains `media_lab_result`, handled by an explicit `media_lab_result` branch (never the analysis default branch). A cited media lab must be the exact same-run revision, and its media must belong to this case.
  - It counts toward SUPPORTED_WITHIN_SCOPE only when `result_use == USABLE`.
  - It counts toward REJECTED_FOR_USE when `qc_status == FAIL`.
  - `LIMITED_BELOW_LOQ` and `PENDING` support neither; INCONCLUSIVE remains allowed.

  Finding text rules and human-only `revise_finding` are unchanged.
- **Export (v1 and v2):** `EvidenceVault.ALLOWED` gains `media`, `media_custody_event` and `media_lab_result`. Collection works as for specimens:
  - every `media_ids` media, at its latest revision, must have the same case;
  - its full media custody chain must loop-free match the count of the run's events for that media;
  - every `media_lab_result_ids` result must be present, plus every cited media lab;
  - each media lab's pinned `media_ref`, `case_ref` and `custody_ref` must be members.

  The private-field scan and run checks apply to all of them.
- **[A-amend 4] Verifier semantic lineage (shared store-free core, server and CLI).** Existence in the pack is not enough. For each member the verifier checks exact type/id/revision against the path and every ref's `type`, plus:
  - `media`: `run_id` = case run, `case_id` = pack case, `material`/`method_id` = SYNTHETIC_UNCLASSIFIED_AEROSOL / TEACH-AEROSOL-S-1, `current_media_custody_event_id` is a member `media_custody_event` whose `media_id` is this media and whose `to_holder` = media `current_holder`;
  - `media_custody_event`: `media_id` names a member media of this case; `previous_event_id` (if any) names a member event of the same media; chain is loop-free;
  - `media_lab_result`: `media_ref`/`case_ref`/`custody_ref` have exact types `media`/`case`/`media_custody_event`, are members, the ref'd media's case = pack case, `custody_ref` event's `media_id` = `media_id`, `method_id` matches the media's method;
  - fuel `lab_result` refs get the same exact-type checks (`specimen`/`case`/`custody_event`, custody `specimen_id` match) — additive stricter lineage on existing packs, which still pass because accepted sealing produces consistent bytes.
  Tests: cross-case, cross-run, wrong-type ref, mismatched custody owner, method mismatch — each yields lineage FAIL (exit 6 offline).
- **Custody summary:** includes media and media custody events. It is `NOT_APPLICABLE` only when the pack has neither specimens nor media; any media exception or non-intact seal makes it `LIMITED`. Fuel-only packs produce identical results, because the new inputs are empty.
- **Learning loop:** `fuelprint.ai.LearningRules` (unchanged) reads only cited `lab_result` records with a fuel `specimen_ref` and `site_id`. A `media_lab_result` therefore cannot become a lesson.
  - **Proof:** a sealed SUPPORTED_WITHIN_SCOPE case whose only usable lab is a `media_lab_result` is listed as SKIPPED with reason "No cited PASS lab with exact same-case specimen lineage; not a lesson."
  - **Where the test goes:** B delivers the exact source to `handoffs/laneB/ai-tests/` (with fixtures and intended command); Lane A places and runs it in `server/src/test/java/fuelprint/ai/`. It covers an aerosol-only supported case (no lesson) and a mixed fuel+aerosol case (aerosol never admitted as fuel; label only from the fuel specimen). No model artefact or learned success is fabricated. A Cases-side public-path test is added only if the real public API supports it.
  - **Name collision:** media records also carry no `site_id`.
