package fuelprint.field.profile;

import java.util.List;

import fuelprint.field.geo.Box;
import fuelprint.field.geo.Vec3;

/**
 * The numbers the field package uses, read from {@code contracts/reference/world-profile.json} and the
 * context buildings of {@code data/context/derived-scene-crop.json} (see {@link ProfileLoader}).
 *
 * @param maxSpeedMS             aerial top speed (3 m/s)
 * @param maxAccelMS2            acceleration limit (1 m/s per second)
 * @param xyMin                  lowest allowed east and north (-450 m)
 * @param xyMax                  highest allowed east and north (450 m)
 * @param zMin                   lowest allowed height (2 m)
 * @param zMax                   highest allowed height (80 m)
 * @param usableWh               usable battery energy (64 Wh)
 * @param reserveWh              energy that must be left on landing (12 Wh)
 * @param hoverW                 hover power (160 W)
 * @param instrumentsW           instrument power (10 W)
 * @param clearanceM             aerial clearance from any box (2 m)
 * @param vehicleRadiusM         vehicle radius (0.5 m)
 * @param depot                  where the drone starts and lands
 * @param obstacles              sheds, stacks and context buildings (not grown)
 * @param contextBuildingsLoaded context buildings read from the crop
 * @param contextBuildingsCleared context buildings left out because they overlap the depot pad
 * @param procedure              capture stage times
 */
public record WorldProfile(double maxSpeedMS, double maxAccelMS2, double xyMin, double xyMax, double zMin,
                           double zMax, double usableWh, double reserveWh, double hoverW, double instrumentsW,
                           double clearanceM, double vehicleRadiusM, Vec3 depot, List<Box> obstacles,
                           int contextBuildingsLoaded, int contextBuildingsCleared, Procedure procedure) {

    public WorldProfile {
        obstacles = List.copyOf(obstacles);
    }

    /** Clearance plus radius: how far the drone's centre must stay from every box. */
    public double obstacleMarginM() {
        return clearanceM + vehicleRadiusM;
    }

    /**
     * Capture stage times in simulation seconds ({@code default_procedure_s}).
     *
     * @param setupS            SETUP
     * @param backgroundS       BACKGROUND
     * @param purgeS            PURGE
     * @param minimumSettleS    SETTLE, at least
     * @param validCollectionS  COLLECT: consecutive valid seconds needed
     * @param minimumRecoveryS  RECOVER, at least
     * @param reviewS           REVIEW
     */
    public record Procedure(double setupS, double backgroundS, double purgeS, double minimumSettleS,
                            double validCollectionS, double minimumRecoveryS, double reviewS) {
    }
}
