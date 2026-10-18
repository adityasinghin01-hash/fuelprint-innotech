package fuelprint.field;

import java.nio.file.Path;

import fuelprint.field.geo.Area;
import fuelprint.field.geo.Obstacles;
import fuelprint.field.geo.PathPlanner;
import fuelprint.field.profile.ProfileLoader;
import fuelprint.field.profile.WorldProfile;
import fuelprint.field.vehicle.Kinematics;

/** The real world profile and geometry from the repository, loaded once for unit tests. */
public final class TestProfile {

    public static final WorldProfile PROFILE = ProfileLoader.load(ProfileLoader.findRepoRoot(Path.of("")));
    public static final Obstacles OBSTACLES = new Obstacles(PROFILE.obstacles(), PROFILE.obstacleMarginM());
    public static final Area AREA = new Area(PROFILE.xyMin(), PROFILE.xyMax(), PROFILE.zMin(), PROFILE.zMax());
    public static final Kinematics KINEMATICS = new Kinematics(PROFILE, OBSTACLES);
    public static final PathPlanner PLANNER = new PathPlanner(OBSTACLES, AREA);

    private TestProfile() {
    }
}
