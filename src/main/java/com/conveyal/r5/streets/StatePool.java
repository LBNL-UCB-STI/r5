package com.conveyal.r5.streets;

public class StatePool {
    private final StreetRouter.State[] pool;
    private int nextAvailable;

    // Overall stats
    private long borrowCount = 0;
    private long exhaustionCount = 0;
    private int maxInUse = 0;

    // Per-route tracking (reset before each route)
    private int exhaustionsSinceReset = 0;

    public StatePool(int poolSize) {
        this.pool = new StreetRouter.State[poolSize];

        // Pre-populate entire pool
        for (int i = 0; i < poolSize; i++) {
            pool[i] = new StreetRouter.State();
        }
        this.nextAvailable = poolSize;
    }

    StreetRouter.State borrow() {
        borrowCount++;

        if (nextAvailable > 0) {
            // Track max usage
            int inUse = pool.length - nextAvailable + 1;
            if (inUse > maxInUse) {
                maxInUse = inUse;
            }

            return pool[--nextAvailable];
        }

        // Pool exhausted - allocate non-pooled state (will be GC'd)
        exhaustionCount++;
        exhaustionsSinceReset++;
        return new StreetRouter.State();
    }

    void returnState(StreetRouter.State s) {
        if (nextAvailable < pool.length) {
            s.reset();
            pool[nextAvailable++] = s;
        }
        // Otherwise discard (pool is full or state was non-pooled)
    }

    void reset() {
        // Return all states to available
        nextAvailable = pool.length;
        // Reset per-route counter
        exhaustionsSinceReset = 0;
    }

    // Getters for stats
    public int getPoolSize() {
        return pool.length;
    }

    public int getAvailableCount() {
        return nextAvailable;
    }

    public int getInUseCount() {
        return pool.length - nextAvailable;
    }

    public long getBorrowCount() {
        return borrowCount;
    }

    public long getExhaustionCount() {
        return exhaustionCount;
    }

    public int getMaxInUse() {
        return maxInUse;
    }

    public double getExhaustionRate() {
        return borrowCount > 0 ? (exhaustionCount * 100.0) / borrowCount : 0.0;
    }

    public int getExhaustionsSinceReset() {
        return exhaustionsSinceReset;
    }
}