package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Rope;

import java.awt.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class BotNavigationGraphProvider {
    private static final Logger log = LoggerFactory.getLogger(BotNavigationGraphProvider.class);

    private static final int GRAPH_VERSION = 1;
    private static final Path CACHE_DIR = Path.of("cache", "bot-nav", "v" + GRAPH_VERSION);
    private static final Map<Integer, BotNavigationGraph> GRAPHS = new ConcurrentHashMap<>();

    static BotNavigationGraph getGraph(MapleMap map) {
        return GRAPHS.computeIfAbsent(map.getId(), ignored -> loadOrBuildGraph(map));
    }

    private static BotNavigationGraph loadOrBuildGraph(MapleMap map) {
        BotNavigationGraph cached = loadGraph(map.getId());
        if (cached != null) {
            return cached;
        }

        BotNavigationGraph built = buildGraph(map);
        saveGraph(built);
        return built;
    }

    private static BotNavigationGraph loadGraph(int mapId) {
        Path file = graphFile(mapId);
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            Object loaded = in.readObject();
            if (!(loaded instanceof BotNavigationGraph graph)) {
                return null;
            }
            if (graph.version != GRAPH_VERSION || graph.mapId != mapId) {
                return null;
            }
            return graph;
        } catch (IOException | ClassNotFoundException e) {
            log.debug("Failed to load bot nav graph cache for map {}", mapId, e);
            return null;
        }
    }

    private static void saveGraph(BotNavigationGraph graph) {
        try {
            Files.createDirectories(CACHE_DIR);
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(graphFile(graph.mapId)))) {
                out.writeObject(graph);
            }
        } catch (IOException e) {
            log.debug("Failed to save bot nav graph cache for map {}", graph.mapId, e);
        }
    }

    private static Path graphFile(int mapId) {
        return CACHE_DIR.resolve(mapId + ".bin");
    }

    private static BotNavigationGraph buildGraph(MapleMap map) {
        List<Foothold> footholds = map.getFootholds() == null ? List.of() : map.getFootholds().getAllFootholds();
        Map<Integer, Foothold> footholdsById = new HashMap<>();
        Map<Integer, BotNavigationGraph.Region> regionsByFootholdId = new HashMap<>();
        Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
        List<BotNavigationGraph.Region> regions = new ArrayList<>();
        Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
        Set<String> edgeKeys = new HashSet<>();

        int nextRegionId = 1;
        for (Foothold foothold : footholds) {
            footholdsById.put(foothold.getId(), foothold);
            if (foothold.isWall()) {
                continue;
            }

            BotNavigationGraph.Region region = new BotNavigationGraph.Region(nextRegionId++, foothold);
            regions.add(region);
            regionsById.put(region.id, region);
            regionsByFootholdId.put(region.footholdId, region);
        }

        for (BotNavigationGraph.Region region : regions) {
            Foothold foothold = footholdsById.get(region.footholdId);
            addWalkEdges(region, foothold, footholdsById, regionsByFootholdId, outgoing, edgeKeys);
            addDropEdges(region, map, regionsByFootholdId, outgoing, edgeKeys);
            addJumpEdges(region, map, regionsByFootholdId, outgoing, edgeKeys);
        }

        for (Rope rope : map.getRopes()) {
            addClimbEdges(rope, map, regionsByFootholdId, outgoing, edgeKeys);
        }

        for (Portal portal : map.getPortals()) {
            addPortalEdges(portal, map, regionsByFootholdId, outgoing, edgeKeys);
        }

        Map<Integer, Integer> regionIdByFootholdId = new HashMap<>();
        for (BotNavigationGraph.Region region : regions) {
            regionIdByFootholdId.put(region.footholdId, region.id);
        }

        return new BotNavigationGraph(map.getId(), GRAPH_VERSION, regions, regionsById, regionIdByFootholdId, outgoing);
    }

    private static void addWalkEdges(BotNavigationGraph.Region from,
                                     Foothold foothold,
                                     Map<Integer, Foothold> footholdsById,
                                     Map<Integer, BotNavigationGraph.Region> regionsByFootholdId,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys) {
        addWalkEdge(from, footholdsById.get(foothold.getPrev()), regionsByFootholdId, outgoing, edgeKeys);
        addWalkEdge(from, footholdsById.get(foothold.getNext()), regionsByFootholdId, outgoing, edgeKeys);
    }

    private static void addWalkEdge(BotNavigationGraph.Region from,
                                    Foothold targetFoothold,
                                    Map<Integer, BotNavigationGraph.Region> regionsByFootholdId,
                                    Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                    Set<String> edgeKeys) {
        if (targetFoothold == null || targetFoothold.isWall()) {
            return;
        }

        BotNavigationGraph.Region to = regionsByFootholdId.get(targetFoothold.getId());
        if (to == null || to.id == from.id) {
            return;
        }

        Point start = edgePointToward(from, to);
        Point end = edgePointToward(to, from);
        addEdge(from.id, to.id, BotNavigationGraph.EdgeType.WALK, start, end, 0, 0, estimateWalkCost(start, end), outgoing, edgeKeys);
        addEdge(to.id, from.id, BotNavigationGraph.EdgeType.WALK, end, start, 0, 0, estimateWalkCost(start, end), outgoing, edgeKeys);
    }

    private static void addDropEdges(BotNavigationGraph.Region from,
                                     MapleMap map,
                                     Map<Integer, BotNavigationGraph.Region> regionsByFootholdId,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys) {
        for (Point anchor : anchorPoints(from)) {
            BotNavigationGraph.Region below = findRegionBelow(map, regionsByFootholdId, new Point(anchor.x, anchor.y + 1));
            if (below == null || below.id == from.id) {
                continue;
            }

            Point landing = below.pointAt(anchor.x);
            addEdge(from.id, below.id, BotNavigationGraph.EdgeType.DROP, anchor, landing, 0, 0, estimateDropCost(anchor, landing), outgoing, edgeKeys);
        }
    }

    private static void addJumpEdges(BotNavigationGraph.Region from,
                                     MapleMap map,
                                     Map<Integer, BotNavigationGraph.Region> regionsByFootholdId,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys) {
        int jumpStep = BotMovementManager.walkStep(map);
        for (Point anchor : anchorPoints(from)) {
            for (int launchStepX : new int[]{-jumpStep, 0, jumpStep}) {
                BotMovementManager.JumpLanding landing = BotMovementManager.simulateJumpLanding(map, anchor, launchStepX);
                if (landing == null) {
                    continue;
                }

                BotNavigationGraph.Region to = regionsByFootholdId.get(landing.foothold().getId());
                if (to == null || to.id == from.id) {
                    continue;
                }
                if (Math.abs(landing.point().x - anchor.x) < 6 && Math.abs(landing.point().y - anchor.y) < 6) {
                    continue;
                }

                addEdge(from.id, to.id, BotNavigationGraph.EdgeType.JUMP, anchor, landing.point(), launchStepX, 0,
                        estimateJumpCost(anchor, landing.point()), outgoing, edgeKeys);
            }
        }
    }

    private static void addClimbEdges(Rope rope,
                                      MapleMap map,
                                      Map<Integer, BotNavigationGraph.Region> regionsByFootholdId,
                                      Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                      Set<String> edgeKeys) {
        BotNavigationGraph.Region bottom = findRegionBelow(map, regionsByFootholdId, new Point(rope.x(), rope.bottomY() - 1));
        BotNavigationGraph.Region top = findRegionBelow(map, regionsByFootholdId, new Point(rope.x(), rope.topY() - 1));
        if (bottom == null || top == null || bottom.id == top.id) {
            return;
        }

        Point start = bottom.pointAt(rope.x());
        Point end = top.pointAt(rope.x());
        int cost = estimateClimbCost(start, end);
        addEdge(bottom.id, top.id, BotNavigationGraph.EdgeType.CLIMB, start, end, 0, 0, cost, outgoing, edgeKeys);
        addEdge(top.id, bottom.id, BotNavigationGraph.EdgeType.CLIMB, end, start, 0, 0, cost, outgoing, edgeKeys);
    }

    private static void addPortalEdges(Portal portal,
                                       MapleMap map,
                                       Map<Integer, BotNavigationGraph.Region> regionsByFootholdId,
                                       Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                       Set<String> edgeKeys) {
        if (portal.getTargetMapId() != map.getId()) {
            return;
        }

        Portal targetPortal = map.getPortal(portal.getTarget());
        if (targetPortal == null || targetPortal.getId() == portal.getId()) {
            return;
        }

        BotNavigationGraph.Region from = findRegionBelow(map, regionsByFootholdId, portal.getPosition());
        BotNavigationGraph.Region to = findRegionBelow(map, regionsByFootholdId, targetPortal.getPosition());
        if (from == null || to == null || from.id == to.id) {
            return;
        }

        Point start = from.pointAt(portal.getPosition().x);
        Point end = to.pointAt(targetPortal.getPosition().x);
        addEdge(from.id, to.id, BotNavigationGraph.EdgeType.PORTAL, start, end, 0, portal.getId(), 100, outgoing, edgeKeys);
    }

    private static BotNavigationGraph.Region findRegionBelow(MapleMap map,
                                                             Map<Integer, BotNavigationGraph.Region> regionsByFootholdId,
                                                             Point point) {
        if (map.getFootholds() == null) {
            return null;
        }

        Foothold foothold = map.getFootholds().findBelow(point);
        if (foothold == null) {
            return null;
        }

        return regionsByFootholdId.get(foothold.getId());
    }

    private static List<Point> anchorPoints(BotNavigationGraph.Region region) {
        List<Point> points = new ArrayList<>(3);
        addAnchor(points, region.leftPoint());
        addAnchor(points, region.centerPoint());
        addAnchor(points, region.rightPoint());
        return points;
    }

    private static void addAnchor(List<Point> points, Point point) {
        for (Point existing : points) {
            if (existing.equals(point)) {
                return;
            }
        }
        points.add(point);
    }

    private static Point edgePointToward(BotNavigationGraph.Region from, BotNavigationGraph.Region to) {
        return to.centerPoint().x >= from.centerPoint().x ? from.rightPoint() : from.leftPoint();
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchStepX,
                                int portalId,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        String key = fromRegionId + ":" + toRegionId + ":" + type + ":" + startPoint.x + ":" + startPoint.y + ":"
                + endPoint.x + ":" + endPoint.y + ":" + launchStepX + ":" + portalId;
        if (!edgeKeys.add(key)) {
            return;
        }

        outgoing.computeIfAbsent(fromRegionId, ignored -> new ArrayList<>())
                .add(new BotNavigationGraph.Edge(fromRegionId, toRegionId, type, startPoint, endPoint, launchStepX, portalId, cost));
    }

    private static int estimateWalkCost(Point start, Point end) {
        return Math.max(50, (int) Math.round((Math.abs(end.x - start.x) * 1000.0) / Math.max(1, BotMovementManager.cfg.WALK_VEL)));
    }

    private static int estimateJumpCost(Point start, Point end) {
        int travel = Math.abs(end.x - start.x) + Math.abs(end.y - start.y);
        return 300 + Math.max(100, (int) Math.round((travel * 1000.0) / Math.max(1, BotMovementManager.cfg.WALK_VEL)));
    }

    private static int estimateDropCost(Point start, Point end) {
        return 200 + Math.max(100, (int) Math.round((Math.abs(end.y - start.y) * 1000.0) / Math.max(1, BotMovementManager.cfg.MAX_FALL_PXS)));
    }

    private static int estimateClimbCost(Point start, Point end) {
        return 150 + Math.max(100, (int) Math.round((Math.abs(end.y - start.y) * 1000.0) / Math.max(1, BotMovementManager.cfg.CLIMB_SPEED_PXS)));
    }
}
