package com.conveyal.r5.streets;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import gnu.trove.iterator.TIntIterator;
import org.locationtech.jts.geom.Envelope;
import gnu.trove.TIntCollection;
import org.apache.commons.math3.util.FastMath;
import org.geotools.referencing.GeodeticCalculator;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a potential split point along an existing edge, retaining some geometric calculation state so that
 * once the best candidate is found more detailed calculations can continue.
 * We don't yet handle initial and final Splits on the same edge. This can be a problem for Analysis if your
 * starting point is on a long road without intersections. Travel times to other points along that road would be
 * measured by going to the end of the road and back.
 */
public class Split {

    private static final Logger LOG = LoggerFactory.getLogger(Split.class);

    /**
     * Thread-local reusable Split objects to avoid allocation in hot path.
     * We need two: one for the current candidate and one for the best candidate.
     */
    private static final ThreadLocal<Split> REUSABLE_CURRENT = ThreadLocal.withInitial(Split::new);
    private static final ThreadLocal<Split> REUSABLE_BEST = ThreadLocal.withInitial(Split::new);

    public int edge = -1;
    public int seg = 0;
    public double frac = 0;
    public int fixedLon;
    public int fixedLat;
    public long distanceToEdge_squaredFixedDegrees = Long.MAX_VALUE;
    public int distanceToEdge_mm = 0;
    public int distance0_mm = 0;
    public int distance1_mm = 0;
    public int vertex0;
    public int vertex1;

    /**
     * Reset this Split to initial state (ready for reuse).
     */
    public void reset() {
        edge = -1;
        seg = 0;
        frac = 0;
        fixedLon = 0;
        fixedLat = 0;
        distanceToEdge_squaredFixedDegrees = Long.MAX_VALUE;
        distanceToEdge_mm = 0;
        distance0_mm = 0;
        distance1_mm = 0;
        vertex0 = 0;
        vertex1 = 0;
    }

    /**
     * Copy all the fields in another Split into this one.
     * This avoids creating large amounts of tiny short-lived objects.
     * Does not copy distanceToEdge_mm, because this is only set at the very end of the operation, on the winning Split.
     */
    public void setFrom(Split other) {
        edge = other.edge;
        seg = other.seg;
        frac = other.frac;
        fixedLon = other.fixedLon;
        fixedLat = other.fixedLat;
        distanceToEdge_squaredFixedDegrees = other.distanceToEdge_squaredFixedDegrees;
    }

    private static GeodeticCalculator distanceCalculator = new GeodeticCalculator(DefaultGeographicCRS.WGS84);

