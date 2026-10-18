#!/usr/bin/env python3
"""Writes the fake-mode recordings server/src/main/resources/replay/SC1.ndjson, SC2.ndjson, SC3.ndjson.

Hand-scripted from apps/shared/fixtures (version 2) following the scenario stories of contracts/AGREEMENT.md
section 10. Everything here is synthetic teaching data. Run from anywhere:  python3 tools/replay/make_replay.py

Line kinds (read by fuelprint.core.replay.ReplayEngine):
  header   scenario, preset_id, duration_s, keep_ids
  frame    t, wind, time_scale, vehicles[] (tick vehicle objects), readings[] (one per sensor and channel;
           raw_value defaults to value and quality to ["OK"])
  write    t, actor, records[]            records the server saved by itself (world loop, field movement)
  command  t, command_type, command_id, actor, status, reason_code, reason_text, records[]
A record entry is {type, id, revision, run_id, event_type, fields}. "@run" is the run id, "@sensor:N2" the id of
that run's N2 box. The engine gives every other UUID a fresh value per loop, except keep_ids.
"""
import copy
import json
import math
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "apps" / "shared" / "fixtures"
OUT = ROOT / "server" / "src" / "main" / "resources" / "replay"
WORLD = json.loads((ROOT / "contracts" / "reference" / "world-profile.json").read_text())

COMMON = {"id", "run_id", "revision", "created_utc", "created_by"}
NS = uuid.UUID("6f1c2a1e-5d0b-4c8e-9a51-3c7d2f0e8b11")
DRONE_ID = "9ad11ce0-f3b3-54f6-a41a-871f695c246f"
SETTINGS_ID = "3390ed7e-d71d-5b19-b1ee-85438491991d"
CHANNELS = [("SO2_ppb", "ppb", 5.0), ("CO2_ppm", "ppm", 420.0), ("PM_ugm3", "ug_m3", 18.0)]
NODES = {n["id"]: (n["east_m"], n["north_m"], n["inlet_m"]) for n in WORLD["nodes"]}

SUPERVISOR = "supervisor@laptop-1"
ANALYST = "analyst@laptop-1"
FIELD_OP = "field_operator@laptop-2"
QC = "qc_reviewer@laptop-2"
CASE_REVIEWER = "case_reviewer@laptop-3"
LAB = "lab_demo@laptop-3"
INSPECTOR = "inspector_demo@laptop-4"
BOARD = "board@laptop-5"
VISITOR = "visitor-7f3a"
AUTO = "automation"


def fixture(name):
    return json.loads((FIXTURES / f"{name}.json").read_text())


def own(record):
    """The record's own fields (no common fields)."""
    return {k: v for k, v in copy.deepcopy(record).items() if k not in COMMON}


class Scenario:
    def __init__(self, name, preset_id, duration_s):
        self.name = name
        self.preset_id = preset_id
        self.duration_s = duration_s
        self.lines = []
        self.revisions = {}
        self.fields = {}
        self.keep = {DRONE_ID, SETTINGS_ID}

    def uid(self, label):
        return str(uuid.uuid5(NS, f"{self.name}:{label}"))

    def entry(self, rtype, rid, fields, event_type, run_scoped=True):
        rev = self.revisions.get((rtype, rid), 0) + 1
        self.revisions[(rtype, rid)] = rev
        self.fields[(rtype, rid)] = copy.deepcopy(fields)
        return {"type": rtype, "id": rid, "revision": rev, "run_id": "@run" if run_scoped else None,
                "event_type": event_type, "fields": fields}

    def change(self, rtype, rid, event_type, **updates):
        """Next revision of a record with some fields changed."""
        fields = copy.deepcopy(self.fields[(rtype, rid)])
        fields.update(updates)
        return self.entry(rtype, rid, fields, event_type)

    def write(self, t, actor, *records):
        self.lines.append({"kind": "write", "t": t, "actor": actor, "records": list(records)})

    def command(self, t, command_type, actor, *records, status="APPLIED", reason_code=None, reason_text=None):
        self.lines.append({"kind": "command", "t": t, "command_type": command_type,
                           "command_id": self.uid(f"cmd:{command_type}:{t}"), "actor": actor, "status": status,
                           "reason_code": reason_code, "reason_text": reason_text, "records": list(records)})

    def save(self):
        header = {"kind": "header", "scenario": self.name, "preset_id": self.preset_id,
                  "duration_s": self.duration_s, "keep_ids": sorted(self.keep),
                  "note": "Synthetic teaching data, hand-scripted by tools/replay/make_replay.py. Not a real run."}
        self.lines.sort(key=lambda line: (line["t"], 0 if line["kind"] == "frame" else 1))
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / f"{self.name}.ndjson"
        with path.open("w", encoding="utf-8") as out:
            out.write(json.dumps(header, separators=(",", ":")) + "\n")
            for line in self.lines:
                out.write(json.dumps(line, separators=(",", ":")) + "\n")
        print(f"wrote {path.relative_to(ROOT)}: {len(self.lines) + 1} lines, {path.stat().st_size // 1024} KiB")


# ---------------------------------------------------------------- frames (box readings and the drone)

