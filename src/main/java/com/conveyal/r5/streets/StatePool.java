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
        this.available = new ArrayList<>(initialCapacity);
        this.inUse = new HashSet<>(initialCapacity);
        this.maxSize = initialCapacity * 100;

        // Pre-populate with initial states
        for (int i = 0; i < initialCapacity; i++) {
            available.add(new StreetRouter.State());
        }
    }

    StreetRouter.State borrow() {
        StreetRouter.State s;
        if (available.isEmpty()) {
            if (inUse.size() < maxSize) {
                s = new StreetRouter.State();  // Create new if under limit
            } else {
                throw new IllegalStateException("State pool exhausted");
            }
        } else {
            s = available.remove(available.size() - 1);
        }
        inUse.add(s);
        return s;
    }

    void returnState(StreetRouter.State s) {
        if (inUse.remove(s)) {
            s.reset();  // Clear state for reuse
            available.add(s);
        }
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
}