    /**
     * Find a location on an existing street near the given point, without actually creating any vertices or edges.
     * @return a new Split object, or null if no edge was found in range.
     */
    public static Split find(double lat, double lon, double searchRadiusMeters, StreetLayer streetLayer,
                             StreetMode streetMode) {

        // After this conversion, the entire geometric calculation is happening in fixed precision int degrees.
        int fixedLat = VertexStore.floatingDegreesToFixed(lat);
        int fixedLon = VertexStore.floatingDegreesToFixed(lon);

        // We won't worry about the perpendicular walks yet.
        // Just insert or find a vertex on the nearest road and return that vertex.

        final double metersPerDegreeLat = 111111.111;
        double cosLat = FastMath.cos(FastMath.toRadians(lat)); // The projection factor, Earth is a "sphere"

        // Use longs for radii and their square because squaring the fixed-point radius _will_ overflow a signed int32.
        long radiusFixedLat = VertexStore.floatingDegreesToFixed(searchRadiusMeters / metersPerDegreeLat);
        long radiusFixedLon = (int)(radiusFixedLat / cosLat); // Expand the X search space, don't shrink it.
        Envelope envelope = new Envelope(fixedLon, fixedLon, fixedLat, fixedLat);
        envelope.expandBy(radiusFixedLon, radiusFixedLat);
        long squaredRadiusFixedLat = radiusFixedLat * radiusFixedLat;
        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        // Iterate over the set of forward (even) edges that may be near the given coordinate.
        TIntCollection candidateEdges = streetLayer.findEdgesInEnvelope(envelope);
        // The split location currently being examined and the best one seen so far.

        // Reuse thread-local Split objects - ZERO allocation!
        Split curr = REUSABLE_CURRENT.get();
        Split best = REUSABLE_BEST.get();
        curr.reset();
        best.reset();

        // Manual iteration over candidate edges to avoid lambda allocation
        TIntIterator edgeIterator = candidateEdges.iterator();
        while (edgeIterator.hasNext()) {
            int e = edgeIterator.next();
            curr.edge = e;
            edge.seek(e);

            // Do not consider linking to edges that are links to streets from transit stops, P+Rs, and bike shares.
            // These edges allow all modes to traverse, but may be connected to roads with more restrictive permissions.
            // On a given edge pair both directions will have the same flag.
            if (edge.getFlag(EdgeStore.EdgeFlag.LINK)) continue;

            if (!edge.allowsStreetMode(streetMode)) {
                // The edge does not allow forward traversal with the specified mode, try the backward edge.
                edge.advance();
                // If backward traversal is also not allowed, skip this edge and try the next one.
                if (!edge.allowsStreetMode(streetMode)) continue;
            }

            // Keep forEachSegment for now (still allocates lambda but safer)
            edge.forEachSegment((seg, fixedLat0, fixedLon0, fixedLat1, fixedLon1) -> {
                // Find the fraction along the current segment
                curr.seg = seg;
                curr.frac = GeometryUtils.segmentFraction(fixedLon0, fixedLat0, fixedLon1, fixedLat1, fixedLon, fixedLat, cosLat);
                // Project to get the closest point on the segment.
                // Note: the fraction is scaleless, xScale is accounted for in the segmentFraction function.
                curr.fixedLon = (int)(fixedLon0 + curr.frac * (fixedLon1 - fixedLon0));
                curr.fixedLat = (int)(fixedLat0 + curr.frac * (fixedLat1 - fixedLat0));
                // Find squared distance to edge (avoid taking square root, which is slow)
                long dx = (long)((curr.fixedLon - fixedLon) * cosLat);
                long dy = (long)(curr.fixedLat - fixedLat);
                curr.distanceToEdge_squaredFixedDegrees = dx * dx + dy * dy;
                // Ignore segments that are too far away (filter false positives).
                if (curr.distanceToEdge_squaredFixedDegrees < squaredRadiusFixedLat) {
                    if (curr.distanceToEdge_squaredFixedDegrees < best.distanceToEdge_squaredFixedDegrees) {
                        // Update the best segment if we've found something closer.
                        best.setFrom(curr);
                    } else if (curr.distanceToEdge_squaredFixedDegrees == best.distanceToEdge_squaredFixedDegrees
                            && curr.edge < best.edge) {
                        // Break distance ties by favoring lower edge IDs. This makes destination linking
                        // deterministic where centroids are equidistant to edges (see issue #159).
                        best.setFrom(curr);
                    }
                }
            });
        }

        if (best.edge < 0) {
            // No edge found nearby.
            return null;
        }

        // We found an edge. Iterate over its segments again, accumulating distances along its geometry.
        // The distance calculations involve square roots so are deferred to happen here, only on the selected edge.

        Split result = new Split();
        result.setFrom(best);

        edge.seek(result.edge);
        result.vertex0 = edge.getFromVertex();
        result.vertex1 = edge.getToVertex();
        double[] lengthBefore_fixedDeg = new double[1];
        edge.forEachSegment((seg, fLat0, fLon0, fLat1, fLon1) -> {
            // Sum lengths only up to the split point.
            // lengthAfter should be total length minus lengthBefore, which ensures splits do not change total lengths.
            if (seg <= result.seg) {
                double dx = (fLon1 - fLon0) * cosLat;
                double dy = (fLat1 - fLat0);
                double length = FastMath.sqrt(dx * dx + dy * dy);
                if (seg == result.seg) {
                    length *= result.frac;
                }
                lengthBefore_fixedDeg[0] += length;
            }
        });
        // Convert the fixed-precision degree measurements into (milli)meters
        double lengthBefore_floatDeg = VertexStore.fixedDegreesToFloating((int)lengthBefore_fixedDeg[0]);
        result.distance0_mm = (int)(lengthBefore_floatDeg * metersPerDegreeLat * 1000);
        // FIXME perhaps we should be using the sphericalDistanceLibrary here, or the other way around.
        // The initial edge lengths are set using that library on OSM node coordinates, and they are slightly different.
        // We are using a single cosLat value at the linking point, instead of a different value at each segment.
        if (result.distance0_mm < 0) {
            result.distance0_mm = 0;
            LOG.error("Length of first street segment was not positive.");
        }

        if (result.distance0_mm > edge.getLengthMm()) {
            // This mistake happens because the linear distance calculation we're using comes out longer than the
            // spherical distance. The graph remains coherent because we force the two split edge lengths to add up
            // to the original edge length.
            LOG.debug("Length of first street segment was greater than the whole edge ({} > {}).",
                    result.distance0_mm, edge.getLengthMm());
            result.distance0_mm = edge.getLengthMm();
        }
        result.distance1_mm = edge.getLengthMm() - result.distance0_mm;

        // To speed up computation above, square roots were avoided and distanceToEdge_squaredFixedDegrees was
        // calculated using fixed point degrees. We now want to calculate the distance in millimeters, for routing.
        // To do so, we take the square root of distanceToEdge_squaredFixedDegrees, convert to floating point degrees
        // latitude then multiply by the metersPerDegreeLat factor above and 1000 to convert to millimeters.
        // This is accurate enough for our purposes.

        double distanceToEdge_fixedDegrees = FastMath.sqrt(result.distanceToEdge_squaredFixedDegrees);
        double distanceToEdge_floatingDegrees = VertexStore.fixedDegreesToFloating(distanceToEdge_fixedDegrees);
        result.distanceToEdge_mm = (int)(distanceToEdge_floatingDegrees * metersPerDegreeLat * 1000);

        return result;
    }

