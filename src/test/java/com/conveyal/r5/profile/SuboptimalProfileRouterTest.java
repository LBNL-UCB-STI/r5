
package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.scenario.FakeGraph;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.streets.McRaptorStatePool;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import org.junit.Before;
import org.junit.Test;

import java.util.*;
import java.util.stream.Collectors;

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
        McRaptorStatePool statePool = new McRaptorStatePool(50000);
        // near s1 and s1b
        request.fromLat = 40.02183;
        request.fromLon = -83.0889;
        // near s2 and s2b
        request.toLat = 39.9622;
        request.toLon = -83.0007;
        request.suboptimalMinutes = 2;
        request.monteCarloDraws = 0;
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
                accessTimesByMode, egressTimesByMode, (t) -> new SuboptimalDominatingList(request.suboptimalMinutes), null, statePool);

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

    @Test
    public void testRouterResetHandlesChangingAccessAndEgressModes() {
        ProfileRequest request = new ProfileRequest();
        request.fromLat = 40.02183;
        request.fromLon = -83.0889;
        request.toLat = 39.9622;
        request.toLon = -83.0007;
        request.suboptimalMinutes = 2;
        request.monteCarloDraws = 0;
        request.date = java.time.LocalDate.of(2025, 10, 17);
        request.fromTime = 7 * 3600;
        request.toTime = 8 * 3600;
        request.transitModes = EnumSet.allOf(TransitModes.class);

        StreetRouter accessRouter = new StreetRouter(network.streetLayer);
        accessRouter.streetMode = StreetMode.WALK;
        accessRouter.profileRequest = request;
        accessRouter.timeLimitSeconds = 120 * 60;
        accessRouter.transitStopSearch = true;
        accessRouter.setOrigin(request.fromLat, request.fromLon);
        accessRouter.route();
        TIntIntMap walkAccessTimes = accessRouter.getReachedStops();

        request.reverseSearch = true;
        StreetRouter egressRouter = new StreetRouter(network.streetLayer);
        egressRouter.streetMode = StreetMode.WALK;
        egressRouter.profileRequest = request;
        egressRouter.timeLimitSeconds = 120 * 60;
        egressRouter.transitStopSearch = true;
        egressRouter.setOrigin(request.toLat, request.toLon);
        egressRouter.route();
        TIntIntMap walkEgressTimes = egressRouter.getReachedStops();
        request.reverseSearch = false;

        // First run with two mode entries, then reset to a single mode entry.
        // This catches stale mode-array/pool state when reusing a router.
        Map<LegMode, TIntIntMap> twoModeAccess = new LinkedHashMap<>();
        twoModeAccess.put(LegMode.WALK, walkAccessTimes);
        twoModeAccess.put(LegMode.CAR, walkAccessTimes);
        Map<LegMode, TIntIntMap> twoModeEgress = new LinkedHashMap<>();
        twoModeEgress.put(LegMode.WALK, walkEgressTimes);
        twoModeEgress.put(LegMode.CAR, walkEgressTimes);

        Map<LegMode, TIntIntMap> oneModeAccess = new LinkedHashMap<>();
        oneModeAccess.put(LegMode.WALK, walkAccessTimes);
        Map<LegMode, TIntIntMap> oneModeEgress = new LinkedHashMap<>();
        oneModeEgress.put(LegMode.WALK, walkEgressTimes);

        ProfileRequest twoModeRequest = request.clone();
        twoModeRequest.accessModes = EnumSet.of(LegMode.WALK, LegMode.CAR);
        twoModeRequest.egressModes = EnumSet.of(LegMode.WALK, LegMode.CAR);

        ProfileRequest oneModeRequest = request.clone();
        oneModeRequest.accessModes = EnumSet.of(LegMode.WALK);
        oneModeRequest.egressModes = EnumSet.of(LegMode.WALK);

        McRaptorStatePool statePool = new McRaptorStatePool(50000);

        McRaptorSuboptimalPathProfileRouter pooledRouter = new McRaptorSuboptimalPathProfileRouter(
                network,
                twoModeRequest,
                twoModeAccess,
                twoModeEgress,
                (t) -> new SuboptimalDominatingList(twoModeRequest.suboptimalMinutes),
                null,
                statePool
        );

        // Prime reusable internal state with a larger mode set.
        assertTrue(pooledRouter.getPaths().size() > 0);

        pooledRouter.reset(
                oneModeRequest,
                oneModeAccess,
                oneModeEgress,
                (t) -> new SuboptimalDominatingList(oneModeRequest.suboptimalMinutes),
                null
        );
        Collection<PathWithTimes> pooledPathsAfterReset = pooledRouter.getPaths();

        McRaptorSuboptimalPathProfileRouter freshRouter = new McRaptorSuboptimalPathProfileRouter(
                network,
                oneModeRequest,
                oneModeAccess,
                oneModeEgress,
                (t) -> new SuboptimalDominatingList(oneModeRequest.suboptimalMinutes),
                null,
                new McRaptorStatePool(50000)
        );
        Collection<PathWithTimes> freshPaths = freshRouter.getPaths();

        Set<String> pooledRouteIds = pooledPathsAfterReset.stream()
                .filter(p -> p.patterns.length > 0)
                .map(p -> network.transitLayer.routes.get(network.transitLayer.tripPatterns.get(p.patterns[0]).routeIndex).route_id)
                .collect(Collectors.toSet());
        Set<String> freshRouteIds = freshPaths.stream()
                .filter(p -> p.patterns.length > 0)
                .map(p -> network.transitLayer.routes.get(network.transitLayer.tripPatterns.get(p.patterns[0]).routeIndex).route_id)
                .collect(Collectors.toSet());

        assertEquals(freshPaths.size(), pooledPathsAfterReset.size());
        assertEquals(freshRouteIds, pooledRouteIds);
    }
}
