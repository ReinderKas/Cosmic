package server.maps;

import client.Character;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import tools.PacketCreator;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public class BotManager {
    private static final Logger log = LoggerFactory.getLogger(BotManager.class);
    private static final BotManager instance = new BotManager();

    // Movement — tuned to real v83: observed walk = 133 px/s, packet rate ~100ms
    private static final int   TICK_MS        = 100;
    private static final int   STEP           = 13;    // px/tick  (133 px/s * 0.1s)
    private static final int   STOP_DIST      = 50;
    private static final int   WALK_VEL       = 133;   // px/s — written into xv field for client interpolation

    // Physics — at 100ms/tick: apex ~8 ticks up → height = v0²/(2g), target ~168px
    //   32g = 168 → g≈5.25, v0=g*8≈42
    private static final float GRAVITY        = 5.25f;
    private static final float JUMP_FORCE     = 42f;
    private static final float MAX_FALL       = 16f;   // ~160px/s terminal velocity

    // Jump control
    private static final int   JUMP_Y_THRESH  = 35;
    private static final int   JUMP_COOLDOWN  = 10;   // ticks (~1s)
    private static final int   MAX_SNAP_DROP  = 12;   // px — larger drop → go airborne instead of snapping

    // Rope climbing — ~130px/s upward
    private static final int   CLIMB_SPEED    = 13;   // px/tick upward
    private static final int   CLIMB_VEL      = 130;  // px/s for yv field
    private static final int   ROPE_SEEK_X    = 150;  // horizontal search radius for ropes

    public static BotManager getInstance() { return instance; }

    private final Map<Integer, BotEntry> bots = new ConcurrentHashMap<>();

    private static final Pattern FOLLOW_PATTERN = Pattern.compile(
            "\\b(follow( me)?|come( here)?|f me)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_PATTERN = Pattern.compile(
            "\\b(stop|stay|wait|halt)\\b", Pattern.CASE_INSENSITIVE);

    private static final List<String> FOLLOW_REPLIES = List.of(
            "ok", "sure", "on my way!", "got it", "coming!",
            "roger that", "yep!", "alright", "right behind you",
            "aye aye!", "lets go!", "as you wish");
    private static final List<String> STOP_REPLIES = List.of(
            "ok", "sure", "alright", "got it", "stopping",
            "ok ill wait here", "ill be here", "np", "standing by",
            "understood", "ok boss");

    private static String randomReply(List<String> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private void botSay(Character bot, String text) {
        bot.getMap().broadcastMessage(PacketCreator.getChatText(bot.getId(), text, false, 0));
    }

    // -------------------------------------------------------------------------
    // Entry state
    // -------------------------------------------------------------------------

    private static class BotEntry {
        final Character bot;
        volatile boolean following = false;
        final ScheduledFuture<?> task;

        // Physics
        float velY = 0f;
        boolean inAir = false;
        int jumpCooldown = 0;

        // Rope climbing
        boolean climbing = false;
        int climbX = 0;
        int climbTopY = Integer.MAX_VALUE;

        // Foothold index, rebuilt on map change
        int lastMapId = -1;
        Map<Integer, Foothold> fhIndex = new HashMap<>();

        BotEntry(Character bot, ScheduledFuture<?> task) {
            this.bot = bot;
            this.task = task;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void registerBot(int ownerCharId, Character bot) {
        BotEntry old = bots.remove(ownerCharId);
        if (old != null) old.task.cancel(false);

        ScheduledFuture<?> task = TimerManager.getInstance().register(
                () -> tick(ownerCharId, bot), TICK_MS);
        bots.put(ownerCharId, new BotEntry(bot, task));
    }

    public void removeBot(int ownerCharId) {
        BotEntry entry = bots.remove(ownerCharId);
        if (entry != null) entry.task.cancel(false);
    }

    public Character getBot(int ownerCharId) {
        BotEntry entry = bots.get(ownerCharId);
        return entry != null ? entry.bot : null;
    }

    public void handleChat(Character owner, String message) {
        BotEntry entry = bots.get(owner.getId());
        if (entry == null) return;

        if (FOLLOW_PATTERN.matcher(message).find()) {
            TimerManager.getInstance().schedule(() -> {
                botSay(entry.bot, randomReply(FOLLOW_REPLIES));
                entry.following = true;
            }, 2000);
        } else if (STOP_PATTERN.matcher(message).find()) {
            TimerManager.getInstance().schedule(() -> {
                entry.following = false;
                TimerManager.getInstance().schedule(() ->
                        botSay(entry.bot, randomReply(STOP_REPLIES)), 2000);
            }, 1000);
        }
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------

    private void tick(int ownerCharId, Character bot) {
        BotEntry entry = bots.get(ownerCharId);
        if (entry == null || !entry.following) return;

        Character owner = Server.getInstance()
                .getWorld(bot.getWorld())
                .getPlayerStorage()
                .getCharacterById(ownerCharId);
        if (owner == null) {
            entry.following = false;
            return;
        }

        // Map change — teleport instantly, reset all motion state
        if (bot.getMapId() != owner.getMapId()) {
            bot.changeMap(owner.getMap(), owner.getPosition());
            entry.inAir = false;
            entry.climbing = false;
            entry.velY = 0f;
            return;
        }

        // Rebuild foothold index on map change
        if (entry.lastMapId != bot.getMapId()) {
            entry.fhIndex = buildFhIndex(bot.getMap());
            entry.lastMapId = bot.getMapId();
        }

        Point botPos  = bot.getPosition();
        Point ownerPos = owner.getPosition();

        if (entry.climbing) {
            tickClimbing(entry, bot, botPos, ownerPos);
        } else if (entry.inAir) {
            int dx = ownerPos.x - botPos.x;
            int stepX = calcStepX(botPos.x, ownerPos.x);
            tickAirborne(entry, bot, botPos, botPos.x + stepX, dx);
        } else {
            tickGrounded(entry, bot, botPos, ownerPos);
        }
    }

    // -------------------------------------------------------------------------
    // Rope climbing
    // -------------------------------------------------------------------------

    private void tickClimbing(BotEntry entry, Character bot, Point botPos, Point ownerPos) {
        int dy = ownerPos.y - botPos.y;

        // Reached rope top or close enough vertically — dismount
        if (botPos.y <= entry.climbTopY || dy > -JUMP_Y_THRESH) {
            entry.climbing = false;
            entry.inAir = true;
            entry.velY = 0f;
            return;
        }

        int newY = botPos.y - CLIMB_SPEED;
        bot.setPosition(new Point(entry.climbX, newY));
        bot.setStance(16);
        broadcastMovement(bot, 0, -CLIMB_VEL);
    }

    // -------------------------------------------------------------------------
    // Airborne physics
    // -------------------------------------------------------------------------

    private void tickAirborne(BotEntry entry, Character bot, Point botPos, int newX, int dx) {
        entry.velY = Math.min(entry.velY + GRAVITY, MAX_FALL);
        int newY = botPos.y + (int) entry.velY;

        // Landing check (only when falling)
        if (entry.velY > 0) {
            Point floorPt = bot.getMap().getPointBelow(new Point(newX, botPos.y - 1));
            if (floorPt != null && floorPt.y <= newY) {
                entry.inAir = false;
                entry.velY = 0f;
                entry.jumpCooldown = 0;
                bot.setPosition(new Point(newX, floorPt.y));
                bot.setStance(5);
                broadcastMovement(bot, 0, 0);
                return;
            }
        }

        bot.setPosition(new Point(newX, newY));
        bot.setStance(dx >= 0 ? 6 : 7);
        int velXBcast = dx >= 0 ? WALK_VEL : -WALK_VEL;
        int velYBcast = (int) (entry.velY * (1000f / TICK_MS)); // convert px/tick → px/s
        broadcastMovement(bot, velXBcast, velYBcast);
    }

    // -------------------------------------------------------------------------
    // Ground movement with foothold awareness
    // -------------------------------------------------------------------------

    private void tickGrounded(BotEntry entry, Character bot, Point botPos, Point ownerPos) {
        int dx = ownerPos.x - botPos.x;
        int dy = ownerPos.y - botPos.y; // negative = owner is higher on screen

        Foothold currentFh = bot.getMap().getFootholds()
                .findBelow(new Point(botPos.x, botPos.y - 1));
        if (currentFh == null) {
            entry.inAir = true;
            entry.velY = 0f;
            return;
        }

        if (entry.jumpCooldown > 0) entry.jumpCooldown--;

        // Rope check — prefer rope over jumping when target is far above
        if (dy < -JUMP_Y_THRESH * 3) {
            Rope rope = findNearbyRope(bot, botPos, ownerPos.y);
            if (rope != null) {
                int rdx = rope.x() - botPos.x;
                if (Math.abs(rdx) < STEP + 5) {
                    // At rope base — start climbing
                    entry.climbing = true;
                    entry.climbX = rope.x();
                    entry.climbTopY = rope.topY();
                    bot.setPosition(new Point(rope.x(), botPos.y));
                    bot.setStance(16);
                    broadcastMovement(bot, 0, -CLIMB_VEL);
                    return;
                }
                // Walk toward rope X
                int stepToRope = Math.min(Math.abs(rdx), STEP) * (rdx >= 0 ? 1 : -1);
                int newX = botPos.x + stepToRope;
                Point snapped = bot.getMap().getPointBelow(new Point(newX, botPos.y - 1));
                if (snapped != null && snapped.y <= botPos.y + MAX_SNAP_DROP) {
                    bot.setPosition(new Point(newX, snapped.y));
                    bot.setStance(rdx >= 0 ? 2 : 3);
                    broadcastMovement(bot, rdx >= 0 ? WALK_VEL : -WALK_VEL, 0);
                }
                return;
            }
        }

        // Proactive jump when owner is significantly above
        if (dy < -JUMP_Y_THRESH && entry.jumpCooldown == 0) {
            initiateJump(entry);
            entry.jumpCooldown = JUMP_COOLDOWN;
            bot.setStance(dx >= 0 ? 6 : 7);
            int jumpVelY = -(int) (JUMP_FORCE * (1000f / TICK_MS));
            broadcastMovement(bot, dx >= 0 ? WALK_VEL : -WALK_VEL, jumpVelY);
            return;
        }

        // Close enough — stand still
        if (Math.abs(dx) < STOP_DIST && Math.abs(dy) < STOP_DIST) {
            bot.setStance(5);
            broadcastMovement(bot, 0, 0);
            return;
        }

        int stepX = calcStepX(botPos.x, ownerPos.x);
        int newX  = botPos.x + stepX;

        // Foothold edge detection
        boolean towardRightEdge = stepX > 0 && newX >= currentFh.getX2();
        boolean towardLeftEdge  = stepX < 0 && newX <= currentFh.getX1();

        if (towardRightEdge || towardLeftEdge) {
            int neighborId = towardRightEdge ? currentFh.getNext() : currentFh.getPrev();
            Foothold neighbor = entry.fhIndex.get(neighborId);

            if (neighbor != null && !neighbor.isWall()) {
                int yDiff = neighbor.getY1() - currentFh.getY1();
                if (yDiff < -JUMP_Y_THRESH && entry.jumpCooldown == 0) {
                    initiateJump(entry);
                    bot.setStance(dx >= 0 ? 6 : 7);
                    int jumpVelY = -(int) (JUMP_FORCE * (1000f / TICK_MS));
                    broadcastMovement(bot, dx >= 0 ? WALK_VEL : -WALK_VEL, jumpVelY);
                    return;
                }
                // Same level or lower — walk onto neighbor naturally
            } else if (dy < -JUMP_Y_THRESH && entry.jumpCooldown == 0) {
                initiateJump(entry);
                bot.setStance(dx >= 0 ? 6 : 7);
                int jumpVelY = -(int) (JUMP_FORCE * (1000f / TICK_MS));
                broadcastMovement(bot, dx >= 0 ? WALK_VEL : -WALK_VEL, jumpVelY);
                return;
            }
        }

        // Normal ground walk — snap Y to terrain at newX
        Point snapped = bot.getMap().getPointBelow(new Point(newX, botPos.y - 1));
        if (snapped == null) {
            entry.inAir = true;
            entry.velY = 0f;
            return;
        }
        if (snapped.y > botPos.y + MAX_SNAP_DROP) {
            // Dropped off ledge — fall naturally
            entry.inAir = true;
            entry.velY = 0f;
            return;
        }

        int velX;
        int newStance;
        if (stepX > 0)      { newStance = 2; velX =  WALK_VEL; }
        else if (stepX < 0) { newStance = 3; velX = -WALK_VEL; }
        else                 { newStance = 5; velX = 0; }

        bot.setPosition(new Point(newX, snapped.y));
        bot.setStance(newStance);
        broadcastMovement(bot, velX, 0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int calcStepX(int botX, int targetX) {
        int dx = targetX - botX;
        return Math.abs(dx) > STOP_DIST
                ? Math.min(Math.abs(dx), STEP) * (dx >= 0 ? 1 : -1)
                : 0;
    }

    private void initiateJump(BotEntry entry) {
        entry.velY = -JUMP_FORCE;
        entry.inAir = true;
    }

    private Rope findNearbyRope(Character bot, Point botPos, int targetY) {
        Rope best = null;
        int bestDist = ROPE_SEEK_X + 1;
        for (Rope r : bot.getMap().getRopes()) {
            int dist = Math.abs(r.x() - botPos.x);
            // Rope must be reachable (bot near bottom of rope) and extend up to target level
            if (dist < bestDist && r.topY() <= targetY && r.bottomY() >= botPos.y - 20) {
                bestDist = dist;
                best = r;
            }
        }
        return best;
    }

    /**
     * Broadcasts a MOVE_PLAYER packet for the bot with real velocity values so the
     * client can smoothly interpolate movement over TICK_MS milliseconds — matching
     * how the server re-broadcasts real player movement packets.
     */
    private void broadcastMovement(Character bot, int velX, int velY) {
        // AbsoluteLifeMovement (cmd=0):
        //   numCmds(1) cmd(1) x(2) y(2) xv(2) yv(2) fh(2) stance(1) duration(2) = 15 bytes
        byte[] d = new byte[15];
        d[0] = 1; // numCmds
        // d[1] = 0; // cmd = AbsoluteLifeMovement (already 0)
        int x = bot.getPosition().x;
        int y = bot.getPosition().y;
        d[2] = (byte) (x & 0xFF);     d[3] = (byte) (x >> 8);
        d[4] = (byte) (y & 0xFF);     d[5] = (byte) (y >> 8);
        d[6] = (byte) (velX & 0xFF);  d[7] = (byte) (velX >> 8);
        d[8] = (byte) (velY & 0xFF);  d[9] = (byte) (velY >> 8);
        // d[10..11] = fh = 0 (client recalculates)
        d[12] = (byte) bot.getStance();
        d[13] = (byte) (TICK_MS & 0xFF); d[14] = (byte) (TICK_MS >> 8);
        InPacket ip = new ByteBufInPacket(Unpooled.wrappedBuffer(d));
        Packet pkt = PacketCreator.movePlayer(bot.getId(), ip, d.length);
        bot.getMap().broadcastMessage(bot, pkt, false);
    }

    private static Map<Integer, Foothold> buildFhIndex(MapleMap map) {
        Map<Integer, Foothold> index = new HashMap<>();
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            index.put(fh.getId(), fh);
        }
        return index;
    }
}
