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

Open <http://localhost:8080>: the page renders the graph from the vector tiles with MapLibre GL JS over OpenStreetMap, draws each edge in the colour and width of its `traversals` so shared stretches stand out, and shows an edge's `traversals`, length and route count on click. Its upload form adds a GPX file to the routes directory and reloads the tile view without a restart.

The earlier Leaflet/GeoJSON page is kept as <http://localhost:8080/baseline.html>: it draws the same graph from `/api/graph`, so opening it next to the tile page diffs the two renderers directly. The server refuses to start when the routes directory does not exist, so put at least one `.gpx` file in `routes/` (or configure a different directory) first.

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| `GET` | `/api/graph` | — | `application/geo+json` FeatureCollection, one `LineString` per edge with `traversals`, `lengthMeters`, `shared` and `routes` properties |
| `POST` | `/api/routes` | `multipart/form-data`, field `file`, `.gpx` only | JSON ack `{"filename": ..., "edges": ...}`; `400` for an invalid file, `409` when the filename is already taken (nothing is overwritten or kept) |
| `GET` | `/tiles/{z}/{x}/{y}.mvt` | optional `Accept-Encoding`, `If-None-Match` | `application/vnd.mapbox-vector-tile` tile with one `edges` layer, gzipped for clients that accept it and cacheable (`ETag`, `Cache-Control`, `Vary: Accept-Encoding`); `304` without a body when `If-None-Match` repeats the ETag; `200` with an empty tile (zero layers) where no edge is visible, `400` outside the slippy-map grid (`z` 0..22, `x`/`y` in `0..2^z-1`) |
| `GET` | `/tiles/tilejson.json` | — | TileJSON 2.2 metadata: tile URL template, zoom range, graph bounds, and the `edges` layer with its `traversals`, `lengthMeters`, `shared` and `routeCount` fields |

```bash
curl -s http://localhost:8080/api/graph | head -c 400
curl -F "file=@routes/komoot-1936580.gpx" http://localhost:8080/api/routes
curl -s http://localhost:8080/tiles/tilejson.json
curl -s -o tile.mvt http://localhost:8080/tiles/14/8819/5654.mvt
curl -sI -H "Accept-Encoding: gzip" http://localhost:8080/tiles/14/8819/5654.mvt
etag=$(curl -sI http://localhost:8080/tiles/14/8819/5654.mvt | sed -n 's/^ETag: //p' | tr -d '\r')
curl -s -o /dev/null -w "%{http_code}\n" -H "If-None-Match: $etag" http://localhost:8080/tiles/14/8819/5654.mvt   # 304
```

Tiles cover the same graph as `/api/graph`, clipped against the tile box expanded by `cartographer.tile-buffer-units`. MVT has no array type, so a tile's properties carry `routeCount` where the GeoJSON response lists the `routes` names; `traversals`, `lengthMeters` and `shared` are the same in both. The map page builds its vector source from `/tiles/tilejson.json` and rebuilds it after every upload.

Tiles are pre-rendered, never encoded on request. A rebuild — startup or upload — encodes every tile the graph reaches over the zoom range `cartographer.tile-cache-min-zoom`..`cartographer.tile-cache-max-zoom` into an immutable snapshot and swaps it in whole, and the graph is published only after that swap: a request sees the old graph with the old tiles or the new graph with the new tiles, never a mix, and it only looks an address up in the snapshot. An address inside the grid but outside the snapshot gets the same empty tile as before. There is no eviction and no per-address invalidation: the next rebuild replaces the snapshot wholesale.

Each payload holds the MVT bytes, their gzip and a strong `ETag` (the SHA-256 of the bytes), all computed once when the tile is encoded. Responses carry `Content-Encoding: gzip` with `Vary: Accept-Encoding` for clients that accept gzip and the decompressed bytes for clients that do not, `Cache-Control: max-age` from `cartographer.tile-cache-max-age-seconds` (tiles only change on a rebuild, and the map page busts its own cache with a generation query parameter after an upload), and `304 Not Modified` with no body when `If-None-Match` repeats the ETag.

The snapshot is also written to an MBTiles file, `cartographer.mbtiles-file`. On startup, when the routes directory and the tile settings are those the file was rendered from — same GPX file names, sizes and modification times, same tolerance, buffer and zoom range — the tiles are loaded back from it and the pre-render is skipped; the graph itself is still built, since `/api/graph` needs it, and that build is cheap next to rendering. A file that is missing, stale or unreadable just means the tiles are rendered again, and deleting it forces a full pre-render on the next start. Served bytes and ETags are identical before and after such a restart.

The second upload of the same file answers `409 Conflict`; to add it again, rename the file first.

