package fuelprint.science.plan;

import fuelprint.api.Analysis;
import fuelprint.api.Enu;
import fuelprint.api.Outcome;
import fuelprint.api.PlanContext;
import fuelprint.api.Proposal;
import fuelprint.api.ProposalAction;
import fuelprint.api.ProposalResult;
import fuelprint.api.VehicleState;
import fuelprint.api.WindNow;
import fuelprint.science.rank.FitEngine;
import fuelprint.science.rank.FitSnapshot;
import fuelprint.science.rank.Gas;
import fuelprint.science.rank.WorldProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * FP-PLAN-INTERVAL-1 (AI-3): at most 8 nominated actions, filtered first (no vehicle, unreachable, unsafe, no
 * energy for the return, stale dependencies, unaffordable full procedure; rejected actions are kept with their
 * reason), then scored for admitted discrimination actions as the minimum separation lower bound over materially
 * different surviving explanations divided by complete minutes. All zero gives NO_USEFUL_MEASUREMENT. Ties: higher
 * score, shorter time, lower energy, action id. Over the wall budget the result is pending (result null, no best
 * action), never a made-up recommendation.
 */
public final class IntervalPlanner {

    public static final long DEFAULT_BUDGET_NANOS = 2_000_000_000L;
    public static final String DISCRIMINATE = "DISCRIMINATE";
    public static final String BACKGROUND = "BACKGROUND";
    public static final String INSTRUMENT_QC = "INSTRUMENT_QC";
    public static final String VAN = "VAN";
    public static final String DRONE = "DRONE";

    /** Status of a planning run. */
    public enum Status { COMPLETE, PENDING_BUDGET_EXCEEDED }

    /** A planning result: the proposal, complete or pending, and energy per action (null when rejected). */
    public record Result(Status status, Proposal proposal, List<Double> energyWh) {
        public Result {
            energyWh = java.util.Collections.unmodifiableList(new ArrayList<>(energyWh));
        }
    }

    /** A nominated action before filtering. */
    record Nominated(String actionId, String kind, String vehicleClass, Enu target) {
    }

    /** An admitted action with its cost, used for scoring and tie order. */
    public record Scored(String actionId, double score, double minutes, double energyWh) {
    }

    /** Tie order: higher score, shorter time, lower energy, then action id. */
    public static final Comparator<Scored> ORDER = Comparator.comparingDouble(Scored::score).reversed()
            .thenComparingDouble(Scored::minutes)
            .thenComparingDouble(Scored::energyWh)
            .thenComparing(Scored::actionId);

    private final FitEngine engine;
    private final WorldProfile world;
    private final RoadNetwork roads;
    private final LongSupplier nanoClock;
    private final long budgetNanos;

    public IntervalPlanner(FitEngine engine, LongSupplier nanoClock, long budgetNanos) {
        this.engine = engine;
        this.world = engine.world();
        this.roads = RoadNetwork.of(world);
        this.nanoClock = nanoClock;
        this.budgetNanos = budgetNanos;
    }

    static List<Nominated> nominate(Enu vanPosition) {
        List<Nominated> list = new ArrayList<>();
        list.add(new Nominated("A-GND-0-100", DISCRIMINATE, VAN, new Enu(0, 100, 3)));
        list.add(new Nominated("A-GND-120-100", DISCRIMINATE, VAN, new Enu(120, 100, 3)));
        list.add(new Nominated("A-GND-280-140", DISCRIMINATE, VAN, new Enu(280, 140, 3)));
        list.add(new Nominated("A-AER-0-100", DISCRIMINATE, DRONE, new Enu(0, 100, 35)));
        list.add(new Nominated("A-AER-120-100", DISCRIMINATE, DRONE, new Enu(120, 100, 35)));
        list.add(new Nominated("A-BKG-DEPOT", BACKGROUND, VAN, vanPosition));
        list.add(new Nominated("A-QC-INSTRUMENT", INSTRUMENT_QC, VAN, vanPosition));
        return list;
    }

