
package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.scenario.FakeGraph;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SuboptimalProfileRouterTest {
    public TransportNetwork network;

    @Before
    public void setUp() {
        network = FakeGraph.buildNetwork(FakeGraph.TransitNetwork.SUBOPTIMAL_LINES);
    }

    @Test
    public void testSuboptimalRouting() {
        ProfileRequest request = new ProfileRequest();
        // near s1 and s1b
        request.fromLat = 40.02183;
        request.fromLon = -83.0889;
        // near s2 and s2b
        request.toLat = 39.9622;
        request.toLon = -83.0007;
        request.suboptimalMinutes = 2;
        request.accessModes = request.egressModes = EnumSet.of(LegMode.WALK);
        request.date = java.time.LocalDate.of(2025, 10, 17);
        request.fromTime = 7 * 3600; // 7 AM
        request.toTime = 8 * 3600;   // 8 AM
        request.transitModes = EnumSet.allOf(TransitModes.class);

        // Perform access search
        StreetRouter accessRouter = new StreetRouter(network.streetLayer);
        accessRouter.streetMode = StreetMode.WALK;
        accessRouter.profileRequest = request;
        accessRouter.timeLimitSeconds = 120 * 60;
        accessRouter.transitStopSearch = true;
        accessRouter.setOrigin(request.fromLat, request.fromLon);
        accessRouter.route();
        TIntIntMap accessTimes = accessRouter.getReachedStops();
        Map<LegMode, TIntIntMap> accessTimesByMode = new HashMap<>();
        accessTimesByMode.put(LegMode.WALK, accessTimes);


        // Perform egress search
        request.reverseSearch = true;
        StreetRouter egressRouter = new StreetRouter(network.streetLayer);
        egressRouter.streetMode = StreetMode.WALK;
        egressRouter.profileRequest = request;
        egressRouter.timeLimitSeconds = 120 * 60;
        egressRouter.transitStopSearch = true;
        egressRouter.setOrigin(request.toLat, request.toLon);
        egressRouter.route();
        TIntIntMap egressTimes = egressRouter.getReachedStops();
        Map<LegMode, TIntIntMap> egressTimesByMode = new HashMap<>();
        egressTimesByMode.put(LegMode.WALK, egressTimes);
        request.reverseSearch = false;


        // Run the profile router
        McRaptorSuboptimalPathProfileRouter router = new McRaptorSuboptimalPathProfileRouter(network, request,
                accessTimesByMode, egressTimesByMode, (t) -> new SuboptimalDominatingList(request.suboptimalMinutes), null);

        Collection<PathWithTimes> paths = router.getPaths();

        // There should be two paths found, one on the fast route and one on the slow route
        assertEquals(2, paths.size());

        // Check that one path is on route "route" and the other on "route2"
        long route1Paths = paths.stream()
                .filter(p -> "route".equals(network.transitLayer.routes.get(network.transitLayer.tripPatterns.get(p.patterns[0]).routeIndex).route_id))
                .count();

        long route2Paths = paths.stream()
                .filter(p -> "route2".equals(network.transitLayer.routes.get(network.transitLayer.tripPatterns.get(p.patterns[0]).routeIndex).route_id))
                .count();

        assertEquals(1, route1Paths);
        assertEquals(1, route2Paths);
    }
}
