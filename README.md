# cartographer

Loads GPX routes and merges them into a single route graph, with pathfinding between routes as the end goal. A Java 21 Maven multi-module project: the pipeline lives in `cartographer-core`, the command-line frontend in `cartographer-cli`, and a Spring Boot server frontend in `cartographer-server`.

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
java -jar cartographer-cli/target/cartographer-cli-1.0-SNAPSHOT.jar foo.gpx ...   # or pass specific GPX files
```

The program prints route info, graph stats, and the merged graph as a vertex table plus section and adjacency lists, and writes an SVG map to `graph.svg`:

```
komoot-1936580.gpx: 255 nodes, starting at (48,601587, 13,792207)
...
Simplified graph: 8 vertices (8 junctions, 0 endpoints), 12 edges (5 shared), 23,50 km total

Sections (* = shared, from one junction/start to the next):
  A -> A (5,29 km)
  B -> A* (252 m)
  ...
```

## Server

The Spring Boot frontend serves the graph as a web map and over HTTP. Start it **from the repository root**, because `cartographer.routes-dir` resolves against the working directory:

```bash
make run-server    # builds, loads all routes in routes/, serves http://localhost:8080
```

Open <http://localhost:8080>: the page draws every edge over OpenStreetMap, highlights shared stretches, and its upload form adds a GPX file to the routes directory and rebuilds the graph without a restart. The server refuses to start when the routes directory does not exist, so put at least one `.gpx` file in `routes/` (or configure a different directory) first.

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| `GET` | `/api/graph` | — | `application/geo+json` FeatureCollection, one `LineString` per edge with `traversals`, `lengthMeters`, `shared` and `routes` properties |
| `POST` | `/api/routes` | `multipart/form-data`, field `file`, `.gpx` only | JSON ack `{"filename": ..., "edges": ...}`; `400` for an invalid file, `409` when the filename is already taken (nothing is overwritten or kept) |

```bash
curl -s http://localhost:8080/api/graph | head -c 400
curl -F "file=@routes/komoot-1936580.gpx" http://localhost:8080/api/routes
```

The second upload of the same file answers `409 Conflict`; to add it again, rename the file first.

Server settings live in `cartographer-server/src/main/resources/application.properties`, separate from the CLI's file but using the same keys:

| Property | Default | Meaning |
| --- | --- | --- |
| `cartographer.routes-dir` | `routes` | directory scanned for `.gpx` files on startup and after every upload |
| `cartographer.intersection-tolerance-meters` | `10.0` | two tracks cross when they pass within this many meters of each other |

The values are bundled into the server jar, so edit the file and run `make package` (or `mvn package`) to apply a change. They can also be overridden on the command line, e.g. `java -jar cartographer-server/target/cartographer-server-1.0-SNAPSHOT.jar --cartographer.routes-dir=other-routes`.

## CLI configuration

The CLI settings live in `cartographer-cli/src/main/resources/application.properties`, which is bundled into the CLI jar:

| Property | Default | Meaning |
| --- | --- | --- |
| `cartographer.intersection-tolerance-meters` | `10.0` | two tracks cross when they pass within this many meters of each other |
| `cartographer.routes-dir` | `routes` | directory scanned for `.gpx` files when none are passed on the command line |
| `cartographer.svg-output` | `graph.svg` | where the SVG map is written |

Passing `.gpx` files on the command line bypasses `routes-dir`. The properties are read from the classpath inside the jar, so edit the file and run `make package` (or `mvn package`) to apply a change; a file of the same name in the working directory is not picked up. The server reads its own file with the same keys; see [Server](#server).

## Clean

```bash
make clean     # or: mvn clean
```

## Test

```bash
mvn test
```

## How it works

1. `GpxRouteLoader` parses GPX 1.x files into `Route` objects (ordered track `Node`s plus named waypoints) using the JDK's built-in XML parser. Files are parsed in parallel (one loader per file, encounter order preserved).
2. `IntersectionFinder` clusters track points within the configured tolerance (10 m by default) of each other into intersections — across routes, and optionally within one route (self-crossings and out-and-back strands). Candidate pairs come from a spatial grid (near-linear instead of O(points²)) and the search runs in parallel per track point, so adding GPX files scales well. A dense out-and-back walk chains into one street-length cluster; when it splits into two passes that meet at both ends, the corridor's ends become junctions instead of dropping the cluster, so the graph merges the two legs into one street.
3. `GraphBuilder` merges everything into a `RouteGraph`: intersections become junction vertices, route stretches between junctions become `GraphEdge`s, and sparse routes are cut at junctions they merely pass near (point-to-segment projection within a 40 m GPS offset).
4. `GraphBuilder.simplify()` then normalizes the graph: fuses dangling endpoints into nearby junctions, contracts chains of corridor junctions (same street, several routes) into single edges, merges parallel edge duplicates, and fuses junctions closer than the GPS offset. Order is least-destructive first.
5. An edge remembers how often it is traveled (`traversals`): shared by several routes, or retraced out-and-back by one. Shared edges are what the renderers highlight.

## Project layout

```
pom.xml                                        # parent POM: modules, dependency and plugin versions
cartographer-core/                             # pipeline library, no Spring Boot
  src/main/java/com/cartographer/model/        # Node, Route
  src/main/java/com/cartographer/io/           # GpxRouteLoader
  src/main/java/com/cartographer/map/          # RouteGraph, GraphNode/Edge,
                                               # IntersectionFinder, GraphBuilder
  src/main/java/com/cartographer/ui/           # TextGraphRenderer, SvgGraphRenderer
  src/test/java/com/cartographer/              # JUnit 5 tests
cartographer-cli/                              # command-line frontend, shaded executable jar
  src/main/java/com/cartographer/              # App entry point, CartographerRunner
  src/main/java/com/cartographer/config/       # AppConfig, CartographerProperties
  src/main/resources/application.properties    # pipeline settings
  src/test/java/com/cartographer/              # JUnit 5 tests
cartographer-server/                           # Spring Boot frontend: web map + GeoJSON API
  src/main/java/com/cartographer/server/       # ServerApplication
  src/main/java/com/cartographer/server/api/    # GraphController, GeoJsonMapper
  src/main/java/com/cartographer/server/config/ # ServerConfig, ServerProperties
  src/main/java/com/cartographer/server/graph/  # GraphService
  src/main/resources/application.properties     # server settings
  src/main/resources/static/index.html          # Leaflet map and upload form
  src/test/java/com/cartographer/server/        # JUnit 5 tests
routes/                                        # GPX files, also the server's input dir (git-ignored)
```