    /**
     * Find a split on a particular edge.
     */
    public static Split findOnEdge(double lat, double lon, EdgeStore.Edge edge) {

        // After this conversion, the entire geometric calculation is happening in fixed precision int degrees.
        int fixedLat = VertexStore.floatingDegreesToFixed(lat);
        int fixedLon = VertexStore.floatingDegreesToFixed(lon);

        // We won't worry about the perpendicular walks yet.
        // Just insert or find a vertex on the nearest road and return that vertex.

        final double metersPerDegreeLat = 111111.111;
        double cosLat = FastMath.cos(FastMath.toRadians(lat));

        // Reuse thread-local Split objects - ZERO allocation!
        Split curr = REUSABLE_CURRENT.get();
        Split best = REUSABLE_BEST.get();
        curr.reset();
        best.reset();

        curr.edge = edge.edgeIndex;
        best.vertex0 = edge.getFromVertex();
        best.vertex1 = edge.getToVertex();

        double[] lengthBefore_fixedDeg = new double[1];
        edge.forEachSegment((seg, fixedLat0, fixedLon0, fixedLat1, fixedLon1) -> {
            // Find the fraction along the current segment
            curr.seg = seg;
            curr.frac = GeometryUtils.segmentFraction(fixedLon0, fixedLat0, fixedLon1, fixedLat1, fixedLon, fixedLat, cosLat);
            // Project to get the closest point on the segment.
            // Note: the fraction is scaleless, xScale is accounted for in the segmentFraction function.
            curr.fixedLon = (int)(fixedLon0 + curr.frac * (fixedLon1 - fixedLon0));
            curr.fixedLat = (int)(fixedLat0 + curr.frac * (fixedLat1 - fixedLat0));

            double dx = (fixedLon1 - fixedLon0) * cosLat;
            double dy = (fixedLat1 - fixedLat0);
            double length = FastMath.sqrt(dx * dx + dy * dy);

            curr.distance0_mm = (int) ((lengthBefore_fixedDeg[0] + length * curr.frac) * metersPerDegreeLat * 1000);

            lengthBefore_fixedDeg[0] += length;

            curr.distanceToEdge_squaredFixedDegrees = (long)(dx * dx + dy * dy);

            if (curr.distanceToEdge_squaredFixedDegrees < best.distanceToEdge_squaredFixedDegrees) {
                best.setFrom(curr);
            }
        });

        // Copy result into new Split to return
        Split result = new Split();
        result.setFrom(best);
        result.vertex0 = best.vertex0;
        result.vertex1 = best.vertex1;

        // ============================================================================
        // FIX: Calculate both distances using consistent methodology
        // ============================================================================
        // The original code mixed two different distance calculation methods:
        // - distance0_mm used simplified linear approximation from the loop above
        // - distance1_mm was calculated as (edge.getLengthMm() - distance0_mm)
        //   where edge.getLengthMm() used Haversine/spherical distance
        //
        // This caused bugs when the split point projected exactly onto a vertex,
        // because the two methods gave different total lengths, resulting in
        // distance1_mm being non-zero even when the split point was at the end vertex.
        //
        // Solution: Calculate BOTH distances using the same method (GeometryUtils.distance)
        // based on actual coordinates, ensuring consistency.
        // ============================================================================

        // Get actual vertex coordinates for consistent distance calculation
        VertexStore.Vertex vFrom = edge.getEdgeStore().vertexStore.getCursor(result.vertex0);
        VertexStore.Vertex vTo = edge.getEdgeStore().vertexStore.getCursor(result.vertex1);

        // Calculate distance from start vertex to split point using GeometryUtils
        double distanceFromStartToSplit = GeometryUtils.distance(
                vFrom.getLat(), vFrom.getLon(),
                result.fixedLat / 1.0e7, result.fixedLon / 1.0e7
        );

        // Calculate distance from split point to end vertex using GeometryUtils
        double distanceFromSplitToEnd = GeometryUtils.distance(
                result.fixedLat / 1.0e7, result.fixedLon / 1.0e7,
                vTo.getLat(), vTo.getLon()
        );

        // Convert to millimeters
        result.distance0_mm = (int)(distanceFromStartToSplit * 1000.0);
        result.distance1_mm = (int)(distanceFromSplitToEnd * 1000.0);

        // The edge's stored length was calculated using Haversine distance in StreetLayer.makeEdge()
        // Our GeometryUtils.distance should give similar results, but there may be small differences
        // due to rounding or slightly different calculation methods. Ensure the split distances
        // don't exceed the edge's stored length to maintain network consistency.
        int edgeLengthMm = edge.getLengthMm();
        int totalSplitLength = result.distance0_mm + result.distance1_mm;

        if (totalSplitLength > edgeLengthMm) {
            // The sum of split distances exceeds the stored edge length.
            // This can happen due to rounding or calculation method differences.
            // Scale the distances proportionally to fit within the edge length.
            double scale = (double)edgeLengthMm / totalSplitLength;
            result.distance0_mm = (int)(result.distance0_mm * scale);
            result.distance1_mm = edgeLengthMm - result.distance0_mm;

            LOG.debug("Split distances ({} + {} = {}mm) exceed edge length ({}mm), scaled to fit.",
                    (int)(distanceFromStartToSplit * 1000.0),
                    (int)(distanceFromSplitToEnd * 1000.0),
                    totalSplitLength,
                    edgeLengthMm);
        }

        // Additional safety check: ensure non-negative distances
        if (result.distance0_mm < 0) {
            LOG.debug("Calculated distance0_mm was negative ({}), setting to 0", result.distance0_mm);
            result.distance0_mm = 0;
        }
        if (result.distance1_mm < 0) {
            LOG.debug("Calculated distance1_mm was negative ({}), setting to 0", result.distance1_mm);
            result.distance1_mm = 0;
        }

        // Edge case: if split point is at start vertex
        if (result.distance0_mm == 0 && totalSplitLength <= edgeLengthMm) {
            result.distance1_mm = edgeLengthMm;
        }
        // Edge case: if split point is at end vertex
        if (result.distance1_mm == 0 && totalSplitLength <= edgeLengthMm) {
            result.distance0_mm = edgeLengthMm;
        }

        return result;
    }
}
