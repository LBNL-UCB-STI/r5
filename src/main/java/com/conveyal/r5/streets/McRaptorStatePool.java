package com.conveyal.r5.streets;

import com.conveyal.r5.profile.DominatingList;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

public class McRaptorStatePool {
    private final McRaptorSuboptimalPathProfileRouter.McRaptorState[] pool;
    private int nextAvailable;
    private long ownerThreadId = Long.MIN_VALUE;

    // Overall stats
    private long borrowCount = 0;
    private long exhaustionCount = 0;
    private int maxInUse = 0;
    private int currentlyInUse = 0;

    // Per-route tracking (reset before each route)
    private int exhaustionsSinceReset = 0;

    private List<McRaptorSuboptimalPathProfileRouter.McRaptorStateBag> stateBagPool = new ArrayList<>(5000);
    private int nextStateBag = 0;
    private int maxStateBagsInUse = 0;
    /** Tracks which state instances are currently present in the available segment of the pool array. */
    private final Set<McRaptorSuboptimalPathProfileRouter.McRaptorState> inPoolSet =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public McRaptorStatePool(int poolSize) {
        this.pool = new McRaptorSuboptimalPathProfileRouter.McRaptorState[poolSize];

        // Pre-populate entire pool
        for (int i = 0; i < poolSize; i++) {
            pool[i] = new McRaptorSuboptimalPathProfileRouter.McRaptorState();
            inPoolSet.add(pool[i]);
        }
        this.nextAvailable = poolSize;
    }

    public McRaptorSuboptimalPathProfileRouter.McRaptorState borrow() {
        assertThreadOwnership("borrow");
        borrowCount++;
        currentlyInUse++;

        if (currentlyInUse > maxInUse) {
            maxInUse = currentlyInUse;
        }

        if (nextAvailable > 0) {
            McRaptorSuboptimalPathProfileRouter.McRaptorState state = pool[--nextAvailable];
            inPoolSet.remove(state);
            if (!state.inPool) {
                int occurrences = countOccurrencesInPool(state);
                throw new IllegalStateException(
                        String.format(
                                "Borrowed pooled McRaptorState that was not marked in-pool: " +
                                        "poolId=%d thread=%s ownerThreadId=%d nextAvailable=%d poolSize=%d " +
                                        "stateId=%d stop=%d round=%d pattern=%d trip=%d time=%d occurrencesInPool=%d",
                                System.identityHashCode(this),
                                Thread.currentThread().getName(),
                                ownerThreadId,
                                nextAvailable,
                                pool.length,
                                System.identityHashCode(state),
                                state.stop,
                                state.round,
                                state.pattern,
                                state.trip,
                                state.time,
                                occurrences
                        )
                );
            }
            state.inPool = false;
            return state;
        }

        // Pool exhausted - allocate non-pooled state
        exhaustionCount++;
        exhaustionsSinceReset++;
        McRaptorSuboptimalPathProfileRouter.McRaptorState state = new McRaptorSuboptimalPathProfileRouter.McRaptorState();
        state.inPool = false;
        return state;
    }

    public void returnState(McRaptorSuboptimalPathProfileRouter.McRaptorState s) {
        assertThreadOwnership("returnState");
        currentlyInUse--;
        if (s.inPool) {
            throw new IllegalStateException(
                    String.format(
                            "Double return of McRaptorState to pool: poolId=%d thread=%s ownerThreadId=%d " +
                                    "stateId=%d stop=%d round=%d pattern=%d trip=%d time=%d",
                            System.identityHashCode(this),
                            Thread.currentThread().getName(),
                            ownerThreadId,
                            System.identityHashCode(s),
                            s.stop,
                            s.round,
                            s.pattern,
                            s.trip,
                            s.time
                    )
            );
        }
        if (inPoolSet.contains(s)) {
            throw new IllegalStateException(
                    String.format(
                            "Duplicate insertion of McRaptorState into pool array: poolId=%d thread=%s ownerThreadId=%d " +
                                    "stateId=%d stop=%d round=%d pattern=%d trip=%d time=%d nextAvailable=%d poolSize=%d",
                            System.identityHashCode(this),
                            Thread.currentThread().getName(),
                            ownerThreadId,
                            System.identityHashCode(s),
                            s.stop,
                            s.round,
                            s.pattern,
                            s.trip,
                            s.time,
                            nextAvailable,
                            pool.length
                    )
            );
        }
        if (nextAvailable < pool.length) {
            s.reset();
            pool[nextAvailable++] = s;
            inPoolSet.add(s);
        }
        // Otherwise discard (pool is full or state was non-pooled)
    }

