package com.conveyal.r5.streets;

import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;

public class McRaptorStatePool {
    private final McRaptorSuboptimalPathProfileRouter.McRaptorState[] pool;
    private int nextAvailable;

    // Overall stats
    private long borrowCount = 0;
    private long exhaustionCount = 0;
    private int maxInUse = 0;

    // Per-route tracking (reset before each route)
    private int exhaustionsSinceReset = 0;

    public McRaptorStatePool(int poolSize) {
        this.pool = new McRaptorSuboptimalPathProfileRouter.McRaptorState[poolSize];

        // Pre-populate entire pool
        for (int i = 0; i < poolSize; i++) {
            pool[i] = new McRaptorSuboptimalPathProfileRouter.McRaptorState();
        }
        this.nextAvailable = poolSize;
    }

    public McRaptorSuboptimalPathProfileRouter.McRaptorState borrow() {
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
        return new McRaptorSuboptimalPathProfileRouter.McRaptorState();
    }

    public void returnState(McRaptorSuboptimalPathProfileRouter.McRaptorState s) {
        if (nextAvailable < pool.length) {
            s.reset();
            pool[nextAvailable++] = s;
        }
        // Otherwise discard (pool is full or state was non-pooled)
    }

    public void reset() {
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