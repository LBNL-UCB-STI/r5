package com.conveyal.r5.streets;

class StatePool {
    private final StreetRouter.State[] pool;
    private int nextAvailable;

    StatePool(int poolSize) {
        this.pool = new StreetRouter.State[poolSize];

        // Pre-populate entire pool
        for (int i = 0; i < poolSize; i++) {
            pool[i] = new StreetRouter.State();
        }
        this.nextAvailable = poolSize;
    }

    StreetRouter.State borrow() {
        if (nextAvailable > 0) {
            return pool[--nextAvailable];
        }
        // Pool exhausted - allocate non-pooled state (will be GC'd)
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
        // States are already in pool, just make them available again
        // (reset() will be called when borrowed)
    }

    int getPoolSize() { return pool.length; }
    int getAvailableCount() { return nextAvailable; }
    int getInUseCount() { return pool.length - nextAvailable; }
}