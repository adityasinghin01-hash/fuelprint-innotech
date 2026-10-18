package fuelprint.science.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import fuelprint.api.CaptureInput;
import fuelprint.api.ChannelStatus;
import fuelprint.api.ChannelVerdict;
import fuelprint.api.Origin;
import fuelprint.api.Reading;
import fuelprint.api.Stage;
import org.junit.jupiter.api.Test;

class TeachCaptureRulesTest {

    private final TeachCaptureRules rules = new TeachCaptureRules();

    private static Stage stage(String name, double start, double end) {
        return new Stage(name, start, end);
    }

    private static Stage running(String name, double start) {
        return new Stage(name, start, null);
    }

    private static Reading r(String channel, double t, Double v, String... q) {
        return new Reading("veh", channel, v, v, channel.contains("flow") ? "L_min" : "ppb", t, List.of(q),
                Origin.SYNTHETIC_TEACHING);
    }

    private static List<Stage> stages(double settleS) {
        return List.of(stage("PURGE", 0, 30), stage("SETTLE", 30, 30 + settleS),
                stage("COLLECT", 30 + settleS, 60 + settleS));
    }

    /** Readings for the collection stage at 1 Hz. {@code faultAt} is the collection second the flow fails (or -1). */
    private static List<Reading> readings(String gas, String flow, double targetFlow, double start, int faultAt) {
        List<Reading> out = new ArrayList<>();
        for (int k = 0; k < 30; k++) {
            double t = start + k;
            boolean fault = faultAt >= 0 && k >= faultAt;
            out.add(fault ? r(gas, t, 40.0, "FLOW_FAULT") : r(gas, t, 40.0, "OK"));
            out.add(fault ? r(flow, t, 0.0, "FLOW_FAULT") : r(flow, t, targetFlow, "OK"));
        }
        return out;
    }

    private ChannelVerdict judge(String channel, List<Stage> st, List<Reading> rs) {
        return rules.judge(new CaptureInput("a1", "TEACH-NODE-ONLINE-2", channel, st, rs));
    }

