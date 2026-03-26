package server.bots;

import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.*;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BotNavigationGraph implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    enum EdgeType {
        WALK,
        JUMP,
        DROP,
        CLIMB,
        PORTAL,
        FLASH_JUMP,
        TELEPORT
    }

    static final class Region implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        final int id;
        final int footholdId;
        final int x1;
        final int y1;
        final int x2;
        final int y2;

        Region(int id, Foothold foothold) {
            this.id = id;
            this.footholdId = foothold.getId();
            this.x1 = foothold.getX1();
            this.y1 = foothold.getY1();
            this.x2 = foothold.getX2();
            this.y2 = foothold.getY2();
        }

        int minX() {
            return Math.min(x1, x2);
        }

        int maxX() {
            return Math.max(x1, x2);
        }

        int clampX(int x) {
            return Math.max(minX(), Math.min(maxX(), x));
        }

        int yAt(int x) {
            int clampedX = clampX(x);
            if (x1 == x2) {
                return Math.min(y1, y2);
            }

            double ratio = (clampedX - x1) / (double) (x2 - x1);
            return (int) Math.round(y1 + ((y2 - y1) * ratio));
        }

        Point pointAt(int x) {
            int clampedX = clampX(x);
            return new Point(clampedX, yAt(clampedX));
        }

        Point leftPoint() {
            return pointAt(minX());
        }

        Point centerPoint() {
            return pointAt((minX() + maxX()) / 2);
        }

        Point rightPoint() {
            return pointAt(maxX());
        }
    }

    static final class Edge implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        final int fromRegionId;
        final int toRegionId;
        final EdgeType type;
        final Point startPoint;
        final Point endPoint;
        final int launchStepX;
        final int portalId;
        final int cost;

        Edge(int fromRegionId, int toRegionId, EdgeType type, Point startPoint, Point endPoint, int launchStepX, int portalId, int cost) {
            this.fromRegionId = fromRegionId;
            this.toRegionId = toRegionId;
            this.type = type;
            this.startPoint = new Point(startPoint);
            this.endPoint = new Point(endPoint);
            this.launchStepX = launchStepX;
            this.portalId = portalId;
            this.cost = cost;
        }
    }

    final int mapId;
    final int version;
    final List<Region> regions;
    final Map<Integer, Region> regionsById;
    final Map<Integer, Integer> regionIdByFootholdId;
    final Map<Integer, List<Edge>> outgoingByRegionId;

    BotNavigationGraph(int mapId,
                       int version,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId) {
        this.mapId = mapId;
        this.version = version;
        this.regions = new java.util.ArrayList<>(regions);
        this.regionsById = new HashMap<>(regionsById);
        this.regionIdByFootholdId = new HashMap<>(regionIdByFootholdId);
        this.outgoingByRegionId = new HashMap<>();
        for (Map.Entry<Integer, List<Edge>> entry : outgoingByRegionId.entrySet()) {
            this.outgoingByRegionId.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
    }

    Region getRegion(int regionId) {
        return regionsById.get(regionId);
    }

    List<Edge> getOutgoing(int regionId) {
        return outgoingByRegionId.getOrDefault(regionId, Collections.emptyList());
    }

    int findRegionId(MapleMap map, Point position) {
        if (map.getFootholds() == null) {
            return -1;
        }

        Foothold foothold = map.getFootholds().findBelow(new Point(position.x, position.y - BotMovementManager.cfg.MAX_SLOPE_UP));
        if (foothold == null) {
            foothold = map.getFootholds().findBelow(position);
        }
        if (foothold == null) {
            return -1;
        }

        return regionIdByFootholdId.getOrDefault(foothold.getId(), -1);
    }
}
