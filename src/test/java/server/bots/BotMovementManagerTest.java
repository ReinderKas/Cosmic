package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotMovementManagerTest {
    @Test
    void shouldNotHoldClimbIdleWhileCommittedClimbEdgeIsActive() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.navEdge = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.CLIMB,
                new Point(0, 0), new Point(0, -100),
                0, 0, 10, -100, 40, 100
        );

        assertFalse(BotMovementManager.shouldHoldClimbIdle(entry, 0, 0));
    }

    @Test
    void shouldAllowIdleClimbHoldWithoutCommittedClimbEdge() {
        BotEntry entry = new BotEntry(null, null, null);

        assertTrue(BotMovementManager.shouldHoldClimbIdle(entry, 0, 0));
    }

    @Test
    void shouldAllowWalkingAlongUpperPlatformWhenExactGroundProbeWouldHitLowerPlatform() {
        MapleMap map = mock(MapleMap.class);
        when(map.getPointBelow(any(Point.class))).thenAnswer(invocation -> {
            Point probe = invocation.getArgument(0);
            if (probe.y >= 151) {
                return new Point(probe.x, 215);
            }
            return new Point(probe.x, 150);
        });
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);

        assertTrue(BotMovementManager.isPathWalkable(bot, new Point(-73, 151), 8));
    }

    @Test
    void shouldHoldCommittedClimbEdgeWhenAlreadyAtAnchorY() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(668, 1757));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(668, 1727, 1980, false);
        entry.navEdge = new BotNavigationGraph.Edge(
                68, 54, BotNavigationGraph.EdgeType.CLIMB,
                new Point(668, 1757), new Point(796, 2025),
                8, 0, 668, 1727, 1980, 650
        );

        BotMovementManager.tickClimbing(entry, new Point(668, 1757), false);

        assertEquals(new Point(668, 1757), bot.getPosition());
    }

    @Test
    void shouldKeepRopeGrabEnabledWhenJumpingFromRopeToRope() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(668, 1757));
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(668, 1727, 1980, false);

        BotMovementManager.jumpToRope(entry, bot, 8);

        assertTrue(entry.inAir);
        assertTrue(entry.climbUpIntent);
        assertEquals(0, entry.ropeGrabCooldownMs);
        assertEquals(668, entry.blockedRopeGrab.x());
    }
}
