package fuelprint.field.vehicle;

import static fuelprint.field.TestProfile.KINEMATICS;
import static fuelprint.field.TestProfile.OBSTACLES;
import static fuelprint.field.TestProfile.PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import fuelprint.field.geo.Area;
import fuelprint.field.geo.Box;
import fuelprint.field.geo.Obstacles;
import fuelprint.field.geo.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KinematicsTest {

    static final double DT = 0.05;

    static Drone droneAt(double e, double n, double u) {
        return new Drone("d", "DRONE-1", new Vec3(e, n, u), 64);
    }

    @Test
    void speedNeverExceeds3MetresPerSecondEvenWhenAskedFor10() {
        Drone d = droneAt(-300, -300, 40);
        double max = 0;
        for (int i = 0; i < 400; i++) {
            KINEMATICS.step(d, new Vec3(10, 10, 10), DT, true);
            max = Math.max(max, d.speed());
        }
        assertThat(max).isLessThanOrEqualTo(3.0 + 1e-9).isGreaterThan(2.99);
    }

    @Test
    void velocityChangesAtMost1MetrePerSecondPerSecond() {
        Drone d = droneAt(-300, -300, 40);
        Vec3 previous = d.velocity();
        int stepsToFullSpeed = -1;
        for (int i = 0; i < 200; i++) {
            Vec3 wanted = i < 100 ? new Vec3(3, 0, 0) : new Vec3(-3, 0, 0);
            KINEMATICS.step(d, wanted, DT, true);
            assertThat(d.velocity().minus(previous).norm()).isLessThanOrEqualTo(1.0 * DT + 1e-9);
            previous = d.velocity();
            if (stepsToFullSpeed < 0 && d.speed() >= 3.0 - 1e-9) {
                stepsToFullSpeed = i + 1;
            }
        }
        assertThat(stepsToFullSpeed * DT).as("0 to 3 m/s takes 3 s").isCloseTo(3.0, within(DT + 1e-9));
    }

    @Test
    void aLongStepIsSplitSoTheAccelerationLimitStillHolds() {
        Drone d = droneAt(-300, -300, 40);
        KINEMATICS.step(d, new Vec3(3, 0, 0), 0.75, true);
        assertThat(d.speed()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void eastAndNorthStayWithin450Metres() {
        Drone d = droneAt(440, -440, 40);
        for (int i = 0; i < 400; i++) {
            KINEMATICS.step(d, new Vec3(3, -3, 0), DT, true);
            assertThat(d.position().e()).isBetween(-450.0, 450.0);
            assertThat(d.position().n()).isBetween(-450.0, 450.0);
        }
        assertThat(d.position().e()).isEqualTo(450.0);
        assertThat(d.position().n()).isEqualTo(-450.0);
        assertThat(d.velocity().e()).isZero();
    }

    @Test
    void heightStaysBetween2And80Metres() {
        Drone d = droneAt(-300, -300, 70);
        for (int i = 0; i < 400; i++) {
            KINEMATICS.step(d, new Vec3(0, 0, 3), DT, true);
            assertThat(d.position().u()).isBetween(2.0, 80.0);
        }
        assertThat(d.position().u()).isEqualTo(80.0);
        for (int i = 0; i < 1200; i++) {
            KINEMATICS.step(d, new Vec3(0, 0, -3), DT, true);
            assertThat(d.position().u()).isBetween(2.0, 80.0);
        }
        assertThat(d.position().u()).isEqualTo(2.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.05, 0.75, 5.0})
    void noTunnellingThroughAStackAtFullSpeed(double dt) {
        // S02 stack: a 4 m column at (-100, 100) up to 35 m; the drone flies east at 20 m height into it.
        Drone d = droneAt(-130, 100, 20);
        double stackWestFace = -102.0;
        for (double t = 0; t < 60; t += dt) {
            KINEMATICS.step(d, new Vec3(3, 0, 0), dt, true);
            assertThat(OBSTACLES.blocked(d.position())).isFalse();
            assertThat(d.position().e()).isLessThanOrEqualTo(stackWestFace - PROFILE.obstacleMarginM());
        }
        assertThat(d.position().e()).as("it got right up to the clearance")
                .isGreaterThan(stackWestFace - PROFILE.obstacleMarginM() - 0.2);
    }

    @Test
    void noTunnellingThroughAThinWallWithAHugeStep() {
        Obstacles wall = new Obstacles(java.util.List.of(new Box("W", "STACK", 0, 0.1, -50, 50, 0, 80)), 2.5);
        Kinematics k = new Kinematics(3, 1, new Area(-450, 450, 2, 80), wall, new Energy(160, 10));
        Drone d = droneAt(-10, 0, 20);
        d.setEnergyWh(64);
        for (int i = 0; i < 40; i++) {
            k.step(d, new Vec3(3, 0, 0), 1.0, true);
            assertThat(d.position().e()).isLessThan(-2.5);
        }
    }

    @Test
    void slidesAlongAWallInsteadOfStopping() {
        Drone d = droneAt(-130, 100, 20);
        for (int i = 0; i < 1200; i++) {
            KINEMATICS.step(d, new Vec3(2, 0, 2), DT, true);
            assertThat(OBSTACLES.blocked(d.position())).isFalse();
        }
        assertThat(d.position().u()).as("climbs while east is stopped").isGreaterThan(37.5);
        assertThat(d.position().e()).as("then passes over the stack").isGreaterThan(-95.0);
    }

    @Test
    void energyUseMatchesTheFormula() {
        Energy energy = new Energy(PROFILE);
        assertThat(energy.powerW(0)).isEqualTo(170.0);
        assertThat(energy.powerW(3)).isEqualTo(179.0);
        assertThat(energy.wattHours(2, 3600)).isEqualTo(174.0);

        Drone hover = droneAt(-300, -300, 40);
        for (int i = 0; i < 2000; i++) {
            KINEMATICS.step(hover, Vec3.ZERO, DT, true);
        }
        assertThat(64 - hover.energyWh()).isCloseTo(170.0 * 100 / 3600, within(1e-9));

        Drone cruise = droneAt(-300, -300, 40);
        double expected = 0;
        for (int i = 0; i < 400; i++) {
            KINEMATICS.step(cruise, new Vec3(3, 0, 0), DT, true);
            expected += (170 + cruise.speed() * cruise.speed()) * DT / 3600;
        }
        assertThat(64 - cruise.energyWh()).isCloseTo(expected, within(1e-9));
    }

    @Test
    void aParkedDroneUsesNoEnergy() {
        Drone d = droneAt(0, 0, 2);
        KINEMATICS.step(d, Vec3.ZERO, 10, false);
        assertThat(d.energyWh()).isEqualTo(64.0);
    }

    @Test
    void brakeStopsAtOnceAndTheInletFollowsTheHeading() {
        Drone d = droneAt(-300, -300, 40);
        for (int i = 0; i < 100; i++) {
            KINEMATICS.step(d, new Vec3(3, 0, 0), DT, true);
        }
        assertThat(d.yawDeg()).isCloseTo(90.0, within(1e-9));
        Vec3 inlet = d.inlet();
        assertThat(inlet.e() - d.position().e()).isCloseTo(0.4, within(1e-9));
        assertThat(inlet.n() - d.position().n()).isCloseTo(0.0, within(1e-9));
        assertThat(inlet.u() - d.position().u()).isCloseTo(-0.4, within(1e-9));
        d.brake();
        assertThat(d.speed()).isZero();
    }
}
