package com.conveyal.r5.common;

import com.conveyal.r5.streets.VertexStore;
import org.apache.commons.math3.util.FastMath;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;

/**
 * Reimplementation of OTP GeometryUtils, using copied code where there are not licensing concerns.
 */
public class GeometryUtils {
    public static final GeometryFactory geometryFactory = new GeometryFactory();

    // average of polar and equatorial, https://en.wikipedia.org/wiki/Earth
    public static final double RADIUS_OF_EARTH_M = 6_367_450;

    /**
     * Thread-local reusable objects to avoid allocation in hot geometric calculation paths.
     * LineSegment and Coordinate are designed to be mutable (per JTS documentation).
     */
    private static final ThreadLocal<LineSegment> REUSABLE_SEGMENT =
            ThreadLocal.withInitial(LineSegment::new);

    private static final ThreadLocal<Coordinate> REUSABLE_COORDINATE =
            ThreadLocal.withInitial(Coordinate::new);

    /**
     * Haversine formula for distance on the sphere. We used to have a fastDistance function that would estimate this
     * quickly, but I'm not convinced we actually need it.
     * @return distance in meters
     */
    public static double distance (double lat0, double lon0, double lat1, double lon1) {
        double theta0 = FastMath.toRadians(lat0);
        double theta1 = FastMath.toRadians(lat1);
        double lambda0 = FastMath.toRadians(lon0);
        double lambda1 = FastMath.toRadians(lon1);

        double thetaComb = (theta1 - theta0) / 2;
        double lambdaComb = (lambda1 - lambda0) / 2;
        double sin2theta = FastMath.pow(FastMath.sin(thetaComb), 2);
        double sin2lambda = FastMath.pow(FastMath.sin(lambdaComb), 2);

        double underRadical = sin2theta + FastMath.cos(theta0) * FastMath.cos(theta1) * sin2lambda;
        return 2 * RADIUS_OF_EARTH_M * FastMath.asin(FastMath.sqrt(underRadical));
    }

    /**
     * Project the point (fixedLon, fixedLat) onto the line segment from (fixedLon0, fixedLat0) to
     * (fixedLon1, fixedLat1) and return the fractional distance of the projected location along the segment as a
     * double in the range [0, 1]. All coordinates are fixed-precision geographic.
     * Pass in the cosine of the latitude to avoid repeatedly performing the expensive cosine operation.
     */
    public static double segmentFraction(int fixedLon0, int fixedLat0, int fixedLon1, int fixedLat1,
                                         int fixedLon, int fixedLat, double cosLat) {
        // Adjust x scale to account for lines of longitude converging toward the poles.
        fixedLon0 = (int) (fixedLon0 / cosLat);
        fixedLon1 = (int) (fixedLon1 / cosLat);
        fixedLon  = (int) (fixedLon / cosLat);

        // Reuse thread-local objects instead of allocating - ZERO allocation!
        LineSegment seg = REUSABLE_SEGMENT.get();
        Coordinate coord = REUSABLE_COORDINATE.get();

        // Mutate the reusable objects
        seg.p0.x = fixedLon0;
        seg.p0.y = fixedLat0;
        seg.p1.x = fixedLon1;
        seg.p1.y = fixedLat1;

        coord.x = fixedLon;
        coord.y = fixedLat;

        return seg.segmentFraction(coord);
    }

    /**
     * Given an envelope in fixed-point degrees, enlarge it by at least the given number of meters in all directions.
     * Intentionally slightly oversizes the rectangle by scaling longitudes based on the latitude closest to the equator.
     * Querying for objects inside the envelope will overselect, which is a common characteristic of spatial indexes.
     * You'll need to filter the resulting set of selected objects using more accurate distances.
     */
    public static void expandEnvelopeFixed(Envelope envelope, double radiusMeters) {
        // Convert latitude to floating for use with SphericalDistanceLibrary below
        double floatingLat0 =
                fixedDegreesToFloating(Math.min(Math.abs(envelope.getMaxY()), Math.abs(envelope.getMinY())));
        double yExpansion =
                VertexStore.floatingDegreesToFixed(SphericalDistanceLibrary.metersToDegreesLatitude(radiusMeters));
        double xExpansion =
                VertexStore.floatingDegreesToFixed(SphericalDistanceLibrary.metersToDegreesLongitude(radiusMeters, floatingLat0));
        if (xExpansion < 0 || yExpansion < 0) {
            throw new AssertionError("Buffer distance in geographic units is negative!");
        }
        envelope.expandBy(xExpansion, yExpansion);
    }

    public static Envelope floatingWgsEnvelopeToFixed (Envelope floatingWgsEnvelope) {
        double fixedMinX = floatingDegreesToFixed(floatingWgsEnvelope.getMinX());
        double fixedMaxX = floatingDegreesToFixed(floatingWgsEnvelope.getMaxX());
        double fixedMinY = floatingDegreesToFixed(floatingWgsEnvelope.getMinY());
        double fixedMaxY = floatingDegreesToFixed(floatingWgsEnvelope.getMaxY());
        return new Envelope(fixedMinX, fixedMaxX, fixedMinY, fixedMaxY);
    }

}