def wobble(t, k, size):
    """Small deterministic wobble so charts are not flat lines."""
    return size * math.sin(t / 7.0 + k) + 0.4 * size * math.sin(t / 2.3 + 2 * k)


def box_readings(t, so2_rise):
    readings = []
    for k, (label, _) in enumerate(sorted(NODES.items())):
        for c, (channel, unit, base) in enumerate(CHANNELS):
            value = base + wobble(t, k + c, 0.6 if channel == "SO2_ppb" else 1.5)
            if channel == "SO2_ppb":
                value += so2_rise(label, t)
            value = round(value, 1)
            readings.append({"sensor": label, "sensor_or_vehicle_id": f"@sensor:{label}", "channel": channel,
                             "value": value, "raw_value": value, "unit": unit, "quality": ["OK"]})
    return readings


def lerp(a, b, f):
    return tuple(round(x + (y - x) * f, 2) for x, y in zip(a, b))


class Flight:
    """The drone's path as (t, position, stage, pilot, lease_holder) keyframes."""

    def __init__(self):
        self.keys = [(0, (0.0, 0.0, 0.0), None, "NONE", None)]

    def at(self, t, pos, stage, pilot, holder):
        self.keys.append((t, pos, stage, pilot, holder))

    def state(self, t):
        keys = self.keys
        for i in range(len(keys) - 1):
            t0, p0, s0, pilot, holder = keys[i]
            t1, p1 = keys[i + 1][0], keys[i + 1][1]
            if t0 <= t < t1:
                f = (t - t0) / (t1 - t0) if t1 > t0 else 0
                pos = lerp(p0, p1, f)
                speed = math.dist(p0, p1) / (t1 - t0) if t1 > t0 else 0
                if speed > 0:
                    vel = tuple(round((y - x) / (t1 - t0), 2) for x, y in zip(p0, p1))
                    yaw = round((math.degrees(math.atan2(p1[0] - p0[0], p1[1] - p0[1])) + 360) % 360, 1)
                else:
                    vel, yaw = (0.0, 0.0, 0.0), 0.0
                return pos, vel, yaw, s0, pilot, holder
        t0, p0, s0, pilot, holder = keys[-1]
        return p0, (0.0, 0.0, 0.0), 0.0, s0, pilot, holder


def drone(flight, t, energy):
    pos, vel, yaw, stage, pilot, holder = flight.state(t)
    return {"vehicle_id": DRONE_ID, "label": "DRONE-1", "kind": "DRONE",
            "pose": {"east": pos[0], "north": pos[1], "up": pos[2], "yaw_deg": yaw},
            "velocity": {"east": vel[0], "north": vel[1], "up": vel[2]},
            "inlet": {"east": pos[0], "north": pos[1], "up": round(max(0.0, pos[2] - 0.4), 2)},
            "energy_wh": round(energy, 1), "stage": stage, "lease_holder": holder, "pilot": pilot}


def frames(sc, flight, so2_rise, drone_so2, drone_flags=None, drone_flow=None):
    energy = 64.0
    for t in range(0, sc.duration_s + 1):
        pos, vel, *_ = flight.state(t)
        if pos[2] > 0.5:
            energy -= 0.044 + 0.002 * sum(v * v for v in vel)
        readings = box_readings(t, so2_rise)
        so2 = round(drone_so2(t, pos) + wobble(t, 9, 0.8), 1)
        flags = drone_flags(t) if drone_flags else ["OK"]
        readings.append({"sensor": "DRONE-1", "sensor_or_vehicle_id": DRONE_ID, "channel": "SO2_ppb", "value": so2,
                         "raw_value": so2, "unit": "ppb", "quality": flags})
        co2 = round(420 + (21.0 if so2 > 60 else 0) + wobble(t, 4, 1.2), 1)
        readings.append({"sensor": "DRONE-1", "sensor_or_vehicle_id": DRONE_ID, "channel": "CO2_ppm", "value": co2,
                         "raw_value": co2, "unit": "ppm", "quality": ["OK"]})
        flow = drone_flow(t) if drone_flow else 0.5
        readings.append({"sensor": "DRONE-1", "sensor_or_vehicle_id": DRONE_ID, "channel": "SO2_flow_L_min",
                         "value": flow, "raw_value": flow, "unit": "L_min", "quality": ["OK"]})
        for reading in readings:  # defaults the engine fills in: raw_value = value, quality = ["OK"]
            if reading["raw_value"] == reading["value"]:
                del reading["raw_value"]
            if reading["quality"] == ["OK"]:
                del reading["quality"]
        sc.lines.append({"kind": "frame", "t": t, "wind": {"from_deg": 270, "speed_m_s": 3.0}, "time_scale": 1,
                         "vehicles": [drone(flight, t, energy)], "readings": readings})


def n2_plume(start):
    def rise(label, t):
        if label != "N2" or t < start:
            return 0.0
        return 56.2 * min(1.0, (t - start) / 20.0)
    return rise


# ---------------------------------------------------------------- shared record builders

