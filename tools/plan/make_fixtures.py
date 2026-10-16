"""Writes apps/shared/fixtures: one example of every record type in contracts/AGREEMENT.md (fp/1).
Values follow scenario SC1-NIGHT-PLUME. IDs are fixed so files can point at each other."""
import json, uuid, pathlib, hashlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "apps/shared/fixtures"; OUT.mkdir(parents=True, exist_ok=True)
def uid(name): return str(uuid.uuid5(uuid.NAMESPACE_URL, "fuelprint/" + name))
RUN = uid("run")
def base(kind, name, rev=1, by="system", t="2026-10-22T10:00:00.000Z"):
    return {"id": uid(name), "run_id": RUN, "revision": rev, "created_utc": t, "created_by": by}
def rec(kind, name, **f):
    d = base(kind, name, f.pop("_rev", 1), f.pop("_by", "system")); d.update(f); return d
sensors = [rec("sensor", n, label=n, inlet={"east": e, "north": no, "up": 3}, channels=["SO2_ppb", "CO2_ppm", "PM_ugm3"],
               status="OK", calibration_state="VALID_SYNTHETIC")
           for n, e, no in [("N1", -20, 60), ("N2", 120, 100), ("N3", 280, 140), ("N4", 400, -100)]]
mission_hash = hashlib.sha256(b"example-dependency").hexdigest()
F = {
 "run": rec("run", "run", preset_id="SC1-NIGHT-PLUME", mode="BLIND", state="RUNNING", parent_run_id=None),
 "sensor": sensors,
 "reading": rec("reading", "reading", sensor_or_vehicle_id=sensors[1]["id"], channel="SO2_ppb", value=61.2, raw_value=61.2,
                unit="ppb", sim_time_s=412.0, quality=["OK"], origin="SYNTHETIC_TEACHING"),
 "window": rec("window", "window", channel="SO2_ppb", start_s=382.0, end_s=412.0, valid_seconds=30, mean_enhancement=48.7,
               std_uncertainty=6.1, background_window_id=uid("bg-window")),
 "trigger": rec("trigger", "trigger", channel="SO2_ppb", sensor=sensors[1]["id"], intervals=[[397.0, 412.0]],
                background_window_id=uid("bg-window"), rule="TEACH-DETECT-1"),
 "investigation": rec("investigation", "investigation", trigger_id=uid("trigger"), survey_id=None, state="ANALYSED",
                      analysis_ids=[uid("analysis")]),
 "analysis": rec("analysis", "analysis", input_window_ids=[uid("window")], outcome="AMBIGUOUS_OR_OPTIONAL_MIXTURE",
                 qualification="SYNTHETIC_SCOPE_ONLY",
                 candidates=[{"explanation_id": "S02", "sources": ["S02"], "fit": 0.94, "status": "SURVIVES", "reason": None},
                             {"explanation_id": "S01", "sources": ["S01"], "fit": 1.07, "status": "SURVIVES", "reason": None},
                             {"explanation_id": "S01+S02", "sources": ["S01", "S02"], "fit": 0.91, "status": "SURVIVES", "reason": None},
                             {"explanation_id": "S03", "sources": ["S03"], "fit": 9.80, "status": "REJECTED", "reason": "RESIDUAL_ABOVE_CUTOFF"},
                             {"explanation_id": "UNKNOWN", "sources": [], "fit": None, "status": "SURVIVES", "reason": "UNREGISTERED_SOURCES_NOT_EXCLUDED"}],
                 exclusions=[{"reading_window": uid("window-n4"), "reason": "STALE"}],
                 limitations=["Synthetic model only, not field validated", "Unregistered sources cannot be ruled out",
                              "Fit numbers are not probabilities"],
                 explanation_text="Box N2 saw a sustained SO2 rise. S01 and S02 sit on the same wind line upwind of N2, so both still fit. A reading closer to S02 at plume height would separate them.",
                 algorithm_version="FP-COMPAT-3-stub"),
 "proposal": rec("proposal", "proposal", analysis_id=uid("analysis"), result="BEST_ACTION", best_action_id="A-AER-0-100",
                 cheaper_action_id="A-GND-120-100",
                 actions=[{"action_id": "A-AER-0-100", "kind": "DISCRIMINATE", "vehicle": "DRONE", "target": {"east": 0, "north": 100, "up": 35},
                           "score": 2.4, "complete_minutes": 9.5, "feasible": True, "rejection_reason": None},
                          {"action_id": "A-GND-120-100", "kind": "DISCRIMINATE", "vehicle": "DRONE", "target": {"east": 120, "north": 100, "up": 3},
                           "score": 0.6, "complete_minutes": 8.0, "feasible": True, "rejection_reason": None},
                          {"action_id": "A-GND-280-140", "kind": "DISCRIMINATE", "vehicle": "VAN", "target": {"east": 280, "north": 140, "up": 3},
                           "score": 0, "complete_minutes": None, "feasible": False, "rejection_reason": "UNREACHABLE"}]),
 "mission": rec("mission", "mission", proposal_id=uid("proposal"), survey_id=None, action_id="A-AER-0-100", vehicle_id=uid("drone-1"),
                target={"east": 0, "north": 100, "up": 35}, route=[{"east": 0, "north": 0, "up": 2}, {"east": 0, "north": 100, "up": 35}],
                method_id="TEACH-NODE-ONLINE-2", channel_ids=["SO2_ppb", "CO2_ppm"], dependency_hash=mission_hash,
                state="APPROVED", expires_s=712.0, _rev=2, _by="supervisor"),
 "pairing": rec("pairing", "pairing", mission_id=uid("mission"), vehicle_id=uid("drone-1"), code="KQ7TXP", phrase="green river",
                expires_utc="2026-10-22T10:02:00.000Z", state="CONFIRMED"),
 "lease": rec("lease", "lease", vehicle_id=uid("drone-1"), holder_session="visitor-7f3a", state="ACTIVE",
              expires_utc="2026-10-22T10:03:02.000Z", last_sequence=1532),
 "attempt": rec("attempt", "attempt", mission_id=uid("mission"), method_id="TEACH-NODE-ONLINE-2", state="COMPLETE",
                stages=[{"stage": s, "start_s": a, "end_s": b} for s, a, b in
                        [("SETUP", 520, 550), ("BACKGROUND", 550, 580), ("PURGE", 580, 610), ("SETTLE", 610, 730),
                         ("COLLECT", 730, 760), ("RECOVER", 760, 880), ("REVIEW", 880, 910)]],
                channels=[{"channel": "SO2_ppb", "valid_seconds": 30, "volume_L": 0.25, "status": "VALID", "reasons": []},
                          {"channel": "CO2_ppm", "valid_seconds": 30, "volume_L": 0.15, "status": "VALID", "reasons": []}]),
 "screening_record": [rec("screening_record", "screen-so2", attempt_id=uid("attempt"), channel="SO2_ppb", named_use="SYNTHETIC_ONLINE_SCREENING",
                          decision="VALID", reasons=[], summary={"mean_enhancement_ppb": 112.4, "valid_seconds": 30, "volume_L": 0.25}),
                      rec("screening_record", "screen-co2", attempt_id=uid("attempt"), channel="CO2_ppm", named_use="SYNTHETIC_ONLINE_SCREENING",
                          decision="VALID", reasons=[], summary={"mean_enhancement_ppm": 21.0, "valid_seconds": 30, "volume_L": 0.15})],
 "case": rec("case", "case", investigation_id=uid("investigation"), screening_record_ids=[uid("screen-so2"), uid("screen-co2")],
             purpose="Follow-up of SO2 event at N2", state="LAB", _by="case_reviewer"),
 "specimen": rec("specimen", "specimen", case_id=uid("case"), site_id="S02", material="SYNTHETIC_COAL_COKE_LIKE", method_id="TEACH-FUEL-S-1",
                 container="JAR-0042", seal_id="SEAL-7781", mass_mg=100, authority_basis="INSPECTOR_DEMO", current_holder="lab_demo"),
 "custody_event": [rec("custody_event", "cust-1", specimen_id=uid("specimen"), from_holder=None, to_holder="inspector_demo", seal_state="INTACT",
                       event_time_utc="2026-10-22T10:20:00.000Z", recorded_utc="2026-10-22T10:20:03.000Z", exceptions=[]),
                   rec("custody_event", "cust-2", specimen_id=uid("specimen"), from_holder="inspector_demo", to_holder="lab_demo", seal_state="INTACT",
                       event_time_utc="2026-10-22T10:24:00.000Z", recorded_utc="2026-10-22T10:24:05.000Z", exceptions=[])],
 "lab_result": rec("lab_result", "lab", specimen_id=uid("specimen"), method_id="TEACH-FUEL-S-1",
                   raw={"gross_ug": 2010, "blank_ug": 10, "mass_mg": 100, "dup_a_pct": 2.0, "dup_b_pct": 2.2, "spike_added_ug": 100, "spike_recovered_ug": 90},
                   computed={"net_ug": 2000, "percent_w_w": 2.0, "rpd_pct": 9.5238, "recovery_pct": 90.0},
                   qc_status="PASS", qc_reasons=[], reporting="REPORTED"),
 "finding": rec("finding", "finding", case_id=uid("case"), outcome="SUPPORTED_WITHIN_SCOPE",
                cited_refs=[{"type": "screening_record", "id": uid("screen-so2"), "revision": 1}, {"type": "lab_result", "id": uid("lab"), "revision": 1}],
                alternatives=["Unregistered source upwind", "S01 contribution not fully excluded under wind error"],
                limitations=["Synthetic teaching data", "The specimen result describes this material only", "Legal suitability not assessed"],
                legal_suitability="NOT_ASSESSED"),
 "board_decision": rec("board_decision", "board", case_id=uid("case"), decision="CANDIDATE_SUPPORTED_SIMULATED",
                       rationale="Valid screening and a passing synthetic lab result; alternatives remain.",
                       notice={"area": "Estate block west", "interval": "22 Oct, 01:00 to 03:00", "status_text": "An air quality investigation is in progress in this area."}),
 "export": rec("export", "export", case_id=uid("case"), case_revision=6, manifest_sha256=hashlib.sha256(b"example-manifest").hexdigest(),
               members=[{"path": "payload/records/finding/%s-r1.json" % uid("finding"), "length": 812, "sha256": hashlib.sha256(b"finding").hexdigest()}],
               signature=None, state="SEALED"),
 "verification_report": rec("verification_report", "verify", export_id=uid("export"),
                            axes={"integrity": "PASS", "key_trust": "NOT_SIGNED", "lineage": "PASS", "trusted_time": "UNVERIFIED",
                                  "custody_documentation": "PASS", "measurement_qualification": "SYNTHETIC_SCOPE_ONLY", "legal_suitability": "NOT_ASSESSED"},
                            reasons=[]),
 "receipt": {"receipt_id": uid("receipt"), "command_id": uid("cmd"), "status": "APPLIED", "reason_code": None, "reason_text": None,
             "record_refs": [{"type": "mission", "id": uid("mission"), "revision": 2}]},
 "tick": {"schema_version": "fp/1", "message_type": "tick", "run_id": RUN, "server_epoch": uid("epoch"), "cursor": 1842, "sim_time_s": 412.35,
          "server_time_utc": "2026-10-22T10:15:04.120Z",
          "payload": {"wind": {"from_deg": 270, "speed_m_s": 3.0}, "time_scale": 1,
                      "vehicles": [{"vehicle_id": uid("drone-1"), "label": "DRONE-1", "kind": "DRONE",
                                    "pose": {"east": 12.0, "north": 98.5, "up": 35.0, "yaw_deg": 90}, "velocity": {"east": 1.2, "north": 0, "up": 0},
                                    "inlet": {"east": 12.4, "north": 98.5, "up": 34.6}, "energy_wh": 51.3, "stage": "OUTBOUND", "lease_holder": "visitor-7f3a"}],
                      "latest_readings": [{"sensor": "N2", "channel": "SO2_ppb", "value": 61.2, "quality": ["OK"], "sim_time_s": 412.0}]}},
}
for k, v in F.items():
    (OUT / f"{k}.json").write_text(json.dumps(v, indent=2, sort_keys=True) + "\n")
(OUT / "README.md").write_text("# Example data (fp/1)\n\nOne example of every record and message in `contracts/AGREEMENT.md`, from scenario SC1. "
    "Screens use these before the fake server is ready. Generated by `tools/plan/make_fixtures.py`; do not edit by hand.\n")
print(len(F), "fixture files written to", OUT)
