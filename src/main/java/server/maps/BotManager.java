package server.maps;

import client.Character;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import tools.PacketCreator;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public class BotManager {
    private static final Logger log = LoggerFactory.getLogger(BotManager.class);
    private static final BotManager instance = new BotManager();

    private static final int TICK_MS = 300;
    private static final int STEP = 70;       // px per tick (~233 px/sec)
    private static final int STOP_DIST = 50;  // stop when within this range
    private static final int JUMP_Y_THRESH = 100; // negative dy threshold to trigger jump

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

        BotEntry(Character bot, ScheduledFuture<?> task) {
            this.bot = bot;
            this.task = task;
        }
    }

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
                botSay(entry.bot, randomReply(STOP_REPLIES));
                entry.following = false;
            }, 2000);
        }
    }

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

        // Different map — teleport bot to owner
        if (bot.getMapId() != owner.getMapId()) {
            bot.changeMap(owner.getMap(), owner.getPosition());
            return;
        }

        Point botPos = bot.getPosition();
        Point targetPos = owner.getPosition();
        int dx = targetPos.x - botPos.x;
        int dy = targetPos.y - botPos.y; // negative dy = target is higher on screen

        if (Math.abs(dx) < STOP_DIST && Math.abs(dy) < STOP_DIST) {
            bot.setStance(0);
            bot.broadcastStance();
            return;
        }

        int stepX = Math.min(Math.abs(dx), STEP) * (dx >= 0 ? 1 : -1);
        int newX = botPos.x + stepX;
        int newY;
        int newStance;

        if (dy < -JUMP_Y_THRESH) {
            // Target is on a higher platform — jump toward it
            newStance = dx >= 0 ? 4 : 5;
            newY = botPos.y + Math.max(dy, -STEP * 2);
        } else {
            // Walk — snap Y to ground below new X
            Point snapped = bot.getMap().getPointBelow(new Point(newX, botPos.y - 1));
            newY = snapped != null ? snapped.y : botPos.y;
            newStance = dx >= 0 ? 2 : 3;
        }

        bot.setPosition(new Point(newX, newY));
        bot.setStance(newStance);
        bot.broadcastStance();
    }
}
