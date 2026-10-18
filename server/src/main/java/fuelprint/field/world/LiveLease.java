package fuelprint.field.world;

import fuelprint.api.Pilot;
import fuelprint.field.geo.Vec3;

/**
 * The lease that is active right now on the drone, as the world tracks it between record revisions.
 * Renewals by fresh input happen here only (10 per second); the {@code lease} record gets a new revision
 * when the lease starts and when it ends (with the last sequence).
 */
final class LiveLease {

    final String leaseId;
    final String runId;
    final String missionId;
    final String holder;
    final Pilot kind;
    final boolean homing;
    long expiresMono;
    Long lastSequence;
    long lastInputMono = Long.MIN_VALUE / 2;
    long lastAcceptedMono = Long.MIN_VALUE / 2;
    Vec3 wanted = Vec3.ZERO;
    /** Set when the lease is being ended (expired, revoked, run over): no input is accepted any more. */
    boolean ending;
    /** True after a (re)connect until a message with a sequence above {@link #lastSequence} arrives. */
    boolean awaitingFreshInput;

    LiveLease(String leaseId, String runId, String missionId, String holder, Pilot kind, boolean homing,
              long expiresMono, Long lastSequence) {
        this.leaseId = leaseId;
        this.runId = runId;
        this.missionId = missionId;
        this.holder = holder;
        this.kind = kind;
        this.homing = homing;
        this.expiresMono = expiresMono;
        this.lastSequence = lastSequence;
    }

    boolean person() {
        return kind != Pilot.AUTOPILOT;
    }
}