    public Result plan(Analysis analysis, PlanContext context, Optional<FitSnapshot> snapshot) {
        long deadline = nanoClock.getAsLong() + budgetNanos;
        Optional<VehicleState> van = vehicle(context, VAN);
        Optional<VehicleState> drone = vehicle(context, DRONE);
        Enu vanAt = van.map(v -> new Enu(v.pose().east(), v.pose().north(), 3)).orElse(null);
        List<Nominated> nominated = nominate(vanAt);
        boolean fitted = analysis.outcome() != Outcome.INVALID && analysis.outcome() != Outcome.NO_SIGNAL;

        List<ProposalAction> actions = new ArrayList<>();
        List<Double> energies = new ArrayList<>();
        List<Scored> scored = new ArrayList<>();
        boolean pending = false;
        for (Nominated a : nominated) {
            Optional<VehicleState> v = a.vehicleClass().equals(DRONE) ? drone : van;
            String reason = null;
            double travelS = 0;
            double procedureS = procedureSeconds(a.kind(), v.map(VehicleState::stage).orElse(null));
            if (v.isEmpty() || v.get().pose() == null || a.target() == null) {
                reason = "NO_VEHICLE_AVAILABLE";
            } else {
                Optional<Double> oneWay = travelSeconds(a, v.get());
                if (oneWay.isEmpty()) {
                    reason = "UNREACHABLE";
                } else {
                    travelS = oneWay.get();
                }
            }
            if (reason == null && unsafe(a)) {
                reason = "UNSAFE";
            }
            Double energy = null;
            if (reason == null) {
                Double available = v.get().energyWh();
                double reserve = world.vehicle("reserve_Wh");
                double travelWh = 2 * travelS * movingW(a.vehicleClass()) / 3600.0;
                double totalWh = travelWh + procedureS * stationaryW(a.vehicleClass()) / 3600.0;
                energy = totalWh;
                if (available == null) {
                    reason = "ENERGY_UNKNOWN";
                } else if (travelWh > available - reserve) {
                    reason = "NO_ENERGY_FOR_RETURN";
                } else if (a.kind().equals(DISCRIMINATE) && fitted && stale(snapshot, context.wind())) {
                    reason = "STALE_DEPENDENCY";
                } else if (totalWh > available - reserve) {
                    reason = "UNAFFORDABLE_PROCEDURE";
                }
            }
            if (reason != null) {
                actions.add(new ProposalAction(a.actionId(), a.kind(), a.vehicleClass(), a.target(), null, null,
                        false, reason));
                energies.add(null);
                continue;
            }
            double minutes = (2 * travelS + procedureS) / 60.0;
            Double score = 0.0;
            if (a.kind().equals(DISCRIMINATE) && fitted) {
                if (pending || nanoClock.getAsLong() > deadline) {
                    pending = true;
                    score = null;
                } else {
                    Optional<Double> s = discrimination(snapshot.orElseThrow(), a.target(), deadline);
                    if (s.isEmpty()) {
                        pending = true;
                        score = null;
                    } else {
                        score = s.get() / minutes;
                    }
                }
            }
            actions.add(new ProposalAction(a.actionId(), a.kind(), a.vehicleClass(), a.target(), score,
                    round(minutes), true, null));
            energies.add(energy);
            if (score != null && a.kind().equals(DISCRIMINATE)) {
                scored.add(new Scored(a.actionId(), score, minutes, energy));
            }
        }
        if (pending || nanoClock.getAsLong() > deadline) {
            return new Result(Status.PENDING_BUDGET_EXCEEDED,
                    new Proposal(context.analysisId(), actions, ProposalResult.PENDING, null, null), energies);
        }
        Selection sel = select(scored);
        if (sel.bestActionId() == null) {
            return new Result(Status.COMPLETE, new Proposal(context.analysisId(), actions,
                    ProposalResult.NO_USEFUL_MEASUREMENT, null, null), energies);
        }
        return new Result(Status.COMPLETE, new Proposal(context.analysisId(), actions, ProposalResult.BEST_ACTION,
                sel.bestActionId(), sel.cheaperActionId()), energies);
    }

    /** Best action and a cheaper alternative (both null when no action has a score above 0). */
    public record Selection(String bestActionId, String cheaperActionId) {
    }

    /**
     * Picks the best by {@link #ORDER} among actions with a positive score, and the cheaper alternative: the best of
     * the remaining useful actions that use less energy than the best (null if there is none).
     */
    public static Selection select(List<Scored> scored) {
        List<Scored> useful = scored.stream().filter(s -> s.score() > 0).sorted(ORDER).toList();
        if (useful.isEmpty()) {
            return new Selection(null, null);
        }
        Scored best = useful.get(0);
        String cheaper = useful.stream().skip(1).filter(s -> s.energyWh() < best.energyWh())
                .findFirst().map(Scored::actionId).orElse(null);
        return new Selection(best.actionId(), cheaper);
    }

