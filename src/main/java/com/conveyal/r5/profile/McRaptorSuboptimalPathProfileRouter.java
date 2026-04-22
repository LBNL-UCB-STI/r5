package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.streets.McRaptorStatePool;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripFlag;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A profile routing implementation which uses McRAPTOR to store bags of arrival times and paths per
 * vertex, so we can find suboptimal paths. We're not using range-RAPTOR here, yet, as the obvious implementation
 * produces some very strange paths for reasons I do not fully understand.
 *
 * @author mattwigway
 */
public class McRaptorSuboptimalPathProfileRouter {
    
    private static final Logger LOG = LoggerFactory.getLogger(McRaptorSuboptimalPathProfileRouter.class);
    private final McRaptorStatePool statePool;
    private static final int MAX_DETERMINISTIC_DEPARTURE_COUNT = 10;

    public static final int BOARD_SLACK = 60;

    public static final int[] EMPTY_INT_ARRAY = new int[0];

    private final boolean DUMP_STOPS = false;

    /** Use a list for the iterations since we aren't sure how many there will be (we're using random sampling over the departure minutes) */
    public List<int[]> timesAtStopsEachIteration = new ArrayList<>();

    private TransportNetwork network;
    private ProfileRequest request;
    private Map<LegMode, TIntIntMap> accessTimes;
    private Map<LegMode, TIntIntMap> egressTimes = null;
    private InRoutingFareCalculator.Collater collapseParetoSurfaceToTime;

    private FrequencyRandomOffsets offsets;

    private final TIntObjectMap<McRaptorStateBag> bestStates;

    private int round = 0;
    private int departureTime;

    private BitSet touchedStops;
    private BitSet touchedPatterns;
    private BitSet patternsNearDestination;
    private BitSet servicesActive;
    // Used in creating the McRaptorStateBag; the type of list supplied determines the domination rules. Receives the departure time as an argument.
    private IntFunction<DominatingList> listSupplier;
    private MersenneTwister mersenneTwister;

    /** In order to properly do target pruning we store the best times at each target _by access mode_, so car trips don't quash walk trips */
    private TObjectIntMap<LegMode> bestTimesAtTargetByAccessMode = new TObjectIntHashMap<>(4, 0.95f, Integer.MAX_VALUE);
    private final List<McRaptorState> statesPerPattern;
    private int[] boardStopPositionsArray;
    private int[] boardTimesForFrequencyArray;
    private int[] tripIndicesArray;
    private int statesPerPatternSize;  // Track logical size
    private int[] touchedStopsLastRound = new int[1000];
    private int[] touchedStopsInRound = new int[1000];
    private int touchedStopsLastRoundSize = 0;
    private int touchedStopsInRoundSize = 0;
    private final BitSet stopsTouchedByTransfer;
    private final TIntObjectMap<Collection<McRaptorState>> bestStatesBeforeRound;

    private List<int[]> travelTimeArrayPool = new ArrayList<>(200);
    private int nextTravelTimeArray = 0;

    private LegMode[] egressModesArray;
    private TIntIntMap[] egressTimesArray;
    private int egressArraySize = 0;
    private final AtomicBoolean inUse = new AtomicBoolean(false);
    private long routeInvocationSequence = 0;
    private long activeRouteInvocationId = 0;
    private int activeDepartureIndex = -1;
    /** Number of departure times sampled in the most recent route invocation. */
    private int lastSampledDepartureCount = 0;

    public McRaptorSuboptimalPathProfileRouter(
            TransportNetwork network,
            ProfileRequest req,
            Map<LegMode, TIntIntMap> accessTimes,
            Map<LegMode, TIntIntMap> egressTimes,
            IntFunction<DominatingList> listSupplier,
            InRoutingFareCalculator.Collater collapseParetoSurfaceToTime
    ) {
        this(network, req, accessTimes, egressTimes, listSupplier, collapseParetoSurfaceToTime,
                new McRaptorStatePool(50000));
    }

    public McRaptorSuboptimalPathProfileRouter (TransportNetwork network, ProfileRequest req, Map<LegMode,
            TIntIntMap> accessTimes, Map<LegMode, TIntIntMap> egressTimes, IntFunction<DominatingList> listSupplier,
                                                InRoutingFareCalculator.Collater collapseParetoSurfaceToTime, McRaptorStatePool statePool) {
        this.network = network;
        this.request = req;
        this.accessTimes = accessTimes;
        this.egressTimes = egressTimes;
        this.listSupplier = listSupplier;
        this.collapseParetoSurfaceToTime = collapseParetoSurfaceToTime;
        this.touchedStops = new BitSet(network.transitLayer.getStopCount());
        this.touchedPatterns = new BitSet(network.transitLayer.tripPatterns.size());
        this.patternsNearDestination = new BitSet(network.transitLayer.tripPatterns.size());
        this.servicesActive = network.transitLayer.getActiveServicesForDate(req.date);
        this.offsets = new FrequencyRandomOffsets(network.transitLayer);
        // To make results repeatable from one run to the next, seed with some characteristic of the request itself,
        // e.g. (int) (request.fromLat * 1e9).  Leaving out an argument will make it use a combination of time and
        // the instance's identity hash code, which makes it truly random for all practical purposes.
        this.mersenneTwister = new MersenneTwister((int) (request.fromLat * 1e9));
        // Pre-size for typical transit networks (most have < 10k stops)
        int estimatedStops = Math.min(network.transitLayer.getStopCount(), 10000);
        this.bestStates = new TIntObjectHashMap<>(estimatedStops, 0.75f);
        this.statesPerPattern = new ArrayList<>(100);
        this.boardStopPositionsArray = new int[100];
        this.boardTimesForFrequencyArray = new int[100];
        this.tripIndicesArray = new int[100];
        this.statesPerPatternSize = 0;
        this.stopsTouchedByTransfer = new BitSet(network.transitLayer.getStopCount());
        this.bestStatesBeforeRound = new TIntObjectHashMap<>(estimatedStops, 0.75f);

        this.statePool = statePool;

        updateEgressArrays(egressTimes);
    }

