# cartographer

Loads GPX routes and merges them into a single route graph, with pathfinding
between routes as the end goal. A Java 21 project built with Maven.

## Requirements

- Java 21+
- Maven 3.9+

## Build

```bash
make package   # or: mvn package
```

## Run

```bash
make run                                            # builds, loads all routes in routes/, prints the graph
java -jar target/cartographer-1.0-SNAPSHOT.jar foo.gpx ...   # or pass specific GPX files
```

The program prints route info, graph stats, and the merged graph as a vertex
table plus section and adjacency lists, and writes an SVG map to `graph.svg`:

```
komoot-1936580.gpx: 255 nodes, starting at (48,601587, 13,792207)
...
Simplified graph: 8 vertices (8 junctions, 0 endpoints), 12 edges (5 shared), 23,50 km total

Sections (* = shared, from one junction/start to the next):
  A -> A (5,29 km)
  B -> A* (252 m)
  ...
```

## Clean

```bash
make clean     # or: mvn clean
```

## Test

```bash
mvn test
```

## How it works

1. `GpxRouteLoader` parses GPX 1.x files into `Route` objects (ordered track
   `Node`s plus named waypoints) using the JDK's built-in XML parser. Files
   are parsed in parallel (one loader per file, encounter order preserved).
2. `IntersectionFinder` clusters track points within 10 m of each other into
   intersections — across routes, and optionally within one route
   (self-crossings and out-and-back strands). Candidate pairs come from a
   spatial grid (near-linear instead of O(points²)) and the search runs in
   parallel per track point, so adding GPX files scales well.
3. `GraphBuilder` merges everything into a `RouteGraph`: intersections become
   junction vertices, route stretches between junctions become `GraphEdge`s,
   and sparse routes are cut at junctions they merely pass near (point-to-
   segment projection within a 40 m GPS offset).
4. `GraphBuilder.simplify()` then normalizes the graph: fuses dangling
   endpoints into nearby junctions, contracts chains of corridor junctions
   (same street, several routes) into single edges, merges parallel
   edge duplicates, and fuses junctions closer than the GPS offset. Order is
   least-destructive first.
5. An edge remembers how often it is traveled (`traversals`): shared by
   several routes, or retraced out-and-back by one. Shared edges are what the
   renderers highlight.

## Project layout

```
src/main/java/com/cartographer/        # App entry point
src/main/java/com/cartographer/model/  # Node, Route
src/main/java/com/cartographer/io/     # GpxRouteLoader
src/main/java/com/cartographer/map/    # RouteGraph, GraphNode/Edge,
                                       # IntersectionFinder, GraphBuilder
src/main/java/com/cartographer/ui/     # TextGraphRenderer, SvgGraphRenderer
src/test/java/com/cartographer/        # JUnit 5 tests
routes/                                # sample GPX files (git-ignored)
```

## Roadmap

- [x] Load GPX routes into `Node`/`Route` model
- [x] Find intersections between routes and merge them into one shared graph
- [x] Normalize the graph (sparse-track snapping, corridor contraction,
      endpoint/junction fusion)
- [x] Text visualization (labeled vertices, sections, adjacency list)
- [x] SVG map export (`graph.svg`)
- [ ] Pathfinding across the merged route graph (Dijkstra/A*)
- [ ] Interactive map visualization (e.g. HTML/Leaflet export)
