package fuelprint.field.geo;

import static fuelprint.field.TestProfile.OBSTACLES;
import static fuelprint.field.TestProfile.PLANNER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PathPlannerTest {

    static void assertSafe(Vec3 from, List<Vec3> path) {
        Vec3 last = from;
        for (Vec3 point : path) {
            assertThat(OBSTACLES.clear(last, point)).as("leg %s -> %s", last, point).isTrue();
            last = point;
        }
    }

    @Test
    void sc1RouteFromTheDepotClimbsFirstBecauseOfTheLowBuildings() {
        Vec3 depot = new Vec3(0, 0, 2);
        Vec3 target = new Vec3(0, 100, 35);
        assertThat(OBSTACLES.clear(depot, target)).as("the straight diagonal clips 8 m buildings").isFalse();
        List<Vec3> path = PLANNER.plan(depot, target).orElseThrow();
        assertThat(path).containsExactly(new Vec3(0, 0, 35), target);
        assertSafe(depot, path);
    }

    @Test
    void goesOverATallStackWithTheMargin() {
        Vec3 from = new Vec3(150, 160, 20);
        Vec3 to = new Vec3(210, 160, 20);
        List<Vec3> path = PLANNER.plan(from, to).orElseThrow();
        assertThat(path.get(0).u()).isGreaterThanOrEqualTo(45 + 2.5 + 1);
        assertThat(path.get(path.size() - 1)).isEqualTo(to);
        assertSafe(from, path);
    }

    @Test
    void refusesATargetInsideTheClearanceOrOutsideTheArea() {
        assertThat(PLANNER.plan(new Vec3(0, 0, 35), new Vec3(-100, 100, 30))).as("inside S02 stack clearance").isEmpty();
        assertThat(PLANNER.plan(new Vec3(0, 0, 35), new Vec3(500, 0, 30))).isEmpty();
        assertThat(PLANNER.plan(new Vec3(0, 0, 35), new Vec3(0, 0, 90))).isEmpty();
    }

    @Test
    void aClearLineIsFlownDirectly() {
        Vec3 from = new Vec3(0, 0, 35);
        Vec3 to = new Vec3(0, 100, 35);
        assertThat(PLANNER.plan(from, to).orElseThrow()).containsExactly(to);
        assertThat(PathPlanner.length(from, List.of(to))).isEqualTo(100.0);
    }

    @Test
    void segmentTestCatchesACornerGraze() {
        Box box = new Box("b", "STACK", 0, 1, 0, 1, 0, 1);
        assertThat(box.hitBy(new Vec3(-1, 0.5, 0.5), new Vec3(2, 0.5, 0.5))).isTrue();
        assertThat(box.hitBy(new Vec3(-1, 1.5, 0.5), new Vec3(2, 1.5, 0.5))).isFalse();
        assertThat(box.hitBy(new Vec3(-1, -1, 0.5), new Vec3(2, 2, 0.5))).isTrue();
    }
}