    /**
     * Reset this router for reuse with new parameters.
     * This allows router pooling to avoid repeated object allocation.
     */
    public void reset(
            ProfileRequest req,
            Map<LegMode, TIntIntMap> accessTimes,
            Map<LegMode, TIntIntMap> egressTimes,
            IntFunction<DominatingList> listSupplier,
            InRoutingFareCalculator.Collater collapseParetoSurfaceToTime
    ) {
        if (inUse.get()) {
            throw new IllegalStateException(
                    "McRaptorSuboptimalPathProfileRouter.reset called while router is in use. " +
                            "Router instances are not thread-safe and must not be reused concurrently."
            );
        }

        // Update parameters
        this.request = req;
        this.accessTimes = accessTimes;
        this.egressTimes = egressTimes;
        this.listSupplier = listSupplier;
        this.collapseParetoSurfaceToTime = collapseParetoSurfaceToTime;

        // Clear search state (just clears references, doesn't affect the pool)
        this.bestStates.clear();
        this.bestStatesBeforeRound.clear();
        this.timesAtStopsEachIteration.clear();
        this.touchedStops.clear();
        this.touchedPatterns.clear();
        this.patternsNearDestination.clear();
        this.stopsTouchedByTransfer.clear();
        this.bestTimesAtTargetByAccessMode.clear();
        this.round = 0;
        this.departureTime = 0;

        // Reset state pool - this makes ALL pre-allocated states available again
        // Doesn't matter what's referencing them - we're about to overwrite those references
        this.statePool.reset();

        // Update date-dependent state
        this.servicesActive = network.transitLayer.getActiveServicesForDate(req.date);
        this.mersenneTwister = new MersenneTwister((int) (request.fromLat * 1e9));
        this.offsets = new FrequencyRandomOffsets(network.transitLayer);

        updateEgressArrays(egressTimes);

        nextTravelTimeArray = 0;

        this.statesPerPatternSize = 0;
        this.touchedStopsInRoundSize = 0;
        this.touchedStopsLastRoundSize = 0;
    }

    private void updateEgressArrays(Map<LegMode, TIntIntMap> egressTimes) {
        if (egressTimes == null || egressTimes.isEmpty()) {
            this.egressModesArray = null;
            this.egressTimesArray = null;
            this.egressArraySize = 0;
            return;
        }

        int size = egressTimes.size();
        if (this.egressModesArray == null || this.egressModesArray.length < size) {
            this.egressModesArray = new LegMode[size];
            this.egressTimesArray = new TIntIntMap[size];
        }

        int i = 0;
        for (Map.Entry<LegMode, TIntIntMap> entry : egressTimes.entrySet()) {
            this.egressModesArray[i] = entry.getKey();
            this.egressTimesArray[i] = entry.getValue();
            i++;
        }

        // Clear trailing references when the number of egress modes shrinks.
        for (int j = i; j < this.egressArraySize; j++) {
            this.egressModesArray[j] = null;
            this.egressTimesArray[j] = null;
        }
        this.egressArraySize = i;
    }

    public McRaptorStatePool getStatePool() {
        return statePool;
    }

    public int getStatePoolMaxInUse() {
        return statePool.getMaxInUse();
    }

    public int getStatePoolExhaustionsSinceReset() {
        return statePool.getExhaustionsSinceReset();
    }

    /** Returns the number of departure times sampled in the most recent route invocation. */
    public int getLastSampledDepartureCount() {
        return lastSampledDepartureCount;
    }

