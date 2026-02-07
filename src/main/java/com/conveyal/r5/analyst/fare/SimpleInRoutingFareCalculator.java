package com.conveyal.r5.analyst.fare;

import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;

/**
 * A simple greedy fare calculator that simply applies a single fare at each boarding.
 */
public class SimpleInRoutingFareCalculator extends InRoutingFareCalculator {
    public int fare;

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        // Fast path: simple fares are additive and have no transfer privileges, so when the back-state fare
        // is already known we can extend it in O(1) instead of walking the entire back-chain.
        if (state.back != null && state.back.fare != null) {
            int fareForState = state.back.fare.cumulativeFarePaid;
            if (state.pattern != -1) fareForState += fare;
            return new FareBounds(fareForState, new TransferAllowance());
        }

        int fareForState = 0;

        while (state != null) {
            if (state.pattern != -1) fareForState += fare;
            state = state.back;
        }

        return new FareBounds(fareForState, new TransferAllowance());
    }

    @Override
    public String getType() {
        return "simple";
    }
}
