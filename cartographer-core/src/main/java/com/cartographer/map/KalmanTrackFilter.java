package com.cartographer.map;

import com.cartographer.model.Node;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Drops displaced GPS points from a recorded track before intersection
 * finding and graph building see it.
 * <p>
 * A constant-velocity Kalman model predicts where the next track point
 * should be; a measurement that lands far away from that prediction is a
 * GPS spike, not a place the track really went, and is left out. Accepted
 * points are kept at their measured coordinates — the filter only decides
 * which points the rest of the pipeline sees, it never smooths the drawn
 * geometry.
 * <p>
 * A single displaced point otherwise becomes an intersection's cut point
 * (one street ends in a backwards spoke), and a short run of displaced
 * points pushes a path beyond the merge budget of {@link GraphBuilder},
 * so one street renders as parallel edges.
 * <p>
 * A track without a single timestamp says nothing about its spacing, so it is
 * passed through unfiltered rather than gated on an assumed one.
 */
@Component
public class KalmanTrackFilter {

    // Consumer GPS position noise without hdop data to read it from: the
    // jitter every measurement of this filter carries (~10 m per axis).
    private static final double MEASUREMENT_NOISE_METERS = 10.0;

    // Acceleration noise of the constant-velocity model in m^2/s^3, walking
    // and cycling scale: small enough that a spike cannot drag the velocity
    // estimate away from the street, large enough to follow real curves.
    private static final double PROCESS_NOISE_METERS2_PER_SECOND3 = 0.5;

    // A measured position this far from the prediction is a spike. Beyond
    // GPS jitter plus a step of travel, so real curves and turnarounds stay
    // inside while the displacements this filter is for do not.
    private static final double INNOVATION_GATE_METERS = 20.0;

    // The gate opens with the prediction's own position uncertainty: an
    // uncertain velocity (recording pause, GPS dropout) carries the expected
    // position further, and the first point after the pause must not be
    // dropped for its position alone.
    private static final double GATE_SIGMAS = 3.0;

    // Points without a time, and timestamps that do not move forward, are
    // treated as a 1 s recording — the spacing of the dense GPS tracks whose
    // spikes this filter is for.
    private static final double DEFAULT_DT_SECONDS = 1.0;

    // Before the track's direction is observed the velocity is unknown.
    // Only the first gates are this wide.
    private static final double INITIAL_SPEED_UNCERTAINTY_METERS_PER_SECOND = 5.0;

    // Shorter tracks carry no direction to predict from.
    private static final int MIN_TRACK_POINTS = 3;

    private static final double MEASUREMENT_VARIANCE_METERS2 =
            MEASUREMENT_NOISE_METERS * MEASUREMENT_NOISE_METERS;

    private static final double METERS_PER_DEGREE_LATITUDE = 111_320.0;

    /**
     * The track's points minus the ones the gate rejected, in track order.
     * The input list is not modified; point objects are shared, not copied,
     * and a track too short or entirely untimed is returned as it is.
     */
    public List<Node> filter(List<Node> track) {
        if (track.size() < MIN_TRACK_POINTS || !carriesTimestamps(track)) {
            return new ArrayList<>(track);
        }
        Node origin = track.get(0);
        State state = new State();
        List<Node> kept = new ArrayList<>(track.size());
        kept.add(origin);

        Instant previousTime = origin.getTime();
        for (int i = 1; i < track.size(); i++) {
            Node point = track.get(i);
            double dt = secondsBetween(previousTime, point.getTime());
            previousTime = point.getTime();

            state.predict(dt);
            double east = metersEast(origin, point);
            double north = metersNorth(origin, point);
            if (state.innovationMeters(east, north) > state.gateMeters(dt)) {
                continue; // spike: the prediction stays the state
            }
            state.update(east, north);
            kept.add(point);
        }
        return kept;
    }

    /**
     * Position (x, y) and velocity (vx, vy) in meters in the track's local
     * frame, with the covariance of one axis. Both axes share the variances:
     * the motion model and the measurement noise are isotropic.
     * <p>
     * A filter starts at rest at the frame's origin (the track's first point):
     * the track's direction is not known before its second point.
     */
    private static final class State {

        private double x;
        private double y;
        private double vx;
        private double vy;

        // Covariance of [position, velocity] along one axis.
        private double p00;
        private double p01;
        private double p11;

        State() {
            p00 = MEASUREMENT_VARIANCE_METERS2;
            p11 = INITIAL_SPEED_UNCERTAINTY_METERS_PER_SECOND
                    * INITIAL_SPEED_UNCERTAINTY_METERS_PER_SECOND;
        }

        void predict(double dt) {
            x += vx * dt;
            y += vy * dt;

            double q = PROCESS_NOISE_METERS2_PER_SECOND3;
            p00 = p00 + 2 * dt * p01 + dt * dt * p11 + q * dt * dt * dt / 3;
            p01 = p01 + dt * p11 + q * dt * dt / 2;
            p11 = p11 + q * dt;
        }

        /** How far the measurement is from where the track was expected. */
        double innovationMeters(double measuredEast, double measuredNorth) {
            return Math.hypot(measuredEast - x, measuredNorth - y);
        }

        /**
         * The distance a measurement may stray from the prediction over
         * {@code dt}: the spike allowance plus how far the uncertain velocity
         * can carry the expected position in that time.
         */
        double gateMeters(double dt) {
            return INNOVATION_GATE_METERS + GATE_SIGMAS * dt * Math.sqrt(p11);
        }

        void update(double measuredEast, double measuredNorth) {
            double innovationVariance = p00 + MEASUREMENT_VARIANCE_METERS2;
            double positionGain = p00 / innovationVariance;
            double velocityGain = p01 / innovationVariance;

            double innovationEast = measuredEast - x;
            double innovationNorth = measuredNorth - y;
            x += positionGain * innovationEast;
            y += positionGain * innovationNorth;
            vx += velocityGain * innovationEast;
            vy += velocityGain * innovationNorth;

            double p00Before = p00;
            double p01Before = p01;
            p00 -= positionGain * p00Before;
            p01 -= positionGain * p01Before;
            p11 -= velocityGain * p01Before;
        }
    }

    /** Meters east of the track's first point (equirectangular, as GraphBuilder). */
    private double metersEast(Node origin, Node point) {
        return (point.getLongitude() - origin.getLongitude())
                * Math.cos(Math.toRadians(origin.getLatitude())) * METERS_PER_DEGREE_LATITUDE;
    }

    /** Meters north of the track's first point. */
    private double metersNorth(Node origin, Node point) {
        return (point.getLatitude() - origin.getLatitude()) * METERS_PER_DEGREE_LATITUDE;
    }

    /**
     * Whether the track's spacing is known at all. Without a single timestamp
     * the points may be a meter or a kilometer apart, so every measurement
     * after the first would look like a spike to a prediction assuming the
     * default spacing: such a track keeps all its points, as it did before
     * this filter existed. One timestamp is enough — a track whose spacing is
     * known keeps the default for the occasional missing or repeated stamp.
     */
    private static boolean carriesTimestamps(List<Node> track) {
        return track.stream().anyMatch(node -> node.getTime() != null);
    }

    /**
     * Seconds since the previous track point, or the default spacing when
     * the points carry no usable time: a missing timestamp, or one that does
     * not move forward (a duplicated stamp from a paused recorder).
     */
    private double secondsBetween(Instant previous, Instant current) {
        if (previous == null || current == null || !current.isAfter(previous)) {
            return DEFAULT_DT_SECONDS;
        }
        return Duration.between(previous, current).toMillis() / 1000.0;
    }
}
