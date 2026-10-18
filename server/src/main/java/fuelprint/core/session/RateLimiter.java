package fuelprint.core.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** At most {@code limit} hits per key in any {@code windowMs} (sliding window, in memory). */
public class RateLimiter {

    private final int limit;
    private final long windowMs;
    private final LongSupplier nowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMs, LongSupplier nowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.nowMs = nowMs;
    }

    /** True (and counted) if {@code key} may do it now. */
    public boolean tryAcquire(String key) {
        long now = nowMs.getAsLong();
        Deque<Long> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - windowMs) {
                times.removeFirst();
            }
            if (times.size() >= limit) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