Server settings live in `cartographer-server/src/main/resources/application.properties`, separate from the CLI's file but using the same keys:

| Property | Default | Meaning |
| --- | --- | --- |
| `cartographer.routes-dir` | `routes` | directory scanned for `.gpx` files on startup and after every upload |
| `cartographer.intersection-tolerance-meters` | `10.0` | two tracks cross when they pass within this many meters of each other |
| `cartographer.tile-buffer-units` | `64` | how far outside a tile, in tile units of 4096, edges are still drawn: the clipping buffer that hides the seams between neighbouring tiles |
| `cartographer.tile-cache-min-zoom` | `0` | lowest zoom level pre-rendered on a rebuild |
| `cartographer.tile-cache-max-zoom` | `18` | highest zoom level pre-rendered on a rebuild; each level above adds four times the tiles |
| `cartographer.tile-cache-max-age-seconds` | `3600` | `max-age` a client may reuse a tile before asking again |
| `cartographer.mbtiles-file` | `tiles.mbtiles` | MBTiles (SQLite) file keeping the pre-rendered tiles between restarts; rewritten on every rebuild, git-ignored |

The values are bundled into the server jar, so edit the file and run `make package` (or `mvn package`) to apply a change. They can also be overridden on the command line, e.g. `java -jar cartographer-server/target/cartographer-server-1.0-SNAPSHOT.jar --cartographer.routes-dir=other-routes`.

What the cache costs and buys, measured on this repository's `routes/` directory (8 GPX files, 18 vertices, 19 edges, 19,220 tiles over zooms 0..18) using the per-zoom log lines a rebuild and a restore print:

| Run | Graph build | Tiles |
| --- | --- | --- |
| cold start, nothing stored yet | 295 ms | 8,842 ms pre-render plus 235 ms to write the store |
| restart, routes and settings unchanged | 262 ms | 484 ms to restore from the store, no pre-render |

A cold start takes 9.94 s and spends 8.8 s of it rendering tiles; a restart of unchanged routes comes up in about 1.3 s, with the tiles arriving from the store instead of from the builder.

Nearly all of the work sits at the top of the zoom range, where the tile count quadruples per level:

| Zoom | Tiles | Pre-render |
| --- | --- | --- |
| 0 | 1 | 11 ms |
| 1 | 1 | 5 ms |
| 2 | 1 | 5 ms |
| 3 | 1 | 5 ms |
| 4 | 1 | 2 ms |
| 5 | 1 | 2 ms |
| 6 | 1 | 2 ms |
| 7 | 1 | 2 ms |
| 8 | 1 | 2 ms |
| 9 | 1 | 2 ms |
| 10 | 2 | 6 ms |
| 11 | 4 | 6 ms |
| 12 | 9 | 7 ms |
| 13 | 25 | 14 ms |
| 14 | 72 | 36 ms |
| 15 | 255 | 120 ms |
| 16 | 957 | 440 ms |
| 17 | 3,648 | 1,654 ms |
| 18 | 14,238 | 6,379 ms |

Zoom 18 alone is 14,238 of the 19,220 tiles and 6,379 ms of the 8,842 ms; zooms 0..10 add up to twelve tiles and a few dozen milliseconds. That is why the range stops at 18 by default: z=19..22 would multiply the count four times per level for detail this data does not have.

Before the cache existed, every tile was encoded on request: mean 1.27 ms, median 1.04 ms and p95 1.98 ms per tile. That cost is now paid once per rebuild instead of once per request; a request only looks its address up and copies bytes already in memory.

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
cartographer-server/                           # Spring Boot frontend: web map, GeoJSON API, vector tiles
  src/main/java/com/cartographer/server/       # ServerApplication
  src/main/java/com/cartographer/server/api/    # GraphController, GeoJsonMapper, TileController
  src/main/java/com/cartographer/server/config/ # ServerConfig, ServerProperties
  src/main/java/com/cartographer/server/graph/  # GraphService
  src/main/java/com/cartographer/server/tiles/  # SlippyMap, MvtEncoder, TileBuilder,
                                               # TilePayload, TilePyramid, TileCache,
                                               # MBTilesStore, RoutesFingerprint
  src/main/resources/application.properties     # server settings
  src/main/resources/static/index.html          # MapLibre GL tile map and upload form
  src/main/resources/static/baseline.html       # Leaflet/GeoJSON map kept for the visual diff
  src/test/java/com/cartographer/server/        # JUnit 5 tests
routes/                                        # GPX files, also the server's input dir (git-ignored)
tiles.mbtiles                                  # pre-rendered tile store, rewritten on every rebuild (git-ignored)
```
