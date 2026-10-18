package fuelprint.api;

/** The simulation clock as the other packages see it (core implements, CORE-API section 3). */
public interface ClockControl {

    /** Simulation seconds since the current run started. */
    double simTimeS();

    /** The time scale in use now: 1, or 15 while fast-forwarding. */
    double timeScale();

    /** Asks for time scale 1 or 15; core allows 15 only while every vehicle is stationary. */
    void requestTimeScale(String requester, double scale);

    /** Withdraws {@code requester}'s time-scale request. */
    void releaseTimeScale(String requester);
}
