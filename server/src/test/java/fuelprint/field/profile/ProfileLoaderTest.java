package fuelprint.field.profile;

import static fuelprint.field.TestProfile.OBSTACLES;
import static fuelprint.field.TestProfile.PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import fuelprint.field.geo.Box;
import fuelprint.field.geo.Vec3;
import org.junit.jupiter.api.Test;

class ProfileLoaderTest {

    @Test
    void vehicleLimitsComeFromTheWorldProfile() {
        assertThat(PROFILE.maxSpeedMS()).isEqualTo(3.0);
        assertThat(PROFILE.maxAccelMS2()).isEqualTo(1.0);
        assertThat(PROFILE.xyMin()).isEqualTo(-450.0);
        assertThat(PROFILE.xyMax()).isEqualTo(450.0);
        assertThat(PROFILE.zMin()).isEqualTo(2.0);
        assertThat(PROFILE.zMax()).isEqualTo(80.0);
        assertThat(PROFILE.usableWh()).isEqualTo(64.0);
        assertThat(PROFILE.reserveWh()).isEqualTo(12.0);
        assertThat(PROFILE.hoverW()).isEqualTo(160.0);
        assertThat(PROFILE.instrumentsW()).isEqualTo(10.0);
        assertThat(PROFILE.clearanceM()).isEqualTo(2.0);
        assertThat(PROFILE.vehicleRadiusM()).isEqualTo(0.5);
    }

    @Test
    void procedureTimesComeFromTheWorldProfile() {
        WorldProfile.Procedure p = PROFILE.procedure();
        assertThat(p.setupS()).isEqualTo(30);
        assertThat(p.backgroundS()).isEqualTo(30);
        assertThat(p.purgeS()).isEqualTo(30);
        assertThat(p.minimumSettleS()).isEqualTo(120);
        assertThat(p.validCollectionS()).isEqualTo(30);
        assertThat(p.minimumRecoveryS()).isEqualTo(120);
        assertThat(p.reviewS()).isEqualTo(30);
    }

    @Test
    void sixShedsAndSixStacksAtTheirReleaseHeights() {
        assertThat(PROFILE.obstacles().stream().filter(b -> b.kind().equals("SHED"))).hasSize(6)
                .allSatisfy(b -> {
                    assertThat(b.maxE() - b.minE()).isEqualTo(80.0);
                    assertThat(b.maxN() - b.minN()).isEqualTo(60.0);
                    assertThat(b.maxU()).isEqualTo(8.0);
                });
        Box s03 = PROFILE.obstacles().stream().filter(b -> b.id().equals("S03-STACK")).findFirst().orElseThrow();
        assertThat(s03.maxU()).isEqualTo(45.0);
        assertThat((s03.minE() + s03.maxE()) / 2).isEqualTo(180.0);
        assertThat((s03.minN() + s03.maxN()) / 2).isEqualTo(160.0);
    }

    @Test
    void contextBuildingsAre8mBoxesAndThePadIsCleared() {
        assertThat(PROFILE.contextBuildingsLoaded()).isEqualTo(991);
        assertThat(PROFILE.contextBuildingsCleared()).isPositive();
        long context = PROFILE.obstacles().stream().filter(b -> b.kind().equals("CONTEXT")).count();
        assertThat(context).isEqualTo(991 - PROFILE.contextBuildingsCleared());
        assertThat(PROFILE.obstacles().stream().filter(b -> b.kind().equals("CONTEXT")))
                .allSatisfy(b -> assertThat(b.maxU()).isEqualTo(8.0));
        assertThat(OBSTACLES.blocked(PROFILE.depot())).isFalse();
        assertThat(OBSTACLES.blocked(new Vec3(0, 0, 35))).isFalse();
        assertThat(OBSTACLES.blocked(new Vec3(0, 100, 35))).as("SC1 target").isFalse();
    }
}
