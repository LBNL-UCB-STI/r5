
package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.scenario.FakeGraph;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.streets.McRaptorStatePool;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
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
    public void testPathWithTimesSkipsPathsWhenFirstTripIsAlreadyMissed() {
        ProfileRequest request = new ProfileRequest();
        request.walkSpeed = 1.3f;
        request.fromTime = 7 * 3600;
        request.toTime = 8 * 3600;
        request.monteCarloDraws = 0;

        int patternIndex = 0;
        int boardStop = network.transitLayer.tripPatterns.get(patternIndex).stops[0];
        int alightStop = network.transitLayer.tripPatterns.get(patternIndex).stops[1];

        network.transitLayer.tripPatterns.get(patternIndex).tripSchedules =
                Collections.singletonList(network.transitLayer.tripPatterns.get(patternIndex).tripSchedules.get(0));

        TIntIntHashMap accessTimes = new TIntIntHashMap();
        accessTimes.put(boardStop, 1);
        TIntIntHashMap egressTimes = new TIntIntHashMap();
        egressTimes.put(alightStop, 0);

        McRaptorSuboptimalPathProfileRouter.McRaptorState origin =
                new McRaptorSuboptimalPathProfileRouter.McRaptorState();
        origin.setOrigin(boardStop, request.fromTime, 0, LegMode.WALK);

        McRaptorSuboptimalPathProfileRouter.McRaptorState transit =
                new McRaptorSuboptimalPathProfileRouter.McRaptorState();
        transit.setFrom(
                origin,
                alightStop,
                0,
                1,
                network.transitLayer.tripPatterns.get(patternIndex).tripSchedules.get(0).arrivals[1],
                network.transitLayer.tripPatterns.get(patternIndex).tripSchedules.get(0).departures[0],
                patternIndex,
                0,
                1
        );
        transit.egressMode = LegMode.WALK;

        try {
            new PathWithTimes(transit, network, request, accessTimes, egressTimes);
            fail("Expected infeasible path timing reconstruction to be skipped");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("No feasible first-leg trips remain"));
        }
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

    @Test
    public void testDeterministicMultiDepartureSamplingIsReproducible() {
        ProfileRequest request = new ProfileRequest();
        request.fromLat = 40.02183;
        request.fromLon = -83.0889;
        request.toLat = 39.9622;
        request.toLon = -83.0007;
        request.suboptimalMinutes = 2;
        request.monteCarloDraws = 0;
        request.mcRaptorDeterministicDepartureCount = 3;
        request.mcRaptorDeterministicDepartureStepSeconds = 120;
        request.accessModes = request.egressModes = EnumSet.of(LegMode.WALK);
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
        TIntIntMap accessTimes = accessRouter.getReachedStops();
        Map<LegMode, TIntIntMap> accessTimesByMode = new HashMap<>();
        accessTimesByMode.put(LegMode.WALK, accessTimes);

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

        McRaptorStatePool sharedPool = new McRaptorStatePool(50000);

        McRaptorSuboptimalPathProfileRouter routerRun1 = new McRaptorSuboptimalPathProfileRouter(
                network,
                request,
                accessTimesByMode,
                egressTimesByMode,
                (t) -> new SuboptimalDominatingList(request.suboptimalMinutes),
                null,
                sharedPool
        );
        Collection<PathWithTimes> pathsRun1 = routerRun1.getPaths();
        int sampledDeparturesRun1 = routerRun1.getLastSampledDepartureCount();

        ProfileRequest requestRun2 = request.clone();
        McRaptorSuboptimalPathProfileRouter routerRun2 = new McRaptorSuboptimalPathProfileRouter(
                network,
                requestRun2,
                accessTimesByMode,
                egressTimesByMode,
                (t) -> new SuboptimalDominatingList(requestRun2.suboptimalMinutes),
                null,
                sharedPool
        );
        Collection<PathWithTimes> pathsRun2 = routerRun2.getPaths();
        int sampledDeparturesRun2 = routerRun2.getLastSampledDepartureCount();

        assertEquals(3, sampledDeparturesRun1);
        assertEquals(3, sampledDeparturesRun2);
        assertTrue(pathsRun1.size() > 0);
        assertTrue(pathsRun2.size() > 0);
        assertEquals(pathsRun1.size(), pathsRun2.size());
        Set<String> routeIdsRun1 = extractRouteIds(pathsRun1);
        Set<String> routeIdsRun2 = extractRouteIds(pathsRun2);
        assertTrue(routeIdsRun1.size() > 0);
        assertEquals(routeIdsRun1, routeIdsRun2);
    }

    @Test
    public void testRouterPoolingStressProducesStructurallyValidPaths() {
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
        request.accessModes = EnumSet.of(LegMode.WALK);
        request.egressModes = EnumSet.of(LegMode.WALK);

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

        Map<LegMode, TIntIntMap> access = new LinkedHashMap<>();
        access.put(LegMode.WALK, walkAccessTimes);
        Map<LegMode, TIntIntMap> egress = new LinkedHashMap<>();
        egress.put(LegMode.WALK, walkEgressTimes);

        McRaptorSuboptimalPathProfileRouter pooledRouter = new McRaptorSuboptimalPathProfileRouter(
                network,
                request,
                access,
                egress,
                (t) -> new SuboptimalDominatingList(request.suboptimalMinutes),
                null,
                new McRaptorStatePool(50000)
        );

        // Repeatedly reset and reroute to stress pooled internal structures.
        for (int i = 0; i < 250; i++) {
            pooledRouter.reset(
                    request,
                    access,
                    egress,
                    (t) -> new SuboptimalDominatingList(request.suboptimalMinutes),
                    null
            );
            Collection<PathWithTimes> paths = pooledRouter.getPaths();
            assertTrue("Expected at least one path at iteration " + i, paths.size() > 0);
            assertAllPathLegIndicesConsistent(paths);
        }
    }

    @Test
    public void testResetFailsFastWhenRouterIsMarkedInUse() throws Exception {
        ProfileRequest request = new ProfileRequest();
        request.fromLat = 40.02183;
        request.fromLon = -83.0889;
        request.toLat = 39.9622;
        request.toLon = -83.0007;
        request.suboptimalMinutes = 2;
        request.monteCarloDraws = 0;
        request.accessModes = request.egressModes = EnumSet.of(LegMode.WALK);
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
        TIntIntMap accessTimes = accessRouter.getReachedStops();
        Map<LegMode, TIntIntMap> accessByMode = new HashMap<>();
        accessByMode.put(LegMode.WALK, accessTimes);

        request.reverseSearch = true;
        StreetRouter egressRouter = new StreetRouter(network.streetLayer);
        egressRouter.streetMode = StreetMode.WALK;
        egressRouter.profileRequest = request;
        egressRouter.timeLimitSeconds = 120 * 60;
        egressRouter.transitStopSearch = true;
        egressRouter.setOrigin(request.toLat, request.toLon);
        egressRouter.route();
        TIntIntMap egressTimes = egressRouter.getReachedStops();
        Map<LegMode, TIntIntMap> egressByMode = new HashMap<>();
        egressByMode.put(LegMode.WALK, egressTimes);
        request.reverseSearch = false;

        McRaptorSuboptimalPathProfileRouter router = new McRaptorSuboptimalPathProfileRouter(
                network,
                request,
                accessByMode,
                egressByMode,
                (t) -> new SuboptimalDominatingList(request.suboptimalMinutes),
                null,
                new McRaptorStatePool(50000)
        );

        Field inUseField = McRaptorSuboptimalPathProfileRouter.class.getDeclaredField("inUse");
        inUseField.setAccessible(true);
        AtomicBoolean inUse = (AtomicBoolean) inUseField.get(router);
        assertNotNull(inUse);
        inUse.set(true);

        IllegalStateException ex;
        try {
            router.reset(
                    request,
                    accessByMode,
                    egressByMode,
                    (t) -> new SuboptimalDominatingList(request.suboptimalMinutes),
                    null
            );
            fail("Expected IllegalStateException when resetting router while in use");
            return;
        } catch (IllegalStateException e) {
            ex = e;
        }
        assertTrue(ex.getMessage().contains("not thread-safe"));
    }

    @Test
    public void testRouterPoolingStressWithAlternatingModeShapes() {
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

        Map<LegMode, TIntIntMap> oneModeAccess = new LinkedHashMap<>();
        oneModeAccess.put(LegMode.WALK, walkAccessTimes);
        Map<LegMode, TIntIntMap> oneModeEgress = new LinkedHashMap<>();
        oneModeEgress.put(LegMode.WALK, walkEgressTimes);

        Map<LegMode, TIntIntMap> twoModeAccess = new LinkedHashMap<>();
        twoModeAccess.put(LegMode.WALK, walkAccessTimes);
        twoModeAccess.put(LegMode.CAR, walkAccessTimes);
        Map<LegMode, TIntIntMap> twoModeEgress = new LinkedHashMap<>();
        twoModeEgress.put(LegMode.WALK, walkEgressTimes);
        twoModeEgress.put(LegMode.CAR, walkEgressTimes);

        ProfileRequest oneModeRequest = request.clone();
        oneModeRequest.accessModes = EnumSet.of(LegMode.WALK);
        oneModeRequest.egressModes = EnumSet.of(LegMode.WALK);

        ProfileRequest twoModeRequest = request.clone();
        twoModeRequest.accessModes = EnumSet.of(LegMode.WALK, LegMode.CAR);
        twoModeRequest.egressModes = EnumSet.of(LegMode.WALK, LegMode.CAR);

        McRaptorSuboptimalPathProfileRouter pooledRouter = new McRaptorSuboptimalPathProfileRouter(
                network,
                oneModeRequest,
                oneModeAccess,
                oneModeEgress,
                (t) -> new SuboptimalDominatingList(oneModeRequest.suboptimalMinutes),
                null,
                new McRaptorStatePool(50000)
        );

        for (int i = 0; i < 300; i++) {
            boolean useTwoMode = (i % 2 == 0);
            ProfileRequest activeRequest = useTwoMode ? twoModeRequest : oneModeRequest;
            // Slightly vary window to perturb internal state trajectories.
            activeRequest.fromTime = 7 * 3600 + (i % 60);
            activeRequest.toTime = activeRequest.fromTime + 3600;

            pooledRouter.reset(
                    activeRequest,
                    useTwoMode ? twoModeAccess : oneModeAccess,
                    useTwoMode ? twoModeEgress : oneModeEgress,
                    (t) -> new SuboptimalDominatingList(activeRequest.suboptimalMinutes),
                    null
            );

            Collection<PathWithTimes> paths = pooledRouter.getPaths();
            assertTrue("Expected at least one path at iteration " + i, paths.size() > 0);
            assertAllPathLegIndicesConsistent(paths);
        }
    }

    private void assertAllPathLegIndicesConsistent(Collection<PathWithTimes> paths) {
        for (PathWithTimes path : paths) {
            for (int i = 0; i < path.patterns.length; i++) {
                int patternIdx = path.patterns[i];
                int boardPos = path.boardStopPositions[i];
                int alightPos = path.alightStopPositions[i];

                int[] stops = network.transitLayer.tripPatterns.get(patternIdx).stops;
                assertTrue(boardPos >= 0);
                assertTrue(alightPos >= 0);
                assertTrue(boardPos < stops.length);
                assertTrue(alightPos < stops.length);
                assertEquals(path.boardStops[i], stops[boardPos]);
                assertEquals(path.alightStops[i], stops[alightPos]);
            }
        }
    }

    private Set<String> extractRouteIds(Collection<PathWithTimes> paths) {
        return paths.stream()
                .filter(p -> p.patterns.length > 0)
                .map(p -> network.transitLayer.routes.get(network.transitLayer.tripPatterns.get(p.patterns[0]).routeIndex).route_id)
                .collect(Collectors.toSet());
    }
}
