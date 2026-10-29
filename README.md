# cartographer

Loads GPX routes and builds a map out of them, with pathfinding between routes
as the end goal. A Java 21 project built with Maven.

## Requirements

- Java 21+
- Maven 3.9+

## Build

```bash
make package   # or: mvn package
```

## Run

```bash
make run                                            # builds and prints info about all routes in routes/
java -jar target/cartographer-1.0-SNAPSHOT.jar foo.gpx ...   # or pass specific GPX files
```

Example output:

```
komoot-1936580.gpx: 255 nodes, starting at (48,601587, 13,792207)
komoot-4321335.gpx: 356 nodes, starting at (48,601275, 13,787572)
komoot-8340422.gpx: 112 nodes, starting at (48,601587, 13,792207)
```

## Clean

```bash
make clean     # or: mvn clean
```

## Test

```bash
mvn test
```

## Domain model

- `Node` — a point on the map with latitude/longitude, optional elevation and
  name. Equality is coordinate-based, so shared points between routes can be
  merged. Includes a Haversine `distanceTo()` for use as an edge weight or A*
  heuristic.
- `Route` — a named route holding its ordered track nodes plus named waypoints.
- `GpxRouteLoader` — parses GPX 1.x files into `Route` objects using the JDK's
  built-in XML parser (no extra dependencies).

## Project layout

```
src/main/java/com/cartographer/        # application code
src/main/java/com/cartographer/model/  # Node and Route
src/main/java/com/cartographer/io/     # GpxRouteLoader
src/test/java/com/cartographer/        # JUnit 5 tests
routes/                                # sample GPX files
```

## Roadmap

- [x] Load GPX routes into `Node`/`Route` model
- [ ] Build a graph from the routes (snapping/merging shared nodes)
- [ ] Pathfinding across the merged route graph (Dijkstra/A*)
- [ ] Map visualization