def model_versions(sc):
    records = []
    for mv in fixture("model_version"):
        sc.keep.add(mv["id"])
        records.append(sc.entry("model_version", mv["id"], own(mv), "MODEL_VERSION_SAVED", run_scoped=False))
    return records


def settings_entry(sc):
    fields = own(fixture("automation_settings"))
    return sc.entry("automation_settings", SETTINGS_ID, fields, "AUTOMATION_SETTINGS_CREATED", run_scoped=False)


def window(sc, label, start, end, mean, background, sensor="N2"):
    fields = own(fixture("window"))
    fields.update({"sensor_or_vehicle_id": f"@sensor:{sensor}", "start_s": start, "end_s": end,
                   "mean_enhancement": mean, "background_window_id": background, "valid_seconds": end - start})
    return sc.entry("window", sc.uid(label), fields, "WINDOW_SAVED")


def analysis(sc, label, window_ids, outcome, candidates, learned, exclusions=None, text=None, history=None):
    fields = own(fixture("analysis"))
    fields.update({"input_window_ids": window_ids, "outcome": outcome, "candidates": candidates,
                   "learned_order": learned, "learned_status": "OK" if learned else "NOT_APPLICABLE",
                   "exclusions": exclusions if exclusions is not None else fields["exclusions"]})
    if not learned:
        fields["learned_model_version_id"] = None
    if text:
        fields["explanation_text"] = text
    if history is not None:
        fields["history_counts"] = history
    return sc.entry("analysis", sc.uid(label), fields, "ANALYSIS_SAVED")


def cand(explanation, fit, status, reason=None):
    sources = [] if explanation in ("UNKNOWN", "BACKGROUND") else explanation.split("+")
    return {"explanation_id": explanation, "sources": sources, "fit": fit, "status": status, "reason": reason}


UNKNOWN_ROW = cand("UNKNOWN", None, "SURVIVES", "UNREGISTERED_SOURCES_NOT_EXCLUDED")


def wording(sc, label, target, purpose, text, source="LLM", fallback=None):
    fields = own(fixture("wording"))
    fields.update({"target": target, "purpose": purpose, "text": text, "source": source,
                   "fallback_reason": fallback, "model": "claude-haiku-5-5" if source == "LLM" else None})
    return sc.entry("wording", sc.uid(label), fields, "WORDING_SAVED")


def notification(sc, label, kind, areas, text, wording_id, cause):
    fields = own(fixture("notification")[0])
    fields.update({"kind": kind, "area_ids": areas, "text": text, "wording_id": wording_id, "cause": cause,
                   "recipients": 3, "sent": 3, "failed": 0, "state": "SENT", "suppressed_reason": None,
                   "gateway_mode": "LOG_ONLY"})
    return sc.entry("notification", sc.uid(label), fields, "NOTIFICATION_SAVED", run_scoped=False)


def ref(sc, rtype, label_or_id, by_label=True):
    rid = sc.uid(label_or_id) if by_label else label_or_id
    return {"type": rtype, "id": rid, "revision": sc.revisions[(rtype, rid)]}


def mission_story(sc, t_propose, proposed_by, proposal_label, survey_id, target, pilot, holder, t_approve,
                  t_takeoff, investigation, action_id):
    """Propose, approve, lease, fly out, capture start. Returns the mission id and the flight."""
    mission = sc.uid("mission")
    fields = own(fixture("mission"))
    route = [{"east": 0, "north": 0, "up": 2}, {"east": target[0], "north": target[1], "up": target[2]}]
    fields.update({"proposal_id": sc.uid(proposal_label) if proposal_label else None, "survey_id": survey_id,
                   "action_id": action_id, "vehicle_id": DRONE_ID,
                   "target": {"east": target[0], "north": target[1], "up": target[2]}, "route": route,
                   "state": "PROPOSED", "pilot": "NONE", "expires_s": t_propose + 300})
    sc.command(t_propose, "propose_mission", proposed_by,
               sc.entry("mission", mission, fields, "MISSION_PROPOSED"),
               sc.change("investigation", investigation, "INVESTIGATION_MISSION_PROPOSED", state="MISSION_PROPOSED"))
    sc.command(t_approve, "approve_mission", SUPERVISOR,
               sc.change("mission", mission, "MISSION_APPROVED", state="APPROVED"),
               sc.change("investigation", investigation, "INVESTIGATION_MISSION_APPROVED", state="MISSION_APPROVED"))
    lease_fields = own(fixture("lease"))
    lease_fields.update({"vehicle_id": DRONE_ID, "holder_session": holder, "state": "ACTIVE"})
    lease = sc.uid("lease")
    if pilot == "VISITOR":
        pairing = sc.uid("pairing")
        pf = own(fixture("pairing"))
        pf.update({"mission_id": mission, "vehicle_id": DRONE_ID, "state": "WAITING"})
        sc.command(t_approve + 4, "create_pairing", FIELD_OP, sc.entry("pairing", pairing, pf, "PAIRING_CREATED"))
        sc.command(t_takeoff - 6, "confirm_pairing", FIELD_OP,
                   sc.change("pairing", pairing, "PAIRING_CONFIRMED", state="CONFIRMED"))
        sc.command(t_takeoff - 2, "acquire_lease", holder, sc.entry("lease", lease, lease_fields, "LEASE_ACQUIRED"),
                   sc.change("mission", mission, "MISSION_LEASED", state="LEASED", pilot=pilot))
    elif pilot == "AUTOPILOT":
        sc.command(t_takeoff - 2, "start_autopilot", AUTO, sc.entry("lease", lease, lease_fields, "LEASE_ACQUIRED"),
                   sc.change("mission", mission, "MISSION_LEASED", state="LEASED", pilot=pilot))
    else:
        sc.command(t_takeoff - 2, "acquire_lease", FIELD_OP, sc.entry("lease", lease, lease_fields, "LEASE_ACQUIRED"),
                   sc.change("mission", mission, "MISSION_LEASED", state="LEASED", pilot=pilot))
    sc.write(t_takeoff, AUTO, sc.change("mission", mission, "MISSION_OUTBOUND", state="OUTBOUND"))
    flight = Flight()
    flight.at(t_takeoff - 2, (0.0, 0.0, 0.0), "LEASED", pilot, holder)
    flight.at(t_takeoff, (0.0, 0.0, 2.0), "OUTBOUND", pilot, holder)
    distance = math.dist((0, 0, 2), target)
    arrive = t_takeoff + math.ceil(distance / 3.0)
    flight.at(arrive, target, "AT_TARGET", pilot, holder)
    sc.write(arrive, AUTO, sc.change("mission", mission, "MISSION_AT_TARGET", state="AT_TARGET"))
    return mission, flight, lease, arrive