    public McRaptorSuboptimalPathProfileRouter.McRaptorStateBag borrowStateBag(IntFunction<DominatingList> listSupplier, int departureTime) {
        assertThreadOwnership("borrowStateBag");
        if (nextStateBag < stateBagPool.size()) {
            McRaptorSuboptimalPathProfileRouter.McRaptorStateBag bag = stateBagPool.get(nextStateBag++);
            // Check if the existing lists in the bag are compatible with the new supplier.
            // If the search type changed (e.g. from suboptimal to fare-based), we must re-create the bag
            // to ensure the correct dominance rules are applied.
            DominatingList newExample = listSupplier.apply(departureTime);
            if (bag.isCompatible(newExample)) {
                bag.reset(listSupplier, departureTime);  // Reset and reconfigure
                bag.updateFrom(newExample);
            } else {
                // Incompatible search type, create a new bag and replace in pool.
                // This shouldn't happen often if the caller partitions pools by search type.
                bag = new McRaptorSuboptimalPathProfileRouter.McRaptorStateBag(() -> listSupplier.apply(departureTime), this);
                stateBagPool.set(nextStateBag - 1, bag);
            }
            if (nextStateBag > maxStateBagsInUse) maxStateBagsInUse = nextStateBag;
            return bag;
        } else {
            // Pool exhausted, create new
            McRaptorSuboptimalPathProfileRouter.McRaptorStateBag bag = new McRaptorSuboptimalPathProfileRouter.McRaptorStateBag(() -> listSupplier.apply(departureTime), this);
            stateBagPool.add(bag);
            nextStateBag++;
            if (nextStateBag > maxStateBagsInUse) maxStateBagsInUse = nextStateBag;
            return bag;
        }
    }

    public void reset() {
        assertThreadOwnership("reset");
        // Return all states to available.
        // Also reset lifecycle bits/fields so debug ownership checks don't
        // report stale borrowed markers from previous route invocations.
        for (McRaptorSuboptimalPathProfileRouter.McRaptorState state : pool) {
            state.reset();
        }
        nextAvailable = pool.length;
        inPoolSet.clear();
        Collections.addAll(inPoolSet, pool);
        // Reset StateBag pool
        nextStateBag = 0;
        // Reset per-route counter
        exhaustionsSinceReset = 0;
        currentlyInUse = 0;
    }

    /** Rewind state-bag borrow cursor without touching the state pool itself. */
    public void resetStateBags() {
        assertThreadOwnership("resetStateBags");
        nextStateBag = 0;
    }

    private void assertThreadOwnership(String operation) {
        long tid = Thread.currentThread().getId();
        if (ownerThreadId == Long.MIN_VALUE) {
            ownerThreadId = tid;
            return;
        }
        if (ownerThreadId != tid) {
            throw new IllegalStateException(
                    String.format(
                            "McRaptorStatePool cross-thread access: poolId=%d operation=%s ownerThreadId=%d currentThreadId=%d currentThread=%s",
                            System.identityHashCode(this),
                            operation,
                            ownerThreadId,
                            tid,
                            Thread.currentThread().getName()
                    )
            );
        }
    }

    private int countOccurrencesInPool(McRaptorSuboptimalPathProfileRouter.McRaptorState target) {
        int count = 0;
        for (McRaptorSuboptimalPathProfileRouter.McRaptorState state : pool) {
            if (state == target) count++;
        }
        return count;
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
