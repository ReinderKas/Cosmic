package server.bots;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.PortalFactory;
import server.maps.Rope;

import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public final class BotNavigationProbe {
    private BotNavigationProbe() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        int mapId = Integer.parseInt(args[0]);
        boolean rebuild = false;
        List<Point> points = new ArrayList<>();
        List<Point> jumps = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--rebuild" -> rebuild = true;
                case "--point" -> points.add(parsePoint(nextArg(args, ++i, "--point")));
                case "--jump" -> jumps.add(parsePoint(nextArg(args, ++i, "--jump")));
                default -> throw new IllegalArgumentException("Unknown arg: " + arg);
            }
        }

        MapleMap map = loadMapGeometry(mapId);
        BotNavigationGraph graph = rebuild
                ? BotNavigationGraphProvider.rebuildGraph(map)
                : BotNavigationGraphProvider.getGraph(map);

        printSummary(map, graph);
        for (Point point : points) {
            probePoint(map, graph, point);
        }
        for (Point point : jumps) {
            probeJump(map, graph, point);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: BotNavigationProbe <mapId> [--rebuild] [--point x,y] [--jump x,y]");
        System.out.println("Example: BotNavigationProbe 100000000 --rebuild --point 1080,334 --jump 1080,334 --jump 990,334");
    }

    private static String nextArg(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[index];
    }

    private static Point parsePoint(String raw) {
        String[] parts = raw.split(",", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected point as x,y but got: " + raw);
        }
        return new Point(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
    }

    private static void printSummary(MapleMap map, BotNavigationGraph graph) {
        int jumpEdges = 0;
        int dropEdges = 0;
        int climbEdges = 0;
        int portalEdges = 0;
        int walkEdges = 0;
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                switch (edge.type) {
                    case WALK -> walkEdges++;
                    case JUMP -> jumpEdges++;
                    case DROP -> dropEdges++;
                    case CLIMB -> climbEdges++;
                    case PORTAL -> portalEdges++;
                    default -> {
                    }
                }
            }
        }

        System.out.printf("Map %d  walkStep=%d  regions=%d  edges walk=%d jump=%d drop=%d climb=%d portal=%d%n",
                map.getId(), BotMovementManager.walkStep(map), graph.regions.size(), walkEdges, jumpEdges, dropEdges, climbEdges, portalEdges);
    }

    private static void probePoint(MapleMap map, BotNavigationGraph graph, Point point) {
        Foothold foothold = map.getFootholds().findBelow(point);
        int regionId = graph.findRegionId(map, point);
        BotNavigationGraph.Region region = graph.getRegion(regionId);

        System.out.printf("%nPoint %d,%d%n", point.x, point.y);
        System.out.printf("  below foothold=%s  region=%d%n", foothold == null ? "none" : foothold.getId(), regionId);
        if (region == null) {
            return;
        }

        System.out.printf("  region span=[%d,%d] y=[%d,%d] segments=%d%n",
                region.minX, region.maxX, region.minY, region.maxY, region.segments.size());

        Map<BotNavigationGraph.EdgeType, Integer> counts = new EnumMap<>(BotNavigationGraph.EdgeType.class);
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
            counts.merge(edge.type, 1, Integer::sum);
        }
        System.out.printf("  outgoing %s%n", counts);

        List<BotNavigationGraph.Edge> nearbyJumpEdges = findNearbyEdges(graph, region.id, point, BotNavigationGraph.EdgeType.JUMP, 24, 16);
        if (nearbyJumpEdges.isEmpty()) {
            System.out.println("  nearby jump edges: none");
            return;
        }

        System.out.println("  nearby jump edges:");
        for (BotNavigationGraph.Edge edge : nearbyJumpEdges) {
            System.out.printf("    %d,%d -> %d,%d  stepX=%d  toRegion=%d%n",
                    edge.startPoint.x, edge.startPoint.y, edge.endPoint.x, edge.endPoint.y, edge.launchStepX, edge.toRegionId);
        }
    }

    private static void probeJump(MapleMap map, BotNavigationGraph graph, Point point) {
        int walkStep = BotMovementManager.walkStep(map);
        int regionId = graph.findRegionId(map, point);

        System.out.printf("%nJump probe %d,%d  region=%d%n", point.x, point.y, regionId);
        for (int stepX : new int[]{-walkStep, 0, walkStep}) {
            BotMovementManager.JumpLanding landing = BotMovementManager.simulateJumpLanding(map, point, stepX);
            if (landing == null) {
                System.out.printf("  stepX=%d -> no landing%n", stepX);
                continue;
            }

            int landingRegionId = graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1);
            System.out.printf("  stepX=%d -> %d,%d fh=%d region=%d%n",
                    stepX, landing.point().x, landing.point().y, landing.foothold().getId(), landingRegionId);
        }

        List<BotNavigationGraph.Edge> nearbyJumpEdges = regionId < 0
                ? List.of()
                : findNearbyEdges(graph, regionId, point, BotNavigationGraph.EdgeType.JUMP, 24, 16);
        if (nearbyJumpEdges.isEmpty()) {
            System.out.println("  graph jump edges near start: none");
            return;
        }

        System.out.println("  graph jump edges near start:");
        for (BotNavigationGraph.Edge edge : nearbyJumpEdges) {
            System.out.printf("    %d,%d -> %d,%d  stepX=%d  toRegion=%d%n",
                    edge.startPoint.x, edge.startPoint.y, edge.endPoint.x, edge.endPoint.y, edge.launchStepX, edge.toRegionId);
        }
    }

    private static List<BotNavigationGraph.Edge> findNearbyEdges(BotNavigationGraph graph,
                                                                 int regionId,
                                                                 Point point,
                                                                 BotNavigationGraph.EdgeType type,
                                                                 int maxDx,
                                                                 int maxDy) {
        List<BotNavigationGraph.Edge> nearby = new ArrayList<>();
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(regionId)) {
            if (edge.type != type) {
                continue;
            }
            if (Math.abs(edge.startPoint.x - point.x) > maxDx || Math.abs(edge.startPoint.y - point.y) > maxDy) {
                continue;
            }
            nearby.add(edge);
        }
        nearby.sort((left, right) -> {
            int leftDistance = Math.abs(left.startPoint.x - point.x) + Math.abs(left.startPoint.y - point.y);
            int rightDistance = Math.abs(right.startPoint.x - point.x) + Math.abs(right.startPoint.y - point.y);
            return Integer.compare(leftDistance, rightDistance);
        });
        return nearby;
    }

    private static MapleMap loadMapGeometry(int mapId) {
        DataProvider mapSource = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Data mapData = mapSource.getData(getMapName(mapId));
        if (mapData == null) {
            throw new IllegalArgumentException("Map data not found for " + mapId);
        }

        Data infoData = mapData.getChildByPath("info");
        String link = DataTool.getString(infoData.getChildByPath("link"), "");
        if (!link.isEmpty()) {
            mapData = mapSource.getData(getMapName(Integer.parseInt(link)));
            infoData = mapData.getChildByPath("info");
        }

        float monsterRate = 0.0f;
        Data mobRate = infoData.getChildByPath("mobRate");
        if (mobRate != null) {
            monsterRate = (Float) mobRate.getData();
        }

        MapleMap map = new MapleMap(mapId, 0, 0, DataTool.getInt("returnMap", infoData, mapId), monsterRate);
        loadBounds(map, mapData, infoData);
        loadPortals(map, mapData);
        loadFootholds(map, mapData);
        loadRopes(map, mapData);

        Data footholdSpeedData = infoData.getChildByPath("fs");
        if (footholdSpeedData != null) {
            map.setFootholdSpeed(DataTool.getFloat(footholdSpeedData));
        }
        return map;
    }

    private static void loadBounds(MapleMap map, Data mapData, Data infoData) {
        int top = DataTool.getInt(infoData.getChildByPath("VRTop"));
        int bottom = DataTool.getInt(infoData.getChildByPath("VRBottom"));
        if (top == bottom) {
            Data minimapData = mapData.getChildByPath("miniMap");
            if (minimapData != null) {
                int px = DataTool.getInt(minimapData.getChildByPath("centerX")) * -1;
                int py = DataTool.getInt(minimapData.getChildByPath("centerY")) * -1;
                int height = DataTool.getInt(minimapData.getChildByPath("height"));
                int width = DataTool.getInt(minimapData.getChildByPath("width"));
                map.setMapPointBoundings(px, py, height, width);
                return;
            }

            int dist = 1 << 18;
            map.setMapPointBoundings(-dist / 2, -dist / 2, dist, dist);
            return;
        }

        int left = DataTool.getInt(infoData.getChildByPath("VRLeft"));
        int right = DataTool.getInt(infoData.getChildByPath("VRRight"));
        map.setMapLineBoundings(top, bottom, left, right);
    }

    private static void loadPortals(MapleMap map, Data mapData) {
        Data portalData = mapData.getChildByPath("portal");
        if (portalData == null) {
            return;
        }

        PortalFactory portalFactory = new PortalFactory();
        for (Data portal : portalData) {
            Portal created = portalFactory.makePortal(DataTool.getInt(portal.getChildByPath("pt")), portal);
            map.addPortal(created);
        }
    }

    private static void loadFootholds(MapleMap map, Data mapData) {
        List<Foothold> footholds = new LinkedList<>();
        Point lowerBound = new Point();
        Point upperBound = new Point();

        Data footholdData = mapData.getChildByPath("foothold");
        if (footholdData == null) {
            map.setFootholds(new FootholdTree(new Point(), new Point()));
            return;
        }

        for (Data footRoot : footholdData) {
            for (Data footCategory : footRoot) {
                for (Data footHold : footCategory) {
                    int x1 = DataTool.getInt(footHold.getChildByPath("x1"));
                    int y1 = DataTool.getInt(footHold.getChildByPath("y1"));
                    int x2 = DataTool.getInt(footHold.getChildByPath("x2"));
                    int y2 = DataTool.getInt(footHold.getChildByPath("y2"));
                    Foothold foothold = new Foothold(new Point(x1, y1), new Point(x2, y2), Integer.parseInt(footHold.getName()));
                    foothold.setPrev(DataTool.getInt(footHold.getChildByPath("prev")));
                    foothold.setNext(DataTool.getInt(footHold.getChildByPath("next")));
                    footholds.add(foothold);
                    lowerBound.x = Math.min(lowerBound.x, Math.min(x1, x2));
                    lowerBound.y = Math.min(lowerBound.y, Math.min(y1, y2));
                    upperBound.x = Math.max(upperBound.x, Math.max(x1, x2));
                    upperBound.y = Math.max(upperBound.y, Math.max(y1, y2));
                }
            }
        }

        FootholdTree tree = new FootholdTree(lowerBound, upperBound);
        for (Foothold foothold : footholds) {
            tree.insert(foothold);
        }
        map.setFootholds(tree);
    }

    private static void loadRopes(MapleMap map, Data mapData) {
        Data ropeData = mapData.getChildByPath("ladderRope");
        if (ropeData == null) {
            return;
        }

        for (Data rope : ropeData) {
            int x = DataTool.getInt(rope.getChildByPath("x"));
            int y1 = DataTool.getInt(rope.getChildByPath("y1"));
            int y2 = DataTool.getInt(rope.getChildByPath("y2"));
            boolean ladder = DataTool.getInt(rope.getChildByPath("l"), 0) == 1;
            map.addRope(new Rope(x, y1, y2, ladder));
        }
    }

    private static String getMapName(int mapId) {
        return "Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img";
    }
}