def capture(sc, mission, flight, arrive, target, pilot, holder, so2_status, so2_reasons, so2_seconds, so2_volume):
    """Automatic capture with compressed teaching stages, then the return flight."""
    attempt = sc.uid("attempt")
    t0 = arrive + 1
    stages_plan = [("SETUP", 10), ("BACKGROUND", 10), ("PURGE", 10), ("SETTLE", 30), ("COLLECT", 30), ("RECOVER", 20)]
    stages, t = [], t0
    for stage, length in stages_plan:
        stages.append({"stage": stage, "start_s": t, "end_s": t + length})
        t += length
    fields = own(fixture("attempt"))
    fields.update({"mission_id": mission, "stages": [dict(stages[0], end_s=None)], "state": "COLLECTING",
                   "channels": []})
    sc.command(t0, "start_capture", AUTO, sc.entry("attempt", attempt, fields, "ATTEMPT_STARTED"),
               sc.change("mission", mission, "MISSION_COLLECTING", state="COLLECTING"))
    flight.at(t0, target, "COLLECTING", pilot, holder)
    done = t
    channels = [{"channel": "SO2_ppb", "valid_seconds": so2_seconds, "volume_L": so2_volume, "status": so2_status,
                 "reasons": so2_reasons},
                {"channel": "CO2_ppm", "valid_seconds": 30, "volume_L": 0.15, "status": "VALID", "reasons": []}]
    sc.write(done, AUTO,
             sc.change("attempt", attempt, "ATTEMPT_COMPLETE", stages=stages, channels=channels, state="COMPLETE"),
             sc.change("mission", mission, "MISSION_RETURNING", state="RETURNING"))
    flight.at(done, target, "RETURNING", pilot, holder)
    back = done + math.ceil(math.dist((0, 0, 2), target) / 3.0)
    flight.at(back, (0.0, 0.0, 2.0), "RETURNING", pilot, holder)
    flight.at(back + 2, (0.0, 0.0, 0.0), "COMPLETE", "NONE", None)
    sc.write(back + 2, AUTO, sc.change("mission", mission, "MISSION_COMPLETE", state="COMPLETE"))
    collect = stages[4]
    return attempt, done, back + 2, (collect["start_s"], collect["end_s"])


def screening(sc, t, attempt, so2_decision, so2_reasons, investigation):
    records = []
    for i, base in enumerate(fixture("screening_record")):
        fields = own(base)
        fields["attempt_id"] = attempt
        if fields["channel"] == "SO2_ppb":
            fields["decision"] = so2_decision
            fields["reasons"] = so2_reasons
        records.append(sc.entry("screening_record", sc.uid(f"screening:{i}"), fields, "SCREENING_RECORDED"))
    records.append(sc.change("investigation", investigation, "INVESTIGATION_EVIDENCE_BACK", state="EVIDENCE_BACK"))
    sc.command(t, "review_capture", QC, *records)


