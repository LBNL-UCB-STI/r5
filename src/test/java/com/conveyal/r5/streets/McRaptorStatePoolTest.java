package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.TransferAllowance;
import com.conveyal.r5.profile.DominatingList;
import com.conveyal.r5.profile.FareDominatingList;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.IntFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class McRaptorStatePoolTest {

    private static class FixedOutcomeDominatingList implements DominatingList {
        private final boolean shouldAccept;
        private final ArrayList<McRaptorSuboptimalPathProfileRouter.McRaptorState> states = new ArrayList<>();

        private FixedOutcomeDominatingList(boolean shouldAccept) {
            this.shouldAccept = shouldAccept;
        }

        @Override
        public boolean add(
            McRaptorSuboptimalPathProfileRouter.McRaptorState state,
            Consumer<McRaptorSuboptimalPathProfileRouter.McRaptorState> evictionCallback
        ) {
            if (!shouldAccept) {
                return false;
            }
            states.add(state);
            return true;
        }

        @Override
        public void reset() {
            states.clear();
        }

        @Override
        public Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState> getNonDominatedStates() {
            return states;
        }
    }

    private Supplier<DominatingList> bagFactory(boolean bestAccepts, boolean nonTransferAccepts) {
        return new Supplier<DominatingList>() {
            private int created = 0;

            @Override
            public DominatingList get() {
                created += 1;
                return created == 1
                    ? new FixedOutcomeDominatingList(bestAccepts)
                    : new FixedOutcomeDominatingList(nonTransferAccepts);
            }
        };
    }

    @Test
    public void testStateBagReuseReconfiguresFareCutoffByDepartureTime() {
        McRaptorStatePool pool = new McRaptorStatePool(8);

        // Use a dynamic FareDominatingList so max clock time depends on departure time.
        IntFunction<DominatingList> listSupplier = departureTime ->
                new FareDominatingList(
                        null,
                        10_000,
                        departureTime,
                        600
                );

        McRaptorSuboptimalPathProfileRouter.McRaptorStateBag firstBag = pool.borrowStateBag(listSupplier, 1_000);
        McRaptorSuboptimalPathProfileRouter.McRaptorState firstState = new McRaptorSuboptimalPathProfileRouter.McRaptorState();
        firstState.time = 2_100;
        firstState.pattern = -1;
        firstState.fare = new FareBounds(0, new TransferAllowance());

        // At departure=1000 with maxTrip=600, maxClockTime=1600 so this should be rejected.
        assertFalse(firstBag.add(firstState));

        // Simulate next departure in the same route run: reuse bag and reconfigure list in place.
        pool.resetStateBags();
        McRaptorSuboptimalPathProfileRouter.McRaptorStateBag secondBag = pool.borrowStateBag(listSupplier, 2_000);
        assertSame(firstBag, secondBag);

        McRaptorSuboptimalPathProfileRouter.McRaptorState secondState = new McRaptorSuboptimalPathProfileRouter.McRaptorState();
        secondState.time = 2_100;
        secondState.pattern = -1;
        secondState.fare = new FareBounds(0, new TransferAllowance());

        // At departure=2000 with maxTrip=600, maxClockTime=2600 so the same state should now be accepted.
        assertTrue(secondBag.add(secondState));
    }

    @Test
    public void testStateBagPartialAddReturnsUnretainedStateToPool() {
        McRaptorStatePool poolA = new McRaptorStatePool(8);
        McRaptorSuboptimalPathProfileRouter.McRaptorStateBag bagA =
            new McRaptorSuboptimalPathProfileRouter.McRaptorStateBag(bagFactory(false, true));

        McRaptorSuboptimalPathProfileRouter.McRaptorState stateA = poolA.borrow();
        stateA.pattern = 1; // Transit state goes to both lists.
        assertTrue(bagA.add(stateA));
        assertEquals(
            "Only one state should remain retained when best rejects and nonTransfer accepts",
            7,
            poolA.getAvailableCount()
        );

        McRaptorStatePool poolB = new McRaptorStatePool(8);
        McRaptorSuboptimalPathProfileRouter.McRaptorStateBag bagB =
            new McRaptorSuboptimalPathProfileRouter.McRaptorStateBag(bagFactory(true, false));

        McRaptorSuboptimalPathProfileRouter.McRaptorState stateB = poolB.borrow();
        stateB.pattern = 1; // Transit state goes to both lists.
        assertTrue(bagB.add(stateB));
        assertEquals(
            "Only one state should remain retained when best accepts and nonTransfer rejects",
            7,
            poolB.getAvailableCount()
        );
    }
}
