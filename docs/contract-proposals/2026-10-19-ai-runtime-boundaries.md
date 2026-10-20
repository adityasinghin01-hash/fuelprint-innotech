# AI runtime boundaries — local implementation notes

This is a local, unpublished Task8 implementation note, not an approved contract/API extension or a trained-model qualification report. Current-source regression and independent review are still pending.

The real backend implements the existing interfaces without inventing a trained artifact:

- Learned ordering returns NOT_APPLICABLE for INVALID/NO_SIGNAL, MODEL_MISSING without active metadata, and FAILED for ambiguous, invalid or unavailable active artifacts/runtime. It produces no order and preserves physics candidates, outcomes and UNKNOWN. Earlier-run survival counts use public analyses, once per source per earlier run.
- Packaged artifact validation checks a bounded `models/*.onnx` resource path and its actual SHA-256 bytes. Matching a hash does not establish valid ONNX, feature compatibility, inference availability or qualification. No ONNX runtime dependency or feature ABI was invented.
- Normal-air inference returns an empty Optional. `record_anomaly` refuses MODEL_MISSING or MODEL_UNAVAILABLE; payload-supplied bands never become an anomaly.
- Wording uses safe deterministic public text and records TEMPLATE with a fallback reason. External providers remain disabled. `write_wording` reads the exact allowed target revision, rather than accepting caller-supplied prompt text. Listeners submit normal commands after commit; the pure wording service never submits or waits on a command.
- Retraining assesses exact public case/finding/lab/specimen references and records eligible/skipped lessons in its normal TRAINING_UNAVAILABLE refusal receipt. It does not create a model_version, evaluation counts or a false REJECTED_BY_GATE result. Replayed sealed-case events consult retained receipts before submitting again.
- The pure quality-gate comparator checks supplied count vectors and the two-night tolerance. A real trainer must establish the same held-out cohort before using it; equal counts/denominators cannot establish that provenance.

The existing APIs leave these concrete future integration gaps:

1. ONNX feature names/order, tensor names/shapes/dtypes, preprocessing, compatibility/version metadata and test-cohort identity need a genuine trained artifact and reviewed specification before inference or activation. A filename/hash alone is insufficient.
2. `NormalAirModel.expected` can express an unavailable band but cannot return a structured availability reason. A future model-status read API would need its own reviewed contract; this work adds no such endpoint or fabricated band.
3. `model_version.state` has ACTIVE, RETIRED and REJECTED_BY_GATE, but no training-request/unavailable state. Until genuine training exists, a refused command receipt is the honest trace. A separate structured training-assessment record would require a reviewed record contract.

Actual training, ONNX parity, real held-out evaluation, provider activation and resulting model performance remain deferred. Existing V4 physics measurements do not qualify these AI services. No external publication, merge, deployment or freeze is implied.