    /** Minimum separation over materially different surviving explanations; empty when over budget. */
    private Optional<Double> discrimination(FitSnapshot snapshot, Enu target, long deadline) {
        List<FitSnapshot.Surviving> surviving = snapshot.surviving();
        if (surviving.size() < 2) {
            return Optional.of(0.0);
        }
        List<Gas> gases = IntervalScorer.gasesOf(snapshot);
        List<Optional<IntervalScorer.Box>> boxes = new ArrayList<>();
        for (FitSnapshot.Surviving s : surviving) {
            if (nanoClock.getAsLong() > deadline) {
                return Optional.empty();
            }
            boxes.add(IntervalScorer.outerBox(engine, snapshot, s, target, gases));
        }
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < surviving.size(); i++) {
            for (int j = i + 1; j < surviving.size(); j++) {
                if (surviving.get(i).explanation().sources().equals(surviving.get(j).explanation().sources())) {
                    continue;
                }
                double sep = boxes.get(i).isEmpty() || boxes.get(j).isEmpty() ? 0.0
                        : IntervalScorer.separation(boxes.get(i).get(), boxes.get(j).get(),
                        IntervalScorer.UNMODELLED_ERROR);
                min = Math.min(min, sep);
            }
        }
        return Optional.of(Double.isFinite(min) ? min : 0.0);
    }

    /** The analysis was made for a wind the nuisance members no longer cover, or its fit is not held. */
    private static boolean stale(Optional<FitSnapshot> snapshot, WindNow now) {
        if (snapshot.isEmpty()) {
            return true;
        }
        if (now == null) {
            return false;
        }
        WindNow then = snapshot.get().wind();
        double d = Math.abs(((now.fromDeg() - then.fromDeg()) % 360 + 540) % 360 - 180);
        double ratio = now.speedMS() / then.speedMS();
        return d > 10 + 1e-9 || ratio < 0.8 - 1e-9 || ratio > 1.2 + 1e-9;
    }

    private Optional<Double> travelSeconds(Nominated a, VehicleState v) {
        Enu t = a.target();
        if (!a.kind().equals(DISCRIMINATE)) {
            return Optional.of(0.0); // background and QC run where the vehicle already is
        }
        if (a.vehicleClass().equals(DRONE)) {
            double lo = world.raw().path("vehicle").path("aerial_xy_m").path(0).asDouble();
            double hi = world.raw().path("vehicle").path("aerial_xy_m").path(1).asDouble();
            double zLo = world.raw().path("vehicle").path("aerial_z_m").path(0).asDouble();
            double zHi = world.raw().path("vehicle").path("aerial_z_m").path(1).asDouble();
            if (t.east() < lo || t.east() > hi || t.north() < lo || t.north() > hi
                    || t.up() < zLo || t.up() > zHi) {
                return Optional.empty();
            }
            double d = Math.sqrt(Math.pow(t.east() - v.pose().east(), 2) + Math.pow(t.north() - v.pose().north(), 2)
                    + Math.pow(t.up() - v.pose().up(), 2));
            return Optional.of(d / world.vehicle("aerial_m_s"));
        }
        return roads.distance(v.pose().east(), v.pose().north(), t.east(), t.north())
                .map(d -> d / world.vehicle("ground_m_s"));
    }

    /** Inside a source footprint (plus clearance) and below its roof plus clearance. */
    private boolean unsafe(Nominated a) {
        Enu t = a.target();
        double clearance = a.vehicleClass().equals(DRONE) ? world.clearance("aerial_obstacle_m")
                : world.clearance("ground_obstacle_m");
        clearance += world.clearance("vehicle_radius_m");
        for (WorldProfile.Source s : world.sources()) {
            boolean inside = Math.abs(t.east() - s.east()) <= s.footprintEastM() / 2 + clearance
                    && Math.abs(t.north() - s.north()) <= s.footprintNorthM() / 2 + clearance;
            if (inside && t.up() <= s.roofM() + clearance) {
                return true;
            }
        }
        return false;
    }

    private double procedureSeconds(String kind, String stage) {
        double cold = stage != null && stage.toUpperCase(Locale.ROOT).contains("COLD")
                ? world.procedureS("cold_warmup_additional") : 0;
        return switch (kind) {
            case BACKGROUND -> world.procedureS("setup") + world.procedureS("background") + world.procedureS("review")
                    + cold;
            case INSTRUMENT_QC -> world.procedureS("setup") + world.procedureS("purge")
                    + world.procedureS("minimum_settle") + world.procedureS("review") + cold;
            default -> world.procedureS("setup") + world.procedureS("background") + world.procedureS("purge")
                    + world.procedureS("minimum_settle") + world.procedureS("online_valid_collection")
                    + world.procedureS("minimum_recovery") + world.procedureS("review") + cold;
        };
    }

    /** Power while moving: base + instruments + speed squared (world-profile "speed_m_s squared W"). */
    private double movingW(String vehicleClass) {
        double v = vehicleClass.equals(DRONE) ? world.vehicle("aerial_m_s") : world.vehicle("ground_m_s");
        return stationaryW(vehicleClass) + v * v;
    }

    private double stationaryW(String vehicleClass) {
        return (vehicleClass.equals(DRONE) ? world.vehicle("hover_W") : world.vehicle("ground_W"))
                + world.vehicle("instruments_W");
    }

    private static Optional<VehicleState> vehicle(PlanContext context, String vehicleClass) {
        return context.vehicles().stream()
                .filter(v -> classOf(v).equals(vehicleClass))
                .sorted(Comparator.comparing(VehicleState::vehicleId, Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst();
    }

    static String classOf(VehicleState v) {
        String k = (v.kind() == null ? "" : v.kind()).toUpperCase(Locale.ROOT);
        if (k.contains("DRONE") || k.contains("AERIAL") || k.contains("UAV")) {
            return DRONE;
        }
        if (k.contains("VAN") || k.contains("GROUND") || k.contains("ROVER")) {
            return VAN;
        }
        return "OTHER";
    }

    private static double round(double minutes) {
        return Math.round(minutes * 100) / 100.0;
    }
}