def case_story(sc, t, investigation, outcome, alternatives, limitations, cited_extra, seal_t):
    """Case, specimen, custody, lab, finding (human), then automatic seal and verify."""
    case = sc.uid("case")
    cf = own(fixture("case"))
    cf.update({"investigation_id": investigation, "screening_record_ids": [sc.uid("screening:0"), sc.uid("screening:1")],
               "state": "INTAKE"})
    sc.command(t, "create_case", CASE_REVIEWER, sc.entry("case", case, cf, "CASE_CREATED"))
    specimen = sc.uid("specimen")
    sf = own(fixture("specimen"))
    sf.update({"case_id": case, "current_holder": "inspector_demo"})
    custody = fixture("custody_event")
    c1 = own(custody[0]); c1["specimen_id"] = specimen
    sc.command(t + 10, "create_specimen", INSPECTOR, sc.entry("specimen", specimen, sf, "SPECIMEN_CREATED"),
               sc.entry("custody_event", sc.uid("custody:1"), c1, "CUSTODY_APPENDED"),
               sc.change("case", case, "CASE_SPECIMEN", state="SPECIMEN"))
    c2 = own(custody[1]); c2["specimen_id"] = specimen
    sc.command(t + 20, "append_custody", INSPECTOR, sc.entry("custody_event", sc.uid("custody:2"), c2, "CUSTODY_APPENDED"),
               sc.change("specimen", specimen, "SPECIMEN_HANDED_OVER", current_holder="lab_demo"))
    lab = sc.uid("lab")
    lf = own(fixture("lab_result")); lf["specimen_id"] = specimen
    sc.command(t + 30, "request_lab", LAB, sc.entry("lab_result", lab, lf, "LAB_RESULT_SAVED"),
               sc.change("case", case, "CASE_LAB", state="LAB"))
    finding = sc.uid("finding")
    ff = own(fixture("finding"))
    ff.update({"case_id": case, "outcome": outcome, "alternatives": alternatives, "limitations": limitations,
               "cited_refs": [ref(sc, "screening_record", "screening:0"), ref(sc, "lab_result", "lab")] + cited_extra})
    sc.command(t + 45, "revise_finding", CASE_REVIEWER, sc.entry("finding", finding, ff, "FINDING_REVISED"),
               sc.change("case", case, "CASE_FINDING", state="FINDING"))
    summary = {"SUPPORTED_WITHIN_SCOPE": "The drone air reading and the lab result fit S02 inside this synthetic "
                                         "model only. Other sources are not ruled out. Legal suitability is not assessed.",
               "REJECTED_FOR_USE": "The SO2 reading could not be used because the tube flow stopped during "
                                   "collection. CO2 was still usable. Legal suitability is not assessed.",
               "INCONCLUSIVE": "The drone reading fits two explanations equally well, so no single source can be "
                               "named. Legal suitability is not assessed."}[outcome]
    sc.command(t + 47, "write_wording", AUTO,
               wording(sc, "wording:finding", ref(sc, "finding", "finding"), "FINDING_SUMMARY", summary))
    export = sc.uid("export")
    ef = own(fixture("export"))
    ef.update({"case_id": case, "case_revision": sc.revisions[("case", case)] + 1})
    ef["members"][0]["path"] = f"payload/records/finding/{finding}-r1.json"
    sc.command(seal_t, "seal_export", AUTO, sc.entry("export", export, ef, "EXPORT_SEALED"),
               sc.change("case", case, "CASE_SEALED", state="SEALED"))
    vf = own(fixture("verification_report")); vf["export_id"] = export
    sc.command(seal_t + 2, "verify_export", AUTO, sc.entry("verification_report", sc.uid("verify"), vf, "EXPORT_VERIFIED"))
    return case


def board(sc, t, case, investigation, decision, areas, area_text, status_text, rationale):
    bf = own(fixture("board_decision"))
    bf.update({"case_id": case, "decision": decision, "rationale": rationale,
               "notice": {"area_ids": areas, "area": area_text, "interval": "22 Oct, 01:00 to 03:00",
                          "status_text": status_text}})
    sc.command(t, "record_board_decision", BOARD, sc.entry("board_decision", sc.uid("board"), bf, "BOARD_DECISION_RECORDED"),
               sc.change("investigation", investigation, "INVESTIGATION_CLOSED", state="CLOSED"))


