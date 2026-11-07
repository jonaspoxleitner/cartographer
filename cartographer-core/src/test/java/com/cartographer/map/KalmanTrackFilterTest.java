package com.cartographer.map;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.model.Node;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KalmanTrackFilterTest {

    // GPX samples live at the repository root, while surefire runs with the
    // module as working directory. Fall back to "." so IDE runs from the root work.
    private static final Path ROUTES_DIR = Path.of(
            System.getProperty("maven.multiModuleProjectDirectory", "."), "routes");

    private static final double METERS_PER_DEGREE_LATITUDE = 111_320.0;
    private static final double ORIGIN_LATITUDE = 48.6;
    private static final double ORIGIN_LONGITUDE = 13.7;
    private static final double METERS_PER_DEGREE_LONGITUDE = METERS_PER_DEGREE_LATITUDE
            * Math.cos(Math.toRadians(ORIGIN_LATITUDE));

    /** A walking pace: 1.4 m per recorded second. */
    private static final double STEP_METERS = 1.4;

    private final KalmanTrackFilter filter = new KalmanTrackFilter();

    /**
     * Builds a track in meters around a fixed origin, one point per second
     * unless paused: the sample spacing of the walking recordings the filter
     * is for.
     */
    private static final class Track {

        private final List<Node> nodes = new ArrayList<>();
        private int second;

        Track at(double eastMeters, double northMeters) {
            nodes.add(new Node(
                    ORIGIN_LATITUDE + northMeters / METERS_PER_DEGREE_LATITUDE,
                    ORIGIN_LONGITUDE + eastMeters / METERS_PER_DEGREE_LONGITUDE,
                    null, null, Instant.EPOCH.plusSeconds(second++)));
            return this;
        }

        /** Moves the clock forward without recording a point. */
        Track pause(int seconds) {
            second += seconds;
            return this;
        }

        Track north(int points) {
            for (int i = 0; i < points; i++) {
                at(0, i * STEP_METERS);
            }
            return this;
        }

        List<Node> nodes() {
            return nodes;
        }
    }

    @Test
    void removesSinglePointSpike() {
        Track track = new Track().north(20);
        Node onTrack = track.nodes().get(10);
        track.nodes().set(10, new Node(onTrack.getLatitude(), onTrack.getLongitude() + 0.0012,
                null, null, onTrack.getTime())); // ~89 m east

        List<Node> filtered = filter.filter(track.nodes());

        assertKeptExactly(track.nodes(), filtered, Set.of(10));
    }

    @Test
    void removesShortMultiPointExcursion() {
        // three samples of a dropout that jumped the track ~60 m east
        Track track = new Track().north(30);
        for (int i = 12; i <= 14; i++) {
            Node offTrack = track.nodes().get(i);
            track.nodes().set(i, new Node(offTrack.getLatitude(),
                    offTrack.getLongitude() + 60 / METERS_PER_DEGREE_LONGITUDE,
                    null, null, offTrack.getTime()));
        }

        List<Node> filtered = filter.filter(track.nodes());

        assertKeptExactly(track.nodes(), filtered, Set.of(12, 13, 14));
    }

    @Test
    void keepsRealCurvesAndTurnarounds() {
        Track track = new Track().north(30);
        double northEnd = 29 * STEP_METERS;
        // U-turn: semicircle of radius 8 m at ~1.4 m per second
        for (int i = 1; i <= 18; i++) {
            double angle = Math.PI * i / 18;
            track.at(8 * Math.sin(angle), northEnd + 8 - 8 * Math.cos(angle));
        }
        // back south on the parallel street, then a right-angle corner east
        for (int i = 1; i <= 30; i++) {
            track.at(16, northEnd + 16 - i * STEP_METERS);
        }
        for (int i = 1; i <= 20; i++) {
            track.at(16 + i * STEP_METERS, northEnd + 16 - 30 * STEP_METERS);
        }

        List<Node> filtered = filter.filter(track.nodes());

        assertKeptExactly(track.nodes(), filtered, Set.of());
    }

    @Test
    void keepsFirstPointAndPassesShortTracksThrough() {
        Track twoPoints = new Track().north(2);
        assertEquals(twoPoints.nodes(), filter.filter(twoPoints.nodes()));

        Track onePoint = new Track().north(1);
        assertEquals(onePoint.nodes(), filter.filter(onePoint.nodes()));

        // even a first point far off the rest of the track is kept: it is the
        // frame the rest of the track is measured in
        Track track = new Track().at(500, 0).at(0, 200).at(0, 400).at(0, 600);
        assertEquals(track.nodes().get(0), filter.filter(track.nodes()).get(0));
    }

    @Test
    void keepsSparseStraightTrack() {
        // Komoot-style planning data: a point every 25 m, 10 s apart, with a
        // few meters of lateral scatter
        Track track = new Track();
        for (int i = 0; i < 25; i++) {
            track.at((i % 3) * 2.5, i * 25);
            track.pause(9);
        }

        List<Node> filtered = filter.filter(track.nodes());

        assertKeptExactly(track.nodes(), filtered, Set.of());
    }

    @Test
    void keepsReentryAfterRecordingPause() {
        // a 15 minute pause in the recording; the walk resumes elsewhere
        Track track = new Track().north(40).pause(900);
        for (int i = 0; i < 10; i++) {
            track.at(400 + i * STEP_METERS, 1000 + i * STEP_METERS);
        }

        List<Node> filtered = filter.filter(track.nodes());

        assertKeptExactly(track.nodes(), filtered, Set.of());
    }

    @Test
    void leavesTrackWithoutTimestampsUnfiltered() {
        // a track with no timestamps at all says nothing about how far apart its
        // points are, so a gate assuming one-second spacing would reject real
        // travel: every point is kept, as before the filter existed
        Track untimed = new Track().north(20);
        List<Node> withoutTime = withoutTime(untimed.nodes());
        setSpike(withoutTime, 10);

        assertKeptExactly(withoutTime, filter.filter(withoutTime), Set.of());
    }

    @Test
    void filtersTrackWithSomeMissingTimestamps() {
        // a track whose spacing is known keeps the default spacing for the
        // occasional missing stamp instead of switching the filter off
        Track track = new Track().north(20);
        Node missingStamp = track.nodes().get(5);
        track.nodes().set(5, new Node(missingStamp.getLatitude(), missingStamp.getLongitude()));
        setSpike(track.nodes(), 10);

        assertEquals(19, filter.filter(track.nodes()).size());
    }

    @Test
    void filtersTrackWhoseStampsDoNotMoveForward() {
        // duplicated stamps (a paused recorder) fall back to the default spacing
        Track sameStamp = new Track().north(20);
        List<Node> duplicated = withTime(sameStamp.nodes(), Instant.EPOCH);
        setSpike(duplicated, 10);

        assertEquals(19, filter.filter(duplicated).size());
    }

    @Test
    void keepsRecordedRoutesIntact() throws Exception {
        // dense walking recording and sparse planned route: neither holds a
        // displaced point, so the gate must not touch them
        List<Node> walking = new GpxRouteLoader()
                .load(ROUTES_DIR.resolve("Walking_2024-12-24_13-01-59_km.gpx")).getNodes();
        List<Node> komoot = new GpxRouteLoader()
                .load(ROUTES_DIR.resolve("komoot-4321335.gpx")).getNodes();

        assertKeptExactly(walking, filter.filter(walking), Set.of());
        assertKeptExactly(komoot, filter.filter(komoot), Set.of());
    }

    @Test
    void filteringIsIdempotent() {
        Track track = new Track().north(30);
        setSpike(track.nodes(), 10);
        setSpike(track.nodes(), 20);

        List<Node> once = filter.filter(track.nodes());
        List<Node> twice = filter.filter(once);

        assertEquals(once, twice);
        assertEquals(28, twice.size());
    }

    @Test
    void filteringIsDeterministicAndLeavesInputIntact() {
        Track track = new Track().north(30);
        setSpike(track.nodes(), 10);
        List<Node> input = new ArrayList<>(track.nodes());

        List<Node> first = filter.filter(input);
        List<Node> second = filter.filter(input);

        assertEquals(first, second);
        assertEquals(input, track.nodes(), "input list is not modified");
    }

    /** Replaces the point at {@code index} with one ~89 m east of it. */
    private void setSpike(List<Node> track, int index) {
        Node onTrack = track.get(index);
        track.set(index, new Node(onTrack.getLatitude(), onTrack.getLongitude() + 0.0012,
                null, null, onTrack.getTime()));
    }

    private List<Node> withoutTime(List<Node> track) {
        return withTime(track, null);
    }

    private List<Node> withTime(List<Node> track, Instant time) {
        List<Node> result = new ArrayList<>(track.size());
        for (Node node : track) {
            result.add(new Node(node.getLatitude(), node.getLongitude(),
                    node.getElevation(), node.getName(), time));
        }
        return result;
    }

    /**
     * Asserts the filtered track is the input track minus {@code removed}
     * indices, sharing the original point objects: keeping a point means
     * keeping its measured coordinate, never a smoothed one.
     */
    private void assertKeptExactly(List<Node> track, List<Node> filtered, Set<Integer> removed) {
        List<Node> expected = new ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            if (!removed.contains(i)) {
                expected.add(track.get(i));
            }
        }
        assertEquals(expected.size(), filtered.size(), "kept point count");
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), filtered.get(i), "kept point " + i);
        }
    }
}