    @Test
    void aCleanCollectionIsValidWith30SecondsAndTheActualVolume() {
        ChannelVerdict so2 = judge("SO2_ppb", stages(120), readings("SO2_ppb", "SO2_flow_L_min", 0.5, 150, -1));
        assertThat(so2.status()).isEqualTo(ChannelStatus.VALID);
        assertThat(so2.reasons()).isEmpty();
        assertThat(so2.validSeconds()).isEqualTo(30.0);
        assertThat(so2.volumeL()).isCloseTo(0.25, within(1e-9));
        ChannelVerdict co2 = judge("CO2_ppm", stages(120), readings("CO2_ppm", "CO2_flow_L_min", 0.3, 150, -1));
        assertThat(co2.status()).isEqualTo(ChannelStatus.VALID);
        assertThat(co2.volumeL()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void theSc2FaultRejectsSo2AndKeepsCo2Valid() {
        List<Reading> both = new ArrayList<>(readings("SO2_ppb", "SO2_flow_L_min", 0.5, 150, 10));
        both.addAll(readings("CO2_ppm", "CO2_flow_L_min", 0.3, 150, -1));
        ChannelVerdict so2 = judge("SO2_ppb", stages(120), both);
        assertThat(so2.status()).isEqualTo(ChannelStatus.REJECTED);
        assertThat(so2.reasons()).contains("FLOW_FAULT");
        assertThat(so2.validSeconds()).isEqualTo(10.0);
        assertThat(so2.volumeL()).isCloseTo(0.083, within(0.0005));
        ChannelVerdict co2 = judge("CO2_ppm", stages(120), both);
        assertThat(co2.status()).isEqualTo(ChannelStatus.VALID);
        assertThat(co2.validSeconds()).isEqualTo(30.0);
    }

    @Test
    void aSettleShorterThan120SecondsIsRejected() {
        ChannelVerdict v = judge("SO2_ppb", stages(100), readings("SO2_ppb", "SO2_flow_L_min", 0.5, 130, -1));
        assertThat(v.status()).isEqualTo(ChannelStatus.REJECTED);
        assertThat(v.reasons()).contains("SETTLE_INCOMPLETE");
        ChannelVerdict none = judge("SO2_ppb", List.of(stage("COLLECT", 150, 180)),
                readings("SO2_ppb", "SO2_flow_L_min", 0.5, 150, -1));
        assertThat(none.status()).isEqualTo(ChannelStatus.REJECTED);
        assertThat(none.reasons()).contains("SETTLE_MISSING");
    }

    @Test
    void modelledSettleErrorIsTenToTheMinusTOverT90() {
        assertThat(TeachCaptureRules.modelledError(60, 60)).isCloseTo(0.1, within(1e-12));
        assertThat(TeachCaptureRules.modelledError(120, 60)).isCloseTo(0.01, within(1e-12));
        assertThat(TeachCaptureRules.modelledError(79, 60)).isLessThan(0.05);
        assertThat(TeachCaptureRules.modelledError(70, 60)).isGreaterThan(0.05);
    }

    @Test
    void aFlowMoreThanFivePercentOffMakesThoseSecondsInvalid() {
        List<Reading> rs = new ArrayList<>(readings("SO2_ppb", "SO2_flow_L_min", 0.5, 150, -1));
        rs.removeIf(x -> x.channel().equals("SO2_flow_L_min") && x.simTimeS() == 160.0);
        rs.add(r("SO2_flow_L_min", 160.0, 0.5 * 1.06, "OK"));
        ChannelVerdict v = judge("SO2_ppb", stages(120), rs);
        assertThat(v.status()).isEqualTo(ChannelStatus.REJECTED);
        assertThat(v.reasons()).contains("FLOW_OUT_OF_RANGE");
        assertThat(v.validSeconds()).isEqualTo(19.0); // seconds 11 to 29: the longest consecutive stretch
        // 4 % off is inside the tolerance
        List<Reading> ok = new ArrayList<>(readings("SO2_ppb", "SO2_flow_L_min", 0.5, 150, -1));
        ok.removeIf(x -> x.channel().equals("SO2_flow_L_min") && x.simTimeS() == 160.0);
        ok.add(r("SO2_flow_L_min", 160.0, 0.5 * 1.04, "OK"));
        assertThat(judge("SO2_ppb", stages(120), ok).status()).isEqualTo(ChannelStatus.VALID);
    }

    @Test
    void disjointGoodStretchesAreNeverSpliced() {
        List<Reading> rs = new ArrayList<>(readings("SO2_ppb", "SO2_flow_L_min", 0.5, 150, -1));
        rs.removeIf(x -> x.simTimeS() == 165.0); // a gap in the middle: 15 s + 14 s
        ChannelVerdict v = judge("SO2_ppb", stages(120), rs);
        assertThat(v.status()).isEqualTo(ChannelStatus.REJECTED);
        assertThat(v.validSeconds()).isEqualTo(15.0);
        assertThat(v.reasons()).contains("FEWER_THAN_30_CONSECUTIVE_VALID_SECONDS");
    }

    @Test
    void aShortRecoveryLimitsTheChannelButALongOneDoesNot() {
        List<Reading> rs = readings("SO2_ppb", "SO2_flow_L_min", 0.5, 150, -1);
        List<Stage> shortRec = new ArrayList<>(stages(120));
        shortRec.add(stage("RECOVERY", 180, 240));
        ChannelVerdict limited = judge("SO2_ppb", shortRec, rs);
        assertThat(limited.status()).isEqualTo(ChannelStatus.LIMITED);
        assertThat(limited.reasons()).containsExactly("RECOVERY_INCOMPLETE");
        List<Stage> longRec = new ArrayList<>(stages(120));
        longRec.add(stage("RECOVERY", 180, 305));
        assertThat(judge("SO2_ppb", longRec, rs).status()).isEqualTo(ChannelStatus.VALID);
    }

    @Test
    void noCollectStageIsRejectedWithUnknownNumbers() {
        ChannelVerdict v = judge("SO2_ppb", List.of(stage("PURGE", 0, 30)), List.of());
        assertThat(v.status()).isEqualTo(ChannelStatus.REJECTED);
        assertThat(v.validSeconds()).isNull();
        assertThat(v.volumeL()).isNull();
    }

    @Test
    void aCollectionStillRunningIsLimitedNotRejected() {
        List<Stage> st = List.of(stage("SETTLE", 30, 150), running("COLLECT", 150));
        List<Reading> rs = new ArrayList<>(readings("SO2_ppb", "SO2_flow_L_min", 0.5, 150, -1)).subList(0, 20);
        ChannelVerdict v = judge("SO2_ppb", st, rs);
        assertThat(v.status()).isEqualTo(ChannelStatus.LIMITED);
        assertThat(v.reasons()).containsExactly("COLLECTION_IN_PROGRESS");
    }
}
