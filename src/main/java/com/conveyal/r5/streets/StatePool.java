package com.conveyal.r5.streets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class StatePool {
    private final List<StreetRouter.State> available;
    private final Set<StreetRouter.State> inUse;
    private final int maxSize;

    StatePool(int initialCapacity) {
        this(initialCapacity, 20000); // Default max of 20k states
    }

    StatePool(int initialCapacity, int maxSize) {
        this.available = new ArrayList<>(initialCapacity);
        this.inUse = new HashSet<>(initialCapacity);
        this.maxSize = maxSize;

        // Pre-populate with initial states
        for (int i = 0; i < Math.min(initialCapacity, maxSize); i++) {
            available.add(new StreetRouter.State());
        }
    }

    StreetRouter.State borrow() {
        StreetRouter.State s;
        if (available.isEmpty()) {
            int currentPoolSize = inUse.size();
            if (currentPoolSize < maxSize) {
                // Pool has room - create a new pooled state
                s = new StreetRouter.State();
                inUse.add(s);
            } else {
                // Pool is at max - allocate a NON-pooled state
                // This won't be tracked in inUse, so it won't be returned to pool
                s = new StreetRouter.State();
                // Don't add to inUse - it's a throwaway state
            }
        } else {
            s = available.remove(available.size() - 1);
            inUse.add(s);
        }
        return s;
    }

    void returnState(StreetRouter.State s) {
        // Only accept states that were pooled (tracked in inUse)
        if (inUse.remove(s)) {
            s.reset();  // Clear state for reuse
            available.add(s);
        }
        // If not in inUse, it was a fallback allocation - just ignore it (will be GC'd)
    }

    void reset() {
        // Return all in-use states to pool
        for (StreetRouter.State s : inUse) {
            s.reset();
            available.add(s);
        }
        inUse.clear();
    }

    int getPoolSize() { return available.size() + inUse.size(); }
    int getAvailableCount() { return available.size(); }
    int getInUseCount() { return inUse.size(); }
}