def alarm_start(sc, plume_start):
    """Shared SC1/SC2 opening: windows, trigger, investigation, first analysis, wording, smoke warning."""
    sc.write(0, AUTO, *model_versions(sc), settings_entry(sc))
    bg = window(sc, "window:bg", 0, 30, 0.3, None)
    sc.write(30, AUTO, bg)
    trigger_t = plume_start + 35
    w1 = window(sc, "window:1", trigger_t - 30, trigger_t, 48.7, bg["id"])
    tf = own(fixture("trigger"))
    tf.update({"sensor": "@sensor:N2", "intervals": [[plume_start + 20.0, float(trigger_t)]], "background_window_id": bg["id"]})
    trig = sc.entry("trigger", sc.uid("trigger"), tf, "TRIGGER_RAISED")
    sc.write(trigger_t, AUTO, w1, trig)
    inv = sc.uid("investigation")
    inf = own(fixture("investigation"))
    inf.update({"trigger_id": trig["id"], "survey_id": None, "state": "OPEN", "analysis_ids": [],
                "warned_area_ids": ["AREA-E"]})
    sc.command(trigger_t + 1, "open_investigation", AUTO, sc.entry("investigation", inv, inf, "INVESTIGATION_OPENED"))
    a1 = analysis(sc, "analysis:1", [w1["id"]], "AMBIGUOUS_OR_OPTIONAL_MIXTURE",
                  [cand("S02", 0.94, "SURVIVES"), cand("S01", 1.07, "SURVIVES"), cand("S01+S02", 0.91, "SURVIVES"),
                   cand("S03", 9.8, "REJECTED", "RESIDUAL_ABOVE_CUTOFF"), UNKNOWN_ROW],
                  own(fixture("analysis"))["learned_order"])
    pf = own(fixture("proposal")); pf["analysis_id"] = a1["id"]
    prop = sc.entry("proposal", sc.uid("proposal:1"), pf, "PROPOSAL_SAVED")
    sc.command(trigger_t + 3, "run_analysis", AUTO, a1, prop,
               sc.change("investigation", inv, "INVESTIGATION_ANALYSED", state="ANALYSED", analysis_ids=[a1["id"]]))
    sc.command(trigger_t + 4, "write_wording", AUTO,
               wording(sc, "wording:analysis:1", ref(sc, "analysis", "analysis:1"), "EXPLANATION",
                       own(fixture("wording"))["text"]))
    sms = "FuelPrint DEMO (simulated): smoke may drift toward East colony in the next 30 min. Investigation started. No action needed."
    w_sms = wording(sc, "wording:sms:1", ref(sc, "investigation", "investigation"), "SMS_SMOKE_WARNING", sms,
                    source="TEMPLATE", fallback="NO_KEY")
    sc.command(trigger_t + 5, "write_wording", AUTO, w_sms)
    sc.command(trigger_t + 6, "send_alert", AUTO,
               notification(sc, "notification:smoke", "SMOKE_WARNING", ["AREA-E"], sms, w_sms["id"],
                            {"type": "trigger", "id": trig["id"], "revision": 1}))
    return inv, trigger_t


def anomaly(sc, t, start):
    af = own(fixture("anomaly"))
    af.update({"sensor": "@sensor:N2", "start_s": start, "end_s": start + 300})
    sc.command(t, "record_anomaly", AUTO, sc.entry("anomaly", sc.uid("anomaly"), af, "ANOMALY_RECORDED"))


def board_sms(sc, t, text):
    w = wording(sc, "wording:sms:board", ref(sc, "board_decision", "board"), "SMS_BOARD_UPDATE", text,
                source="TEMPLATE", fallback="NO_KEY")
    sc.command(t, "write_wording", AUTO, w)
    sc.command(t + 1, "send_alert", AUTO,
               notification(sc, "notification:board", "BOARD_UPDATE", ["AREA-E"], text, w["id"],
                            ref(sc, "board_decision", "board")))


# ---------------------------------------------------------------- the three scenarios

def sc1():
    sc = Scenario("SC1", "SC1-NIGHT-PLUME", 420)
    plume = 40
    inv, trigger_t = alarm_start(sc, plume)
    target = (0.0, 100.0, 35.0)
    mission, flight, lease, arrive = mission_story(sc, trigger_t + 7, AUTO, "proposal:1", None, target, "VISITOR",
                                                   VISITOR, trigger_t + 25, trigger_t + 45, inv, "A-AER-0-100")
    attempt, done, home, collect = capture(sc, mission, flight, arrive, target, "VISITOR", VISITOR, "VALID", [], 30, 0.25)
    sc.write(home + 1, AUTO, sc.change("lease", lease, "LEASE_RELEASED", state="RELEASED"))
    screening(sc, done + 4, attempt, "VALID", [], inv)
    a2 = analysis(sc, "analysis:2", [sc.uid("window:1"), sc.uid("window:drone")], "PROVISIONAL_SINGLE",
                  [cand("S02", 0.88, "SURVIVES"), cand("S01", 6.4, "REJECTED", "RESIDUAL_ABOVE_CUTOFF"),
                   cand("S01+S02", 1.9, "REJECTED", "OPTIONAL_MEMBER_NOT_SUPPORTED"),
                   cand("S03", 11.2, "REJECTED", "RESIDUAL_ABOVE_CUTOFF"), UNKNOWN_ROW],
                  [{"explanation_id": "S02", "rank": 1, "order_score": 2.1}],
                  text="With the drone reading at 35 m only S02 still fits, inside this synthetic model only. "
                       "An unregistered source is still possible.")
    w_drone = window(sc, "window:drone", collect[0], collect[1], 112.4, sc.uid("window:bg"))
    w_drone["fields"]["sensor_or_vehicle_id"] = DRONE_ID
    pf = own(fixture("proposal"))
    pf.update({"analysis_id": a2["id"], "result": "NO_USEFUL_MEASUREMENT", "best_action_id": None,
               "cheaper_action_id": None})
    sc.write(done, AUTO, w_drone)
    sc.command(done + 6, "run_analysis", AUTO, a2, sc.entry("proposal", sc.uid("proposal:2"), pf, "PROPOSAL_SAVED"),
               sc.change("investigation", inv, "INVESTIGATION_ANALYSED", state="ANALYSED",
                         analysis_ids=[sc.uid("analysis:1"), a2["id"]]))
    sc.command(done + 7, "write_wording", AUTO,
               wording(sc, "wording:analysis:2", ref(sc, "analysis", "analysis:2"), "EXPLANATION",
                       "After the drone sample only S02 still fits, inside this synthetic model. Unknown sources "
                       "are still possible."))
    anomaly(sc, plume + 300, plume)
    case = case_story(sc, done + 15, inv, "SUPPORTED_WITHIN_SCOPE",
                      ["Unregistered source upwind", "S01 contribution not fully excluded under wind error"],
                      ["Synthetic teaching data", "The specimen result describes this material only",
                       "Legal suitability not assessed"], [ref(sc, "analysis", "analysis:2")], done + 65)
    mv = fixture("model_version")[0]
    new_mv = copy.deepcopy(own(mv))
    new_mv.update({"version": "FP-LEARN-1.1", "parent_id": mv["id"], "state": "REJECTED_BY_GATE",
                   "trained_on": dict(new_mv["trained_on"], case_examples=1,
                                      note="EXAMPLE ONLY, not a real result. Parent plus 1 lesson from a sealed case"),
                   "gate": {"passed": False, "reason": "EXAMPLE ONLY: first_correct fell by 3 test nights against the parent (limit 2)"},
                   "lessons": [{"case_id": case, "used": True, "reason": "SUPPORTED_WITHIN_SCOPE with a passing lab result"}],
                   "test_results": dict(new_mv["test_results"], first_correct=409)})
    new_id = sc.uid("model_version:1.1")
    sc.keep.add(new_id)
    sc.command(done + 70, "retrain_model", AUTO,
               sc.entry("model_version", new_id, new_mv, "MODEL_VERSION_SAVED", run_scoped=False))
    board(sc, done + 85, case, inv, "CANDIDATE_SUPPORTED_SIMULATED", ["AREA-E"], "East colony",
          "An air quality investigation is in progress in this area.",
          "Valid screening and a passing synthetic lab result; alternatives remain.")
    board_sms(sc, done + 87, "FuelPrint DEMO (simulated): the board has acted on the smoke source near East colony. "
                             "Thank you for staying informed.")
    frames(sc, flight, n2_plume(plume),
           lambda t, pos: 117.0 if pos[2] > 30 and abs(pos[1] - 100) < 3 else (60.0 if pos[2] > 20 else 5.0))
    return sc


