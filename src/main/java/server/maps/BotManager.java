package server.maps;

import client.Character;
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

    // Movement
    private static final int TICK_MS       = 300;
    private static final int STEP          = 70;   // px/tick (~233 px/sec walking)
    private static final int STOP_DIST     = 50;   // stand still within this range

    // Physics
    private static final float GRAVITY     = 6f;   // px/tick added to velY each tick
    private static final float JUMP_FORCE  = 46f;  // initial upward velocity — arc height ~176px
    private static final float MAX_FALL    = 50f;  // terminal fall velocity

    // Jump threshold: try to jump when target is this many px higher on screen (lower Y)
    private static final int JUMP_Y_THRESH  = 35;
    // Cooldown in ticks before re-attempting a jump (~3s at 300ms/tick)
    private static final int JUMP_COOLDOWN = 10;

    public static BotManager getInstance() {
        return instance;
    }

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

    private static class BotEntry {
        final Character bot;
        volatile boolean following = false;
        final ScheduledFuture<?> task;

        // Physics state
        float velY = 0f;
        boolean inAir = false;
        int jumpCooldown = 0;

        // Foothold index, rebuilt when map changes
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
    // Tick
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

        // Map change — teleport instantly, reset physics
        if (bot.getMapId() != owner.getMapId()) {
            bot.changeMap(owner.getMap(), owner.getPosition());
            entry.inAir = false;
            entry.velY = 0f;
            return;
        }

        // Rebuild foothold index when entering a new map
        if (entry.lastMapId != bot.getMapId()) {
            entry.fhIndex = buildFhIndex(bot.getMap());
            entry.lastMapId = bot.getMapId();
        }

        Point botPos = bot.getPosition();
        Point targetPos = owner.getPosition();
        int dx = targetPos.x - botPos.x;
        int dy = targetPos.y - botPos.y; // negative = target is higher on screen

        // Desired X step toward target (0 if already close enough)
        int stepX = Math.abs(dx) > STOP_DIST
                ? Math.min(Math.abs(dx), STEP) * (dx >= 0 ? 1 : -1)
                : 0;
        int newX = botPos.x + stepX;

        if (entry.inAir) {
            tickAirborne(entry, bot, botPos, newX, dx, dy);
        } else {
            tickGrounded(entry, bot, botPos, targetPos, newX, stepX, dx, dy);
        }
    }

    // -------------------------------------------------------------------------
    // Airborne physics
    // -------------------------------------------------------------------------

    private void tickAirborne(BotEntry entry, Character bot,
                               Point botPos, int newX, int dx, int dy) {
        entry.velY = Math.min(entry.velY + GRAVITY, MAX_FALL);
        int newY = botPos.y + (int) entry.velY;

        // Only check landing when falling down (velY > 0)
        if (entry.velY > 0) {
            Point floorPt = bot.getMap().getPointBelow(new Point(newX, botPos.y - 1));
            if (floorPt != null && floorPt.y <= newY) {
                // Landed
                entry.inAir = false;
                entry.velY = 0f;
                entry.jumpCooldown = 0;
                bot.setPosition(new Point(newX, floorPt.y));
                bot.setStance(5); // stand on landing
                bot.broadcastStance();
                return;
            }
        }

        // Still airborne
        bot.setPosition(new Point(newX, newY));
        bot.setStance(dx >= 0 ? 6 : 7); // jump right / jump left
        bot.broadcastStance();
    }

    // -------------------------------------------------------------------------
    // Ground movement with foothold awareness
    // -------------------------------------------------------------------------

    private void tickGrounded(BotEntry entry, Character bot,
                               Point botPos, Point targetPos,
                               int newX, int stepX, int dx, int dy) {
        // Find foothold we're currently standing on
        Foothold currentFh = bot.getMap().getFootholds()
                .findBelow(new Point(botPos.x, botPos.y - 1));

        if (currentFh == null) {
            // No foothold under us — start falling
            entry.inAir = true;
            entry.velY = 0f;
            return;
        }

        // Tick down jump cooldown
        if (entry.jumpCooldown > 0) entry.jumpCooldown--;

        // Target is significantly higher — jump proactively from anywhere on foothold
        if (dy < -JUMP_Y_THRESH && entry.jumpCooldown == 0) {
            initiateJump(entry);
            entry.jumpCooldown = JUMP_COOLDOWN;
            bot.setStance(dx >= 0 ? 6 : 7);
            bot.broadcastStance();
            return;
        }

        // Close enough to target — stand still
        if (Math.abs(dx) < STOP_DIST && Math.abs(dy) < STOP_DIST) {
            bot.setStance(5);
            bot.broadcastStance();
            return;
        }

        // Check if we're walking toward a foothold edge
        boolean towardRightEdge = stepX > 0 && newX >= currentFh.getX2();
        boolean towardLeftEdge  = stepX < 0 && newX <= currentFh.getX1();

        if (towardRightEdge || towardLeftEdge) {
            int neighborId = towardRightEdge ? currentFh.getNext() : currentFh.getPrev();
            Foothold neighbor = entry.fhIndex.get(neighborId);

            if (neighbor != null && !neighbor.isWall()) {
                // Connected foothold — check height difference
                int yDiff = neighbor.getY1() - currentFh.getY1(); // negative = neighbor is higher
                if (yDiff < -JUMP_Y_THRESH) {
                    // Neighbor is significantly higher: jump to reach it
                    initiateJump(entry);
                    bot.setStance(dx >= 0 ? 6 : 7);
                    bot.broadcastStance();
                    return;
                }
                // Same level or lower: walk onto it naturally (fall handles the drop)
            } else {
                // No connected foothold at this edge
                if (dy < -JUMP_Y_THRESH) {
                    // Target is on a higher platform with no chain — jump toward it
                    initiateJump(entry);
                    bot.setStance(dx >= 0 ? 6 : 7);
                    bot.broadcastStance();
                    return;
                }
                // Target is at same level or below: walk off the edge and fall naturally
            }
        }

        // Normal ground walk — snap Y to terrain at new X
        Point snapped = bot.getMap().getPointBelow(new Point(newX, botPos.y - 1));
        int newY = snapped != null ? snapped.y : botPos.y;

        int newStance;
        if (stepX > 0)       newStance = 2; // walk right
        else if (stepX < 0)  newStance = 3; // walk left
        else                  newStance = 5; // stand

        bot.setPosition(new Point(newX, newY));
        bot.setStance(newStance);
        bot.broadcastStance();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void initiateJump(BotEntry entry) {
        entry.velY = -JUMP_FORCE;
        entry.inAir = true;
    }

    private static Map<Integer, Foothold> buildFhIndex(MapleMap map) {
        Map<Integer, Foothold> index = new HashMap<>();
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            index.put(fh.getId(), fh);
        }
        return index;
    }
}
