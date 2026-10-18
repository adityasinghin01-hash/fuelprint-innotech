package fuelprint.api;

/** Science: moves the simulated world forward. */
public interface WorldClock {

    /** Advances the world by {@code dtS} simulation seconds. */
    void step(double dtS);

    /** The wind now. */
    WindNow wind();
}