def sc2():
    sc = Scenario("SC2", "SC2-BLOCKED-INLET", 420)
    plume = 40
    inv, trigger_t = alarm_start(sc, plume)
    target = (0.0, 100.0, 35.0)
    holder = "field_operator@laptop-2"
    mission, flight, lease, arrive = mission_story(sc, trigger_t + 7, AUTO, "proposal:1", None, target, "OPERATOR",
                                                   holder, trigger_t + 25, trigger_t + 45, inv, "A-AER-0-100")
    reasons = ["FLOW_ZERO_DURING_COLLECT"]
    attempt, done, home, collect = capture(sc, mission, flight, arrive, target, "OPERATOR", holder, "REJECTED",
                                           reasons, 10, 0.083)
    blocked_from = collect[0] + 10
    sc.write(home + 1, AUTO, sc.change("lease", lease, "LEASE_RELEASED", state="RELEASED"))
    screening(sc, done + 4, attempt, "REJECTED", reasons, inv)
    a2 = analysis(sc, "analysis:2", [sc.uid("window:1")], "AMBIGUOUS_OR_OPTIONAL_MIXTURE",
                  [cand("S02", 0.94, "SURVIVES"), cand("S01", 1.07, "SURVIVES"), cand("S01+S02", 0.91, "SURVIVES"),
                   cand("S03", 9.8, "REJECTED", "RESIDUAL_ABOVE_CUTOFF"), UNKNOWN_ROW],
                  own(fixture("analysis"))["learned_order"],
                  exclusions=[{"reading_window": sc.uid("window:drone"), "reason": "FLOW_FAULT"}],
                  text="The drone SO2 sample was rejected because its tube flow stopped, so S01 and S02 both still fit.")
    pf = own(fixture("proposal")); pf["analysis_id"] = a2["id"]
    sc.command(done + 6, "run_analysis", AUTO, a2, sc.entry("proposal", sc.uid("proposal:2"), pf, "PROPOSAL_SAVED"),
               sc.change("investigation", inv, "INVESTIGATION_ANALYSED", state="ANALYSED",
                         analysis_ids=[sc.uid("analysis:1"), a2["id"]]))
    anomaly(sc, plume + 300, plume)
    case = case_story(sc, done + 15, inv, "REJECTED_FOR_USE",
                      ["S01 and S02 both still fit", "Unregistered source upwind"],
                      ["SO2 sample rejected: flow stopped during collection", "Synthetic teaching data",
                       "Legal suitability not assessed"], [ref(sc, "analysis", "analysis:2")], done + 65)
    sc.command(done + 70, "retrain_model", AUTO, status="REJECTED", reason_code="NO_NEW_LESSONS",
               reason_text="SO2 rejected, not a lesson")
    board(sc, done + 85, case, inv, "SAMPLE_INVALID", ["AREA-E"], "East colony",
          "The sample could not be used. The investigation continues.",
          "The SO2 screening was rejected for use; CO2 alone cannot separate S01 and S02.")
    board_sms(sc, done + 87, "FuelPrint DEMO (simulated): the sample near East colony could not be used. "
                             "The investigation continues.")
    frames(sc, flight, n2_plume(plume),
           lambda t, pos: 117.0 if pos[2] > 30 and abs(pos[1] - 100) < 3 else (60.0 if pos[2] > 20 else 5.0),
           drone_flags=lambda t: ["FLOW_FAULT"] if blocked_from <= t <= collect[1] else ["OK"],
           drone_flow=lambda t: 0.0 if blocked_from <= t <= collect[1] else 0.5)
    return sc


