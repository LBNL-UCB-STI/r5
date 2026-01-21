package com.conveyal.r5.streets;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;

public interface TravelTimeCalculator {

    /**
     * Primary method - backwards compatible (kept as abstract for old implementations)
     */
    float getTravelTimeSeconds(
            EdgeStore.Edge edge,
            int durationSeconds,
            StreetMode streetMode,
            ProfileRequest req
    );

    /**
     * NEW: Overload with explicit start time for stateless calculators.
     * Default implementation uses the old method, but new implementations can override both.
     */
    default float getTravelTimeSeconds(
            EdgeStore.Edge edge,
            int durationSeconds,
            StreetMode streetMode,
            ProfileRequest req,
            int searchStartTimeSeconds
    ) {
        // Default: just call the old method (suboptimal but works)
        return getTravelTimeSeconds(edge, durationSeconds, streetMode, req);
    }
}