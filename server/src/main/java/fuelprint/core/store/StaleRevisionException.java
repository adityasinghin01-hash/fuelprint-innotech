package fuelprint.core.store;

/** Thrown when a save was based on a revision that is no longer the latest one. Nothing is saved. */
public class StaleRevisionException extends RuntimeException {

    private final RecordKey key;
    private final long expected;
    private final long actual;

    public StaleRevisionException(RecordKey key, long expected, long actual) {
        super("Stale revision for " + key.type() + " " + key.id() + ": expected " + expected + ", latest is " + actual);
        this.key = key;
        this.expected = expected;
        this.actual = actual;
    }

    public RecordKey key() {
        return key;
    }

    public long expected() {
        return expected;
    }

    public long actual() {
        return actual;
    }
}
