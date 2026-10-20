package fuelprint.science.plan;

import fuelprint.api.Analysis;
import fuelprint.api.Enu;
import fuelprint.api.PlanContext;
import fuelprint.api.Proposal;
import fuelprint.api.ProposalAction;
import fuelprint.api.ProposalResult;
import fuelprint.api.VehiclePose;
import fuelprint.api.VehicleState;
import fuelprint.api.Velocity;
import fuelprint.api.WindNow;
import fuelprint.science.rank.CompatRanker;
import fuelprint.science.rank.Cutoff;
import fuelprint.science.rank.KernelWindows;
import fuelprint.science.rank.ScienceRanker;
import fuelprint.science.rank.WorldProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IntervalPlannerTest {

    private static final WindNow WIND = KernelWindows.WIND;

    static List<VehicleState> vehicles(Double vanWh, Double droneWh) {
        // Both start at the depot on the south road under the central vertical road.
        return List.of(
                new VehicleState("v-van", "Van", "VAN", new VehiclePose(0, -350, 0, 0), new Velocity(0, 0, 0),
                        new Enu(0, -350, 3), vanWh, "READY", null, fuelprint.api.Pilot.NONE),
                new VehicleState("v-drone", "Drone", "DRONE", new VehiclePose(0, -350, 0, 0),
                        new Velocity(0, 0, 0), new Enu(0, -350, 0), droneWh, "READY", null,
                        fuelprint.api.Pilot.NONE));
    }

    private static PlanContext context(WindNow wind, List<VehicleState> vehicles) {
        return new PlanContext("run-1", "analysis-1", 600, wind, vehicles);
    }

    private static IntervalPlanner planner(KernelWindows k) {
        return new IntervalPlanner(k.engine, System::nanoTime, IntervalPlanner.DEFAULT_BUDGET_NANOS);
    }

    private static Map<String, ProposalAction> byId(Proposal p) {
        return p.actions().stream().collect(Collectors.toMap(ProposalAction::actionId, a -> a));
    }

    @Test
    void atMostEightActionsAndUnreachablePointIsRejectedForTheVan() {
        KernelWindows k = new KernelWindows().s02AtN2();
        CompatRanker.Result r = k.analyze();
        Proposal p = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, 64.0)),
                Optional.of(r.snapshot())).proposal();
        assertThat(p.actions()).hasSizeLessThanOrEqualTo(8);
        ProposalAction far = byId(p).get("A-GND-280-140");
        assertThat(far.feasible()).isFalse();
        assertThat(far.rejectionReason()).isEqualTo("UNREACHABLE");
        assertThat(far.vehicle()).isEqualTo("VAN");
        assertThat(far.score()).isNull();
        assertThat(far.target()).isEqualTo(new Enu(280, 140, 3));
    }

    /** S02 emitting, seen at N2 and N3: S01 and S02 both survive and the ground point (0,100,3) can separate them. */
    private static KernelWindows s02AtN2AndN3() {
        KernelWindows k = new KernelWindows();
        WorldProfile.Source s02 = k.source("S02");
        for (String node : List.of("N2", "N3")) {
            k.add(node, k.world.nodeInlets().get(node), s02.east(), s02.north(), s02.heightM(), 6, 300);
        }
        return k;
    }

    @Test
    void bestActionIsTheOneThatSeparatesTheSurvivors() {
        KernelWindows k = s02AtN2AndN3();
        CompatRanker.Result r = k.analyze();
        assertThat(r.snapshot().surviving()).extracting(s -> s.explanation().id())
                .containsExactlyInAnyOrder("S01", "S02");
        IntervalPlanner.Result result = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, 64.0)),
                Optional.of(r.snapshot()));
        Proposal p = result.proposal();
        assertThat(result.status()).isEqualTo(IntervalPlanner.Status.COMPLETE);
        assertThat(p.result()).isEqualTo(ProposalResult.BEST_ACTION);
        assertThat(p.bestActionId()).isEqualTo("A-AER-0-100");
        ProposalAction best = byId(p).get("A-AER-0-100");
        assertThat(best.score()).isPositive();
        assertThat(best.completeMinutes()).isPositive();
        assertThat(best.vehicle()).isEqualTo("DRONE");
        // Places that cannot separate the two, and background / QC, score 0 and never claim discrimination.
        assertThat(byId(p).get("A-GND-120-100").score()).isZero();
        assertThat(byId(p).get("A-BKG-DEPOT").score()).isZero();
        assertThat(byId(p).get("A-QC-INSTRUMENT").feasible()).isTrue();
    }

    @Test
    void bestPlusACheaperAlternative() {
        List<IntervalPlanner.Scored> scored = List.of(
                new IntervalPlanner.Scored("A-AER-0-100", 2.4, 9.5, 30.0),
                new IntervalPlanner.Scored("A-GND-120-100", 0.6, 8.0, 20.0),
                new IntervalPlanner.Scored("A-GND-0-100", 0.4, 12.0, 25.0),
                new IntervalPlanner.Scored("A-AER-120-100", 0.0, 9.0, 10.0));
        IntervalPlanner.Selection sel = IntervalPlanner.select(scored);
        assertThat(sel.bestActionId()).isEqualTo("A-AER-0-100");
        assertThat(sel.cheaperActionId()).isEqualTo("A-GND-120-100"); // next by order that uses less energy
        assertThat(IntervalPlanner.select(List.of(new IntervalPlanner.Scored("A", 1, 1, 1))).cheaperActionId())
                .isNull();
        assertThat(IntervalPlanner.select(List.of(new IntervalPlanner.Scored("A", 0, 1, 1))).bestActionId())
                .isNull();
    }

    @Test
    void singleSurvivorLeavesNothingToSeparate() {
        KernelWindows k = new KernelWindows().s02AtN2().s02AtAerial();
        CompatRanker.Result r = k.analyze();
        Proposal p = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, 64.0)),
                Optional.of(r.snapshot())).proposal();
        assertThat(p.result()).isEqualTo(ProposalResult.NO_USEFUL_MEASUREMENT);
        assertThat(p.bestActionId()).isNull();
        assertThat(p.cheaperActionId()).isNull();
    }

    @Test
    void allOverlappingPredictionsGiveNoUsefulMeasurement() {
        // With a very loose cutoff every surviving explanation's box is wide and they all overlap everywhere.
        KernelWindows k = new KernelWindows().s02AtN2();
        CompatRanker loose = new CompatRanker(k.engine, new Cutoff(1e12, 60, 58, "test", "test", "test"));
        CompatRanker.Result r = loose.analyze(k.input(), k.inlets());
        assertThat(r.snapshot().surviving().size()).isGreaterThanOrEqualTo(2);
        Proposal p = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, 64.0)),
                Optional.of(r.snapshot())).proposal();
        assertThat(p.result()).isEqualTo(ProposalResult.NO_USEFUL_MEASUREMENT);
        assertThat(p.actions()).filteredOn(ProposalAction::feasible)
                .allSatisfy(a -> assertThat(a.score()).isZero());
    }

    @Test
    void tieOrderIsHigherScoreThenShorterTimeThenLowerEnergyThenActionId() {
        List<IntervalPlanner.Scored> list = new ArrayList<>(List.of(
                new IntervalPlanner.Scored("A-4", 1.0, 5, 2),
                new IntervalPlanner.Scored("A-3", 1.0, 5, 1),
                new IntervalPlanner.Scored("A-2", 1.0, 5, 1),
                new IntervalPlanner.Scored("A-1", 1.0, 6, 0.5),
                new IntervalPlanner.Scored("A-0", 2.0, 9, 9)));
        list.sort(IntervalPlanner.ORDER);
        assertThat(list).extracting(IntervalPlanner.Scored::actionId)
                .containsExactly("A-0", "A-2", "A-3", "A-4", "A-1");
    }

    @Test
    void overTheTwoSecondBudgetTheResultIsPendingNotAFakeRecommendation() {
        KernelWindows k = new KernelWindows().s02AtN2();
        CompatRanker.Result r = k.analyze();
        AtomicLong now = new AtomicLong();
        IntervalPlanner slow = new IntervalPlanner(k.engine, () -> now.getAndAdd(1_500_000_000L),
                IntervalPlanner.DEFAULT_BUDGET_NANOS);
        IntervalPlanner.Result result = slow.plan(r.analysis(), context(WIND, vehicles(64.0, 64.0)),
                Optional.of(r.snapshot()));
        assertThat(result.status()).isEqualTo(IntervalPlanner.Status.PENDING_BUDGET_EXCEEDED);
        assertThat(result.proposal().result()).isEqualTo(ProposalResult.PENDING);
        assertThat(result.proposal().bestActionId()).isNull();
        assertThat(result.proposal().cheaperActionId()).isNull();
    }

    @Test
    void filtersKeepRejectedActionsWithReasons() {
        KernelWindows k = new KernelWindows().s02AtN2();
        CompatRanker.Result r = k.analyze();
        // Drone nearly empty: it cannot even fly there and back.
        Proposal low = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, 12.5)),
                Optional.of(r.snapshot())).proposal();
        assertThat(byId(low).get("A-AER-0-100").rejectionReason()).isEqualTo("NO_ENERGY_FOR_RETURN");
        // Enough to fly there and back, not enough for the full procedure.
        Proposal mid = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, 30.0)),
                Optional.of(r.snapshot())).proposal();
        assertThat(byId(mid).get("A-AER-0-100").rejectionReason()).isEqualTo("UNAFFORDABLE_PROCEDURE");
        // Unknown energy is not zero energy.
        Proposal unknown = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, null)),
                Optional.of(r.snapshot())).proposal();
        assertThat(byId(unknown).get("A-AER-0-100").rejectionReason()).isEqualTo("ENERGY_UNKNOWN");
        // The wind turned beyond what the analysis' nuisance members cover.
        Proposal turned = planner(k).plan(r.analysis(), context(new WindNow(300, 3), vehicles(64.0, 64.0)),
                Optional.of(r.snapshot())).proposal();
        assertThat(byId(turned).get("A-AER-0-100").rejectionReason()).isEqualTo("STALE_DEPENDENCY");
        assertThat(turned.result()).isEqualTo(ProposalResult.NO_USEFUL_MEASUREMENT);
        // No fit snapshot held for this analysis.
        Proposal missing = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, 64.0)), Optional.empty())
                .proposal();
        assertThat(byId(missing).get("A-AER-0-100").rejectionReason()).isEqualTo("STALE_DEPENDENCY");
        // No drone at all.
        Proposal noDrone = planner(k).plan(r.analysis(), context(WIND, vehicles(64.0, 64.0).subList(0, 1)),
                Optional.of(r.snapshot())).proposal();
        assertThat(byId(noDrone).get("A-AER-0-100").rejectionReason()).isEqualTo("NO_VEHICLE_AVAILABLE");
    }

    @Test
    void identicalPublicInputGivesAnIdenticalProposal() {
        KernelWindows a = new KernelWindows().s02AtN2();
        KernelWindows b = new KernelWindows().s02AtN2();
        CompatRanker.Result ra = a.analyze();
        CompatRanker.Result rb = b.analyze();
        Proposal pa = planner(a).plan(ra.analysis(), context(WIND, vehicles(64.0, 64.0)),
                Optional.of(ra.snapshot())).proposal();
        Proposal pb = planner(b).plan(rb.analysis(), context(WIND, vehicles(64.0, 64.0)),
                Optional.of(rb.snapshot())).proposal();
        assertThat(pa).isEqualTo(pb);
    }

    @Test
    void springBeansWorkTogetherThroughTheHeldSnapshot() {
        KernelWindows k = s02AtN2AndN3();
        ScienceRanker ranker = new ScienceRanker(k.ranker(), List.of(k.inlets()));
        SciencePlanner planner = new SciencePlanner(ranker);
        Analysis a = ranker.analyze(k.input());
        Proposal p = planner.plan(a, context(WIND, vehicles(64.0, 64.0)));
        assertThat(p.bestActionId()).isEqualTo("A-AER-0-100");
        assertThat(p.analysisId()).isEqualTo("analysis-1");
    }

    @Test
    void roadDistanceFollowsTheGraph() {
        RoadNetwork roads = RoadNetwork.of(new KernelWindows().world);
        assertThat(roads.distance(0, -350, 0, 100)).hasValueSatisfying(d -> assertThat(d).isCloseTo(450, within(1e-9)));
        assertThat(roads.distance(0, -350, 120, 100)).hasValueSatisfying(d -> assertThat(d).isCloseTo(570, within(1e-9)));
        assertThat(roads.distance(0, -350, 280, 140)).isEmpty();
    }
    @Test
    void realMeasurementSnapshotsNeverFallbackToAnInstantlySettledTargetWhenForecastIsMissing() {
        KernelWindows k=s02AtN2AndN3();
        var fitted=k.analyze();
        var old=fitted.snapshot();
        var actual=new fuelprint.science.rank.FitSnapshot(old.inputWindowIds(),old.wind(),old.cutoff(),old.entries(),
                old.gases(),old.surviving(),true);
        var result=planner(k).plan(fitted.analysis(),context(WIND,vehicles(64.0,64.0)),Optional.of(actual));
        assertThat(result.proposal().result()).isEqualTo(ProposalResult.NO_USEFUL_MEASUREMENT);
        assertThat(result.proposal().actions()).filteredOn(a -> a.kind().equals(IntervalPlanner.DISCRIMINATE)
                && a.target().equals(new Enu(0,100,35)))
                .allSatisfy(a -> assertThat(a.rejectionReason()).isEqualTo("PROSPECTIVE_INLET_HISTORY_UNAVAILABLE"));
    }
    @Test void anExpiredPlanCannotStartAnyTrajectoryForecast() {
        KernelWindows k=s02AtN2AndN3(); var fitted=k.analyze(); var old=fitted.snapshot();
        var actual=new fuelprint.science.rank.FitSnapshot(old.inputWindowIds(),old.wind(),old.cutoff(),old.entries(),
                old.gases(),old.surviving(),true);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        fuelprint.api.ProspectiveInlets forecast=(context,vehicle,target,expired)->{calls.incrementAndGet();return Optional.empty();};
        AtomicLong now=new AtomicLong();
        var planner=new IntervalPlanner(k.engine,()->now.getAndAdd(1_500_000_000L),IntervalPlanner.DEFAULT_BUDGET_NANOS,List.of(forecast));
        var result=planner.plan(fitted.analysis(),context(WIND,vehicles(64.0,64.0)),Optional.of(actual));
        assertThat(result.status()).isEqualTo(IntervalPlanner.Status.PENDING_BUDGET_EXCEEDED);
        assertThat(result.proposal().result()).isEqualTo(ProposalResult.PENDING);
        assertThat(calls.get()).isZero();
    }
}
