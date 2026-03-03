package com.conveyal.r5.profile;

import com.conveyal.r5.api.util.LegMode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class DominatingListContractTest {

    private static McRaptorSuboptimalPathProfileRouter.McRaptorState makeState(
        int stop,
        int time,
        int round,
        int pattern,
        int trip,
        LegMode accessMode
    ) {
        McRaptorSuboptimalPathProfileRouter.McRaptorState s =
            new McRaptorSuboptimalPathProfileRouter.McRaptorState();
        s.stop = stop;
        s.time = time;
        s.round = round;
        s.pattern = pattern;
        s.trip = trip;
        s.accessMode = accessMode;
        s.egressMode = null;
        s.boardStopPosition = -1;
        s.alightStopPosition = -1;
        s.back = null;
        return s;
    }

    @Test
    public void rejectedStateIsNotRetainedInSuboptimalList() {
        SuboptimalDominatingList list = new SuboptimalDominatingList(2);

        McRaptorSuboptimalPathProfileRouter.McRaptorState dominator =
            makeState(42, 1000, 0, 1, 0, LegMode.WALK);
        assertTrue(list.add(dominator));

        // Identical state should be rejected by duplicate fast path.
        McRaptorSuboptimalPathProfileRouter.McRaptorState duplicate =
            makeState(42, 1000, 0, 1, 0, LegMode.WALK);
        boolean added = list.add(duplicate);
        assertFalse(added);

        for (McRaptorSuboptimalPathProfileRouter.McRaptorState s : list.getNonDominatedStates()) {
            assertNotSame(
                "add() returned false but duplicate state is still identity-present in list",
                duplicate,
                s
            );
        }
    }
}