def sc3():
    sc = Scenario("SC3", "SC3-ABOVE-THE-BOXES", 440)
    sc.write(0, AUTO, *model_versions(sc), settings_entry(sc))
    bg = window(sc, "window:bg", 0, 30, 0.2, None)
    sc.write(30, AUTO, bg)
    inv = sc.uid("investigation")
    survey = sc.uid("survey")
    inf = own(fixture("investigation"))
    inf.update({"trigger_id": None, "survey_id": survey, "state": "OPEN", "analysis_ids": [], "warned_area_ids": []})
    sc.command(45, "schedule_survey", ANALYST, sc.entry("investigation", inv, inf, "INVESTIGATION_OPENED"))
    target = (260.0, 160.0, 45.0)
    mission, flight, lease, arrive = mission_story(sc, 50, ANALYST, None, survey, target, "AUTOPILOT", "autopilot",
                                                   60, 122, inv, "A-AER-260-160")
    attempt, done, home, collect = capture(sc, mission, flight, arrive, target, "AUTOPILOT", "autopilot", "VALID", [],
                                           30, 0.25)
    sc.write(home + 1, AUTO, sc.change("lease", lease, "LEASE_RELEASED", state="RELEASED"))
    screening(sc, done + 4, attempt, "VALID", [], inv)
    w_drone = window(sc, "window:drone", collect[0], collect[1], 42.6, bg["id"])
    w_drone["fields"]["sensor_or_vehicle_id"] = DRONE_ID
    sc.write(done, AUTO, w_drone)
    a1 = analysis(sc, "analysis:1", [w_drone["id"]], "AMBIGUOUS_OR_OPTIONAL_MIXTURE",
                  [cand("S03", 1.02, "SURVIVES"), cand("S03+S04", 1.01, "SURVIVES"),
                   cand("S01", 8.7, "REJECTED", "RESIDUAL_ABOVE_CUTOFF"),
                   cand("S02", 7.9, "REJECTED", "RESIDUAL_ABOVE_CUTOFF"), UNKNOWN_ROW],
                  [{"explanation_id": "S03", "rank": 1, "order_score": 1.12},
                   {"explanation_id": "S03+S04", "rank": 2, "order_score": 1.05}],
                  exclusions=[],
                  text="No box saw this plume: it passed above every 3 m inlet. The drone reading at 45 m fits S03 "
                       "alone and S03 with S04 equally well.",
                  history={"S01": 1, "S02": 3, "S03": 0, "S04": 0, "S05": 0, "S06": 0})
    pf = own(fixture("proposal"))
    pf.update({"analysis_id": a1["id"], "result": "NO_USEFUL_MEASUREMENT", "best_action_id": None,
               "cheaper_action_id": None})
    for action in pf["actions"]:
        action["feasible"] = False
        action["rejection_reason"] = action["rejection_reason"] or "NO_BENEFIT"
        action["complete_minutes"] = None
    sc.command(done + 6, "run_analysis", AUTO, a1, sc.entry("proposal", sc.uid("proposal:1"), pf, "PROPOSAL_SAVED"),
               sc.change("investigation", inv, "INVESTIGATION_ANALYSED", state="ANALYSED", analysis_ids=[a1["id"]]))
    sc.command(done + 7, "write_wording", AUTO,
               wording(sc, "wording:analysis:1", ref(sc, "analysis", "analysis:1"), "EXPLANATION",
                       "The plume passed above every box, so no alarm fired. The drone reading fits two explanations "
                       "equally well. A missed plume is never a clean verdict."))
    case = case_story(sc, done + 15, inv, "INCONCLUSIVE",
                      ["S03 alone", "S03 together with S04", "Unregistered source"],
                      ["Two explanations fit equally well", "Synthetic teaching data", "Legal suitability not assessed"],
                      [ref(sc, "analysis", "analysis:1")], done + 65)
    sc.command(done + 70, "retrain_model", AUTO, status="REJECTED", reason_code="NO_NEW_LESSONS",
               reason_text="inconclusive, not a lesson")
    board(sc, done + 85, case, inv, "MORE_INVESTIGATION", [], "No area", "No area was warned. Monitoring continues.",
          "The drone reading fits two explanations; more investigation is needed.")
    frames(sc, flight, lambda label, t: 0.0,
           lambda t, pos: 47.6 if pos[2] > 40 and pos[0] > 200 else (12.0 if pos[2] > 30 else 5.0))
    return sc


if __name__ == "__main__":
    for make in (sc1, sc2, sc3):
        make().save()