    /** Get a McRAPTOR state bag for every departure minute */
    public Collection<McRaptorState> route () {
        if (!inUse.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "McRaptorSuboptimalPathProfileRouter is already in use. " +
                            "Router instances are not thread-safe and must not be reused concurrently."
            );
        }
        try {
            return routeInternal();
        } finally {
            inUse.set(false);
        }
    }

    /** Internal route implementation; caller must hold the inUse lock. */
    private Collection<McRaptorState> routeInternal () {
            // Modeify does its own pre-computation of accessTimes, but Analysis does not
            if (accessTimes == null) computeAccessTimes();

            // Reset pool once per route. For multi-departure sampling, pooled states can still be referenced
            // across samples (e.g., destination states), so do not reset between departures unless results
            // are fully consumed or destination states are copied/non-pooled.
            statePool.reset();

            long startTime = System.currentTimeMillis();

            // Optimization for modeify (PointToPointQuery): find patterns near destination
            // on the final round of the search we only explore these patterns
            if (this.egressTimes != null) {
                this.egressTimes.values().forEach(times -> times.forEachKey(s -> {
                    network.transitLayer.patternsForStop.get(s).forEach(p -> {
                        patternsNearDestination.set(p);
                        return true;
                    });
                    return true;
                }));
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{} patterns found near the destination", patternsNearDestination.cardinality());
                }
            }

            List<McRaptorState> codominatingStatesToBeReturned = new ArrayList<>();

            // We start at end of time window and work backwards (which is what range-RAPTOR does, in case we
            // re-implement that here). We use a constrained random walk to choose which departure minutes to sample as we
            // work backward through the time window.  According to others (Owen and Jiang?), this is a good way to reduce
            // the number of samples without causing an issue with variance in results.  This value is the constraint
            // (upper limit) on the walk.
            // multiply by two because E[random] = 1/2 * max.
            if (request.monteCarloDraws == 200) {
                // 200 draws will take a really long time and is probably not what is desired. It is more likely the user simply
                // forgot to change the number of draws in the
                throw new IllegalArgumentException("Monte Carlo draws set to UI default, this is probably not what you want, exiting. " +
                        "Each draw in the fare-based router can be quite slow, so you probably want a smaller number. " +
                        "If you _really_ want 200 draws, maybe you'd be happy with 199 or 201, which will prevent " +
                        "this error?");
            }

            if (request.monteCarloDraws != 0) {
                LOG.warn("BEAM fork requires monteCarloDraws=0, got {}", request.monteCarloDraws);
                throw new IllegalArgumentException("BEAM fork requires monteCarloDraws=0");
            }

            ArrayList<Integer> departureTimes = generateDepartureTimesToSample(request);
            lastSampledDepartureCount = departureTimes.size();

            // Only enforce exact count for Monte Carlo mode
            // In deterministic mode (monteCarloDraws == 0), use whatever was generated
            if (request.monteCarloDraws > 0) {
                while (departureTimes.size() != request.monteCarloDraws) {
                    departureTimes = generateDepartureTimesToSample(request);
                }
            }

            for (int n = 0; n < departureTimes.size(); n++) {
                activeRouteInvocationId = ++routeInvocationSequence;
                activeDepartureIndex = n;
                departureTime = departureTimes.get(n);

                // we're not using range-raptor so it's safe to change the schedule on each search
                if (request.monteCarloDraws > 0) {
                    offsets.randomize();
                }

                bestStates.clear();
                // Ensure no stale per-round snapshots survive across sampled departures.
                bestStatesBeforeRound.clear();
                // Reuse existing state bags across departures in the same route invocation.
                statePool.resetStateBags();
                touchedPatterns.clear();
                touchedStops.clear();
                touchedStopsLastRoundSize = 0;
                // Round 0 is in essence non-transit access.
                round = 0;
                // final to allow use in the lambda function below
                final int finalDepartureTime = departureTime;

                // enqueue/relax access times, which are seconds of travel time (not clock time) by mode from the origin
                // to nearby stops
                for (Map.Entry<LegMode, TIntIntMap> entry : accessTimes.entrySet()) {
                    LegMode mode = entry.getKey();
                    TIntIntMap times = entry.getValue();

                    TIntIntIterator it = times.iterator();
                    while (it.hasNext()) {
                        it.advance();
                        int stop = it.key();
                        int accessTime = it.value();
                        if (addState(stop, -1, -1, finalDepartureTime + accessTime, -1, -1, -1, null, mode))
                            touchedStops.set(stop);
                    }
                }

                markPatterns();

                round++;

                // NB the walk search is an initial round, so MAX_ROUNDS + 1
                while (doOneRound() && round < request.maxRides + 1);

                for (int i = 0; i < touchedStopsLastRoundSize; i++) {
                    int stop = touchedStopsLastRound[i];
                    bestStatesBeforeRound.remove(stop);
                }

                // TODO this means we wind up with some duplicated states.
                if (egressTimes != null) {
                    // In a PointToPointQuery (for Modeify), egressTimes will already be computed
                    codominatingStatesToBeReturned.addAll(doPropagationToDestination(finalDepartureTime));
                }
                if (collapseParetoSurfaceToTime != null) {
                    collateTravelTimes(departureTime);
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("minute {} / {}", n + 1, departureTimes.size());
                }
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("McRAPTOR took {}ms", System.currentTimeMillis() - startTime);
            }

            // will be empty unless this is for a PointToPointQuery.
            return codominatingStatesToBeReturned;
    }

    /** compute access times based on the profile request. NB this does not do a search-per-mode */
    private void computeAccessTimes() {
        StreetRouter streetRouter = new StreetRouter(network.streetLayer);

        EnumSet<LegMode> modes = request.accessModes;
        LegMode mode;
        if (modes.contains(LegMode.CAR)) {
            streetRouter.streetMode = StreetMode.CAR;
            mode = LegMode.CAR;
        } else if (modes.contains(LegMode.BICYCLE)) {
            streetRouter.streetMode = StreetMode.BICYCLE;
            mode = LegMode.BICYCLE;
        } else {
            streetRouter.streetMode = StreetMode.WALK;
            mode = LegMode.WALK;
        }

        streetRouter.profileRequest = request;

        // TODO add time and distance limits to routing, not just weight.
        // TODO apply walk and bike speeds and maxBike time.
        streetRouter.distanceLimitMeters = TransitLayer.WALK_DISTANCE_LIMIT_METERS; // FIXME arbitrary, and account for bike or car access mode
        streetRouter.setOrigin(request.fromLat, request.fromLon);
        streetRouter.route();
        streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        accessTimes = new HashMap<>();
        accessTimes.put(mode, streetRouter.getReachedStops());
    }

    /** dump out all stop names, for debugging */
    public String dumpStops (TIntIntMap stops) {
        if (DUMP_STOPS) {
            StringBuilder sb = new StringBuilder();

            stops.forEachEntry((stop, time) -> {
                String stopName = network.transitLayer.stopNames.get(stop);
                sb.append(String.format("%s (%d) at %sm %ss\n", stopName, stop, time / 60, time % 60));
                return true;
            });

            return sb.toString();
        } else {
            return "";
        }
    }

    /** Perform a McRAPTOR search and extract paths */
    public Collection<PathWithTimes> getPaths() {
        if (!inUse.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "McRaptorSuboptimalPathProfileRouter is already in use. " +
                            "Router instances are not thread-safe and must not be reused concurrently."
            );
        }
        try {
            Collection<McRaptorState> states = routeInternal();

            // A map to keep track of the best path among each group of paths using the same sequence of patterns.
            // We will often find multiple paths that board or transfer to the same patterns at different locations.
            // We only want to retain the best set of boarding, transfer, and alighting stops for a particular pattern sequence.
            // FIXME we are using a map here with unorthodox definitions of hashcode and equals to make them serve as map keys.
            // We should instead wrap PathWithTimes or copy the relevant fields into a PatternSequenceKey class.
            Map<PathWithTimes, PathWithTimes> paths = new HashMap<>();

            //  Manual iteration - no lambda allocation
            for (McRaptorState s : states) {
                TIntIntMap access = accessTimes.get(s.accessMode);
                TIntIntMap egress = egressTimes.get(s.egressMode);
                if (access == null || egress == null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Skipping path: missing access/egress times (accessMode={}, egressMode={})",
                                s.accessMode, s.egressMode);
                    }
                    continue;
                }
                try {
                    PathWithTimes pwt = new PathWithTimes(s, network, request, access, egress);

                    //  Single lookup instead of containsKey + get
                    PathWithTimes existing = paths.get(pwt);
                    if (existing == null || existing.stats.avg > pwt.stats.avg) {
                        paths.put(pwt, pwt);
                    }
                } catch (IllegalArgumentException ex) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Skipping path during timing reconstruction: {}", ex.getMessage());
                    }
                } finally {
                    // Intentionally no per-state return during active routing.
                    // All pooled state instances are reclaimed together at statePool.reset()
                    // before the next route invocation.
                }
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("{} states led to {} paths", states.size(), paths.size());
                paths.values().forEach(p -> LOG.debug("{}", p.dump(network)));
            }

            return new ArrayList<>(paths.values());
        } finally {
            inUse.set(false);
        }
    }

    private void ensureCapacity(int needed) {
        if (needed > statesPerPattern.size()) {
            // Grow list
            while (statesPerPattern.size() < needed) {
                statesPerPattern.add(null);
            }
        }
        if (needed > boardStopPositionsArray.length) {
            // Grow arrays
            int newCapacity = Math.max(needed, boardStopPositionsArray.length * 2);
            boardStopPositionsArray = Arrays.copyOf(boardStopPositionsArray, newCapacity);
            boardTimesForFrequencyArray = Arrays.copyOf(boardTimesForFrequencyArray, newCapacity);
            tripIndicesArray = Arrays.copyOf(tripIndicesArray, newCapacity);
        }
    }

    private void ensureStopCapacity(int needed) {
        if (needed > touchedStopsInRound.length) {
            int newCapacity = Math.max(needed, touchedStopsInRound.length * 2);
            touchedStopsInRound = Arrays.copyOf(touchedStopsInRound, newCapacity);
            touchedStopsLastRound = Arrays.copyOf(touchedStopsLastRound, newCapacity);
        }
    }

    /** perform one round of the McRAPTOR search. Returns true if anything changed */
    private boolean doOneRound() {
        for (int i = 0; i < touchedStopsLastRoundSize; i++) {
            int stop = touchedStopsLastRound[i];
            bestStatesBeforeRound.remove(stop);
        }
        // Populate new entries and track which stops we touched this round
        touchedStopsInRoundSize = 0;

        bestStates.forEachEntry((stop, bag) -> {
            // Snapshot states from previous round to avoid ConcurrentModificationException
            // and ensure boarding is based only on states already present at start of round.
            List<McRaptorState> snapshot = new ArrayList<>();
            for (McRaptorState s : bag.getBestStates()) {
                if (s.round == round - 1) snapshot.add(s);
            }
            bestStatesBeforeRound.put(stop, snapshot);

            // Track this stop for next round's cleanup
            ensureStopCapacity(touchedStopsInRoundSize + 1);
            touchedStopsInRound[touchedStopsInRoundSize++] = stop;

            return true;
        });

        // Swap arrays for next round (zero allocation!)
        int[] temp = touchedStopsLastRound;
        touchedStopsLastRound = touchedStopsInRound;
        touchedStopsInRound = temp;
        touchedStopsLastRoundSize = touchedStopsInRoundSize;

        // optimization: on the last round, only explore patterns near the destination in a point to point search
        if (round == request.maxRides && egressTimes != null)
            touchedPatterns.and(patternsNearDestination);

        for (int patIdx = touchedPatterns.nextSetBit(0); patIdx >= 0; patIdx = touchedPatterns.nextSetBit(patIdx + 1)) {
            // Clear and reuse pattern-level collections (instead of creating new)
            for (int i = 0; i < statesPerPatternSize; i++) {
                statesPerPattern.set(i, null);
            }
            statesPerPatternSize = 0;

            TripPattern pattern = network.transitLayer.tripPatterns.get(patIdx);
            RouteInfo routeInfo = network.transitLayer.routes.get(pattern.routeIndex);
            TransitModes mode = TransitLayer.getTransitModes(routeInfo.route_type);

            if (!pattern.servicesActive.intersects(servicesActive) ||
                    !request.transitModes.contains(mode)) {
                continue;
            }
            List<TripSchedule> tripSchedules = pattern.tripSchedules;
            int[] patternStops = pattern.stops;

            // ride along the entire pattern, picking up states as we go
            for (int stopPositionInPattern = 0; stopPositionInPattern < patternStops.length; stopPositionInPattern++) {
                int stop = patternStops[stopPositionInPattern];

                if (request.wheelchair) {
                    if (!network.transitLayer.stopsWheelchair.get(stop)) {
                        continue;
                    }
                }

                Collection<McRaptorState> statesAtStopFromPreviousRound = bestStatesBeforeRound.get(stop);
                boolean stopReachedViaDifferentPattern = statesAtStopFromPreviousRound != null;

                // get off the bus, if we can
                // Use the field instead of local var
                for (int i = 0; i < statesPerPatternSize; i++) {
                    McRaptorState state = statesPerPattern.get(i);
                    int tripIndexInPattern = tripIndicesArray[i];
                    int boardStopPosition = boardStopPositionsArray[i];
                    TripSchedule sched = tripSchedules.get(tripIndexInPattern);
                    int boardTime = boardTimesForFrequencyArray[i];
                    int arrival;  // Declare arrival here

                    if (sched.headwaySeconds != null) {
                        // For frequency trips, boardTime is already loaded from the array
                        int travelTimeToStop = sched.arrivals[stopPositionInPattern] - sched.departures[boardStopPosition];
                        arrival = boardTime + travelTimeToStop;
                    } else {
                        // For scheduled trips, get both from the schedule
                        arrival = sched.arrivals[stopPositionInPattern];
                        boardTime = sched.departures[boardStopPosition];
                    }

                    if (addState(stop, boardStopPosition, stopPositionInPattern, arrival, boardTime, patIdx,
                            tripIndexInPattern, state))
                        touchedStops.set(stop);
                }

                // get on the bus, if we can
                if (stopReachedViaDifferentPattern) {
                    for (McRaptorState state : statesAtStopFromPreviousRound) {
                        if (state.stop != stop) {
                            continue;
                        }
                        if (state.round != round - 1) continue;

                        if (pattern.hasFrequencies && pattern.hasSchedules) {
                            throw new IllegalStateException("McRAPTOR router does not support frequencies and schedules in the same trip pattern!");
                        }

                        int currentTrip = -1;

                        if (pattern.hasSchedules) {
                            int earliestPossibleBoardTime = state.time + BOARD_SLACK;
                            for (TripSchedule tripSchedule : tripSchedules) {
                                currentTrip++;
                                //Skips trips which don't run on wanted date
                                if (!servicesActive.get(tripSchedule.serviceCode) ||
                                    //Skip trips that can't be used with wheelchairs when wheelchair trip is requested
                                    (request.wheelchair && !tripSchedule.getFlag(TripFlag.WHEELCHAIR))) {
                                    continue;
                                }

                                int departure = tripSchedule.departures[stopPositionInPattern];
                                if (departure > earliestPossibleBoardTime) {
                                    // Add to the field
                                    ensureCapacity(statesPerPatternSize + 1);
                                    statesPerPattern.set(statesPerPatternSize, state);
                                    tripIndicesArray[statesPerPatternSize] = currentTrip;
                                    boardStopPositionsArray[statesPerPatternSize] = stopPositionInPattern;
                                    statesPerPatternSize++;

                                    // we found the best trip we can board at this stop based on travel time (we know this because trips
                                    // are sorted by departure time from first stop), break loop regardless of whether
                                    // we decided to board it or continue on a trip coming from a previous stop.

                                    // NB there is an assumption here that a user will take the first vehicle that comes
                                    // on the desired pattern. It is possible to imagine a situation in which this is not
                                    // completely correct. If there are peak and off-peak fares, it may make sense to arrive
                                    // at a transfer point and allow a on-peak vehicle to pass in order to get on the next vehicle
                                    // which just so happens to arrive after peak. I do not doubt that someone, somewhere, does this.
                                    // There are reasons to do this at a transfer point. Suppose that there are peak and off-peak
                                    // fares for a rail system but not a connecting bus system (e.g., WMATA in DC). Suppose that the bus only
                                    // comes every hour. If you take the 8:30 AM (hourly) bus, you arrive at the rail station at 8:50 - still in peak time.
                                    // However, if you allow the 8:55 on-peak train to pass and take the off-peak 9:01, you stand to save some money.
                                    // You can't leave your house later, because the feeder bus isn't coming again until 9:30.
                                    // This isn't a problem for the almost certainly more common situation of people delaying
                                    // their trips to save money, as that should be accounted for by the time window (and if you
                                    // wanted to consider a trip that nominally departed at 8:30 but involved waiting to depart until 9:00
                                    // to get the best fare, you could achieve that through post-processing.
                                    break;
                                }
                            }
                        } else if (pattern.hasFrequencies) {
                            int earliestPossibleBoardTime = state.time + BOARD_SLACK;
                            int[][] offsetsForPattern = offsets.offsets.get(patIdx);
                            for (TripSchedule tripSchedule : tripSchedules) {
                                currentTrip++;
                                if (!servicesActive.get(tripSchedule.serviceCode) ||
                                    //Skip trips that can't be used with wheelchairs when wheelchair trip is requested
                                    (request.wheelchair && !tripSchedule.getFlag(TripFlag.WHEELCHAIR))) {
                                    continue;
                                }

                                int[] offsetsForTrip = offsetsForPattern[currentTrip];

                                // find a departure on this trip
                                for (int frequencyEntry = 0; frequencyEntry < tripSchedule.startTimes.length; frequencyEntry++) {
                                    // we have to check all trips and frequency entries because, unlike
                                    // schedule-based trips, these are not sorted
                                    int departure = tripSchedule.startTimes[frequencyEntry] +
                                            offsetsForTrip[frequencyEntry] +
                                            tripSchedule.departures[stopPositionInPattern];

                                    int latestDeparture = tripSchedule.endTimes[frequencyEntry] +
                                            tripSchedule.departures[stopPositionInPattern];

                                    if (earliestPossibleBoardTime > latestDeparture) continue;

                                    while (departure < earliestPossibleBoardTime)
                                        departure += tripSchedule.headwaySeconds[frequencyEntry];

                                    // check again, because depending on the offset, the latest possible departure based
                                    // on end time may not actually occur
                                    if (departure > latestDeparture) continue;

                                    ensureCapacity(statesPerPatternSize + 1);
                                    statesPerPattern.set(statesPerPatternSize, state);
                                    tripIndicesArray[statesPerPatternSize] = currentTrip;
                                    boardTimesForFrequencyArray[statesPerPatternSize] = departure;  // Note: 'departure', not boardTime
                                    boardStopPositionsArray[statesPerPatternSize] = stopPositionInPattern;
                                    statesPerPatternSize++;
                                }
                            }
                        }
                    }
                }
            }
        }

        doTransfers();
        markPatterns();
        round++;

        return !touchedPatterns.isEmpty();
    }

    /** Perform transfers */
    private void doTransfers () {
        stopsTouchedByTransfer.clear();
        double walkSpeedMillimetersPerSecond = request.walkSpeed * 1000;
        for (int stop = touchedStops.nextSetBit(0); stop >= 0; stop = touchedStops.nextSetBit(stop + 1)) {
            TIntList transfers = network.transitLayer.transfersForStop.get(stop);

            // okay to use bestStates directly here, it doesn't allow the router to ride two transit vehicles in one round.
            // because doTransfers only creates transfer states, it does not affect nonTransfer states.
            for (McRaptorState state : bestStates.get(stop).getNonTransferStates()) {
                for (int transfer = 0; transfer < transfers.size(); transfer += 2) {
                    int toStop = transfers.get(transfer);
                    int distanceMillimeters = transfers.get(transfer + 1);
                    int walkTimeSeconds = (int)(distanceMillimeters / walkSpeedMillimetersPerSecond);
                    if (addState(toStop, -1, -1, state.time + walkTimeSeconds, -1, -1, -1, state)) {
                        String to = network.transitLayer.stopNames.get(transfers.get(transfer));
                        //LOG.info("Transfer from {} to {} is optimal", from, to);

                        stopsTouchedByTransfer.set(toStop);
                    }
                }
            }
        }

        // copy all stops touched by transfers into the touched stops bitset.
        touchedStops.or(stopsTouchedByTransfer);
    }

    /** propagate states to the destination in a point-to-point search */
    private Collection<McRaptorState> doPropagationToDestination(int departureTime) {
        McRaptorStateBag bag = createStateBag(departureTime);

        for (Map.Entry<LegMode, TIntIntMap> egressEntry : egressTimes.entrySet()) {
            LegMode mode = egressEntry.getKey();
            TIntIntMap times = egressEntry.getValue();

            times.forEachEntry((stop, egressTime) -> {
                McRaptorStateBag bagAtStop = bestStates.get(stop);
                if (bagAtStop == null) return true;

                for (McRaptorState state : bagAtStop.getNonTransferStates()) {
                    McRaptorState stateAtDest = statePool.borrow();
                    boolean stateAtDestFromPool = true;
                    if (stateAtDest == state) {
                        // Defensive guard: if the pool hands back a still-live state, avoid creating a self-cycle.
                        stateAtDest = new McRaptorState();
                        stateAtDestFromPool = false;
                    }
                    stateAtDest.back = state;
                    stateAtDest.pattern = -1;
                    stateAtDest.trip = -1;
                    stateAtDest.stop = -1;
                    stateAtDest.accessMode = state.accessMode;
                    stateAtDest.egressMode = mode;
                    stateAtDest.time = state.time + egressTime;

                    // Safe early return: destination states are terminal and local to this method.
                    // If rejected, this instance is unreachable from all live search structures.
                    boolean retained = bag.add(stateAtDest);
                    if (!retained && stateAtDestFromPool) {
                        statePool.returnState(stateAtDest);
                    }
                }

                return true;
            });
        }

        return bag.getBestStates();
    }

    private ArrayList<Integer> generateDepartureTimesToSample(ProfileRequest request) {
        int count = Math.max(1, Math.min(request.mcRaptorDeterministicDepartureCount, MAX_DETERMINISTIC_DEPARTURE_COUNT));
        int step = Math.max(1, request.mcRaptorDeterministicDepartureStepSeconds);

        ArrayList<Integer> departureTimes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int t = request.fromTime + i * step;
            if (t >= request.toTime) break;
            departureTimes.add(t);
        }

        // Defensive guard for degenerate windows.
        if (departureTimes.isEmpty()) {
            departureTimes.add(request.fromTime);
        }

        // Process from latest to earliest to align with target pruning assumptions.
        Collections.reverse(departureTimes);
        return departureTimes;
    }

    private void collateTravelTimes(int departureTime) {
        int[] timesAtStopsThisIteration;
        if (nextTravelTimeArray < travelTimeArrayPool.size()) {
            timesAtStopsThisIteration = travelTimeArrayPool.get(nextTravelTimeArray++);
        } else {
            timesAtStopsThisIteration = new int[network.transitLayer.getStopCount()];
            travelTimeArrayPool.add(timesAtStopsThisIteration);
            nextTravelTimeArray++;
        }
        Arrays.fill(timesAtStopsThisIteration, FastRaptorWorker.UNREACHED);

        final int maxClockTime = departureTime + request.maxTripDurationMinutes * 60;
        bestStates.forEachEntry((stop, bag) -> {
            int bestClockTimeGivenConstraint = collapseParetoSurfaceToTime.collate(bag.getNonTransferStates(),
                    maxClockTime);
            if (bestClockTimeGivenConstraint != FastRaptorWorker.UNREACHED) {
                timesAtStopsThisIteration[stop] = bestClockTimeGivenConstraint - departureTime;
            }
            return true;
        });

        timesAtStopsEachIteration.add(timesAtStopsThisIteration);
    }

    public int[][] getBestTimes() {
        return timesAtStopsEachIteration.toArray(new int[timesAtStopsEachIteration.size()][]);
    }

    /** Mark patterns at touched stops, to be explored in a subsequent round */
    private void markPatterns () {
        this.touchedPatterns.clear();

        for (int stop = touchedStops.nextSetBit(0); stop >= 0; stop = touchedStops.nextSetBit(stop + 1)) {
            network.transitLayer.patternsForStop.get(stop).forEach(pat -> {
                this.touchedPatterns.set(pat);
                return true;
            });
        }

        this.touchedStops.clear();
    }

    private boolean addState (int stop, int boardStopPosition, int alightStopPosition, int time, int boardTime, int
            pattern, int trip, McRaptorState back) {
        return addState(stop, boardStopPosition, alightStopPosition, time, boardTime, pattern, trip, back, back
                .accessMode);
    }


        /** Add a state */
    private boolean addState (int stop, int boardStopPosition, int alightStopPosition, int time, int boardTime, int
            pattern, int trip, McRaptorState back, LegMode accessMode) {
        /**
         * local pruning, and cutting off of excessively long searches
         * NB need to have cutoff be relative to toTime because otherwise when we do range-RAPTOR we'll have left over states
         * that are past the cutoff.
         */
        // cut off excessively long searches
        if (time > request.toTime + request.maxTripDurationMinutes * 60) return false;

        // local pruning iff in suboptimal point-to-point (Modeify) mode
        if (request.maxFare < 0 && time - request.suboptimalMinutes * 60 > bestTimesAtTargetByAccessMode.get(accessMode)) {
            return false;
        }

        if (back != null && back.time > time)
            throw new IllegalStateException("Attempt to decrement time in state!");
        McRaptorState state = statePool.borrow();
        boolean stateFromPool = true;
        if (back != null && state == back) {
            // Defensive guard: if a pooled instance aliases the back-pointer state,
            // setting fields would create state.back == state.
            state = new McRaptorState();
            stateFromPool = false;
        }

        if (back != null) {
            state.setFrom(back, stop, boardStopPosition, alightStopPosition, time, boardTime, pattern, trip, round);
        } else {
            state.setOrigin(stop, time, round, accessMode);
        }


        // sanity check (anecdotally, this has no noticeable effect on speed)
        if (boardStopPosition >= 0) {
            TripPattern patt = network.transitLayer.tripPatterns.get(pattern);
            if (boardStopPosition >= patt.stops.length) {
                if (stateFromPool) statePool.returnState(state);
                return false;
            }
            int boardStop = patt.stops[boardStopPosition];

            if (back == null || boardStop != back.stop) {
                if (stateFromPool) statePool.returnState(state);
                return false;
            }

            if (alightStopPosition < 0 || alightStopPosition >= patt.stops.length) {
                if (stateFromPool) statePool.returnState(state);
                return false;
            }
            if (stop != patt.stops[alightStopPosition]) {
                if (stateFromPool) statePool.returnState(state);
                return false;
            }
        }

        McRaptorStateBag bag = bestStates.get(stop);
        if (bag == null) {
            bag = statePool.borrowStateBag(listSupplier, departureTime);
            bag.bindToStop(stop);
            bestStates.put(stop, bag);
        } else if (bag.getOwnerStop() != stop) {
            throw new IllegalStateException(
                String.format(
                    "McRaptorStateBag owner mismatch before add: routerId=%d routeInvocationId=%d departureIdx=%d " +
                        "round=%d departureTime=%d mapStop=%d bagOwnerStop=%d",
                    System.identityHashCode(this),
                    activeRouteInvocationId,
                    activeDepartureIndex,
                    round,
                    departureTime,
                    stop,
                    bag.getOwnerStop()
                )
            );
        }
        boolean optimal = bag.add(state);

        // Safe early return: if the state was rejected by the bag and came from the pool,
        // it is unreachable from all live search structures and can be recycled immediately.
        if (!optimal && stateFromPool) {
            statePool.returnState(state);
        }

        // target pruning: keep track of best time at destination
        if (egressTimes != null && optimal && pattern != -1) {
            int egressTimeWithSlowestEgressMode = -1;

            // Array iteration - no iterator allocation!
            for (int i = 0; i < egressArraySize; i++) {
                TIntIntMap times = egressTimesArray[i];
                if (!times.containsKey(stop)) continue;
                int timeAtDest = time + times.get(stop);
                egressTimeWithSlowestEgressMode = Math.max(egressTimeWithSlowestEgressMode, timeAtDest);
            }

            if (egressTimeWithSlowestEgressMode != -1 &&
                    egressTimeWithSlowestEgressMode < bestTimesAtTargetByAccessMode.get(accessMode)) {
                bestTimesAtTargetByAccessMode.put(accessMode, egressTimeWithSlowestEgressMode);
            }
        }

        return optimal;
    }

    /** Create a new McRaptorStateBag with properly-configured dominance */
    public McRaptorStateBag createStateBag (int departureTime) {
        return new McRaptorStateBag(() -> listSupplier.apply(departureTime));
    }

    /**
     * This is the McRAPTOR state, which stores a way to get to a stop in a round. It is an object,
     * so there is a certain level of indirection, but note that all of its members are primitives so the entire object
     * can be packed.
     */
    public static class McRaptorState {
        /** what is the previous state? */
        public McRaptorState back;

        /** What is the clock time of this state (seconds since midnight) */
        public int time;

        /** Board time of ride used to reach this stop */
        public int boardTime;

        /** On what pattern did we reach this stop (-1 indicates this is the result of a transfer) */
        public int pattern;

        /** What trip of that pattern did we arrive on */
        public int trip;

        /** the round on which this state was discovered */
        public int round;

        /** What stop are we at */
        public int stop;

        /**
         * What stop position are we at in the pattern?
         *
         * This is needed because the same stop can appear twice in a pattern, see #116.
         */
        public int boardStopPosition;

        /**
         * What stop position in this pattern did we board at?
         */
        public int alightStopPosition;

        /** The mode used to access transit at the start of the trip implied by this state */
        public LegMode accessMode;
        public LegMode egressMode;

        /**
         * The fare to get to this state. Ideally, this wouldn't be here, as it only applies to fare-based routing, not
         * other McRaptor DominatingLists, but this is the easiest place to put it so that it isn't being recalculated
         * all the time (which can be slow if there are table lookups involved).
         */
        public FareBounds fare;
        public String dump(TransportNetwork network) {
            StringBuilder sb = new StringBuilder();
            sb.append("BEGIN PATH DUMP (reverse chronological order, read up)\n");
            McRaptorState state = this;
            while (state != null) {
                String toStop = state.stop == -1 ? "destination" : network.transitLayer.stopNames.get(state.stop);

                if (state.pattern != -1) {
                    RouteInfo ri = network.transitLayer.routes.get(network.transitLayer.tripPatterns.get(state.pattern).routeIndex);
                    sb.append(String.format("%s %s to %s, p%st%s end at %d:%02d\n", ri.route_short_name, ri.route_long_name,
                            toStop, state.pattern, state.trip, state.time / 3600, state.time % 3600 / 60));
                }
                else {
                    sb.append(String.format("transfer via street to %s, end at %d:%02d\n", toStop, state.time / 3600, state.time % 3600 / 60));
                }

                state = state.back;
            }

            sb.append("END PATH DUMP");

            return sb.toString();
        }

        /** Reset this state for reuse in the pool */
        public void reset() {
            this.back = null;
            this.time = 0;
            this.boardTime = 0;
            this.pattern = -1;
            this.trip = -1;
            this.round = 0;
            this.stop = -1;
            this.boardStopPosition = -1;
            this.alightStopPosition = -1;
            this.accessMode = null;
            this.egressMode = null;
            this.fare = null;
        }

        /** Initialize from another state (for extending paths) */
        void setFrom(McRaptorState source, int stop, int boardStopPosition, int alightStopPosition,
                     int time, int boardTime, int pattern, int trip, int round) {
            this.back = source;
            this.stop = stop;
            this.boardStopPosition = boardStopPosition;
            this.alightStopPosition = alightStopPosition;
            this.time = time;
            this.boardTime = boardTime;
            this.pattern = pattern;
            this.trip = trip;
            this.round = round;
            this.accessMode = source.accessMode;
            this.egressMode = source.egressMode;
            this.fare = null;
        }

        /** Initialize from scratch (for origin states) */
        void setOrigin(int stop, int time, int round, LegMode accessMode) {
            this.back = null;
            this.stop = stop;
            this.boardStopPosition = -1;
            this.alightStopPosition = -1;
            this.time = time;
            this.boardTime = -1;
            this.pattern = -1;
            this.trip = -1;
            this.round = round;
            this.accessMode = accessMode;
            this.egressMode = null;
            this.fare = null;
        }

        /** Copy all fields from another state */
        public void copyFrom(McRaptorState other) {
            this.back = other.back;
            this.time = other.time;
            this.boardTime = other.boardTime;
            this.pattern = other.pattern;
            this.trip = other.trip;
            this.round = other.round;
            this.stop = other.stop;
            this.boardStopPosition = other.boardStopPosition;
            this.alightStopPosition = other.alightStopPosition;
            this.accessMode = other.accessMode;
            this.egressMode = other.egressMode;
            this.fare = other.fare;
        }
    }

    /** A bag of states which maintains dominance, and also keeps transfer and non-transfer states separately. */
    public static class McRaptorStateBag {
        /** best states at stops */
        private DominatingList best;

        /** best states for which the preceding step was not a transfer via the street network. States in this list
         * could be reached by multiple transfers farther back in the itinerary, but we need a separate list for
         * stops reached by a direct egress from a transit vehicle without intervening walking along the street
         * network.  This is to avoid circumventing the egress walk limit. */
        private DominatingList nonTransfer;

        private int ownerStop = Integer.MIN_VALUE;

        public McRaptorStateBag(Supplier<DominatingList> factory) {
            this.best = factory.get();
            this.nonTransfer = factory.get();
        }

        public void bindToStop(int stop) {
            this.ownerStop = stop;
        }

        public int getOwnerStop() {
            return ownerStop;
        }

        /** try adding state to the best DominatingList, and to the nonTransfer dominating list if the last step in
         * this state was not a transfer */
        public boolean add (McRaptorState state) {
            if (ownerStop != Integer.MIN_VALUE && state.stop != ownerStop) {
                throw new IllegalStateException(
                    String.format(
                        "McRaptorStateBag cross-stop insert: ownerStop=%d stateStop=%d stateRound=%d statePattern=%d stateTrip=%d stateTime=%d",
                        ownerStop,
                        state.stop,
                        state.round,
                        state.pattern,
                        state.trip,
                        state.time
                    )
                );
            }
            if (state.pattern == -1) {
                // Transfer state: only goes in 'best'
                // Do not recycle evicted states immediately: they may still be referenced
                // by round snapshots or back-pointers used later in this search.
                return best.add(state, evicted -> {});
            } else {
                // Transit state can be stored in both lists. Keep ownership strict:
                // - if retained in both, they must be different objects;
                // - never return a state that may still be retained by one list.
                boolean addedToBest = best.add(state, evicted -> {});

                if (addedToBest) {
                    // Clone only when we truly need two retained instances.
                    McRaptorState copy = new McRaptorState();
                    copy.copyFrom(state);
                    nonTransfer.add(copy, evicted -> {});
                    return true;
                }

                // Not retained in 'best': attempt to retain the borrowed state directly in nonTransfer.
                // If this returns false, caller will return state to pool exactly once.
                return nonTransfer.add(state, evicted -> {});
            }
        }

        public void reset(int departureTime) {
            // Reconfigure existing lists in place for this departure time (no list allocation).
            best.resetForDepartureTime(departureTime);
            nonTransfer.resetForDepartureTime(departureTime);
            ownerStop = Integer.MIN_VALUE;
        }

        /** Check if this bag's dominating lists are of the same class as the example */
        public boolean isCompatible(DominatingList example) {
            return best.getClass().equals(example.getClass());
        }

        /** Update internal dominating lists with parameters from an example list */
        public void updateFrom(DominatingList example) {
            best.updateFrom(example);
            nonTransfer.updateFrom(example);
        }

        public Collection<McRaptorState> getBestStates () {
            return best.getNonDominatedStates();
        }

        public Collection<McRaptorState> getNonTransferStates () {
            return nonTransfer.getNonDominatedStates();
        }
    }

}
