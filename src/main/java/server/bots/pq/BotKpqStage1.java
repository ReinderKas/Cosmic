package server.bots.pq;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import scripting.event.EventInstanceManager;
import server.bots.BotChatManager;
import server.bots.BotEntry;
import server.life.NPC;

import java.awt.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * KPQ Stage 1 automation — bot navigates to Cloto, gets assigned a coupon target,
 * grinds until it has enough, exchanges them at Cloto, then delivers the pass to owner.
 */
final class BotKpqStage1 {

    private static final int KPQ_MAP_MIN  = 103000800;
    private static final int KPQ_MAP_MAX  = 103000805;
    private static final int NPC_CLOTO    = 9020001;
    private static final int ITEM_COUPON  = 4001007;
    private static final int ITEM_PASS    = 4001008;

    // Question index (1-7) → required coupon count; index 0 unused
    private static final int[] ANSWERS = {0, 10, 35, 20, 25, 25, 30, 8};

    static final int IDLE         = 0;
    static final int FIRST_WALK   = 1;  // walking to Cloto for assignment
    static final int FIRST_WAIT   = 2;  // standing at Cloto; brief pause then assign
    static final int GRINDING     = 3;  // farming coupons
    static final int SECOND_WALK  = 4;  // walking to Cloto to exchange
    static final int SECOND_WAIT  = 5;  // at Cloto; brief pause then exchange
    static final int DELIVERING   = 6;  // walking to owner with pass
    static final int DONE         = 7;  // finished

    private static final int NEAR_NPC_PX    = 80;
    private static final int NEAR_OWNER_PX  = 80;
    private static final long WAIT_MS       = 1800;
    private static final int CHAT_TICKS_MIN = 25;
    private static final int CHAT_TICKS_VAR = 15;

    // ── entry point ─────────────────────────────────────────────────────────

    static void tick(BotEntry entry, Character bot, Character owner) {
        int mapId = bot.getMapId();
        if (mapId < KPQ_MAP_MIN || mapId > KPQ_MAP_MAX) {
            if (entry.kpq.state != IDLE) reset(entry);
            return;
        }

        switch (entry.kpq.state) {
            case IDLE        -> tickIdle(entry, bot);
            case FIRST_WALK  -> tickFirstWalk(entry, bot);
            case FIRST_WAIT  -> tickFirstWait(entry, bot);
            case GRINDING    -> tickGrinding(entry, bot);
            case SECOND_WALK -> tickSecondWalk(entry, bot);
            case SECOND_WAIT -> tickSecondWait(entry, bot);
            case DELIVERING  -> tickDelivering(entry, bot, owner);
        }
    }

    static boolean isNpcLocked(BotEntry entry) {
        return entry.kpq.state == FIRST_WAIT || entry.kpq.state == SECOND_WAIT;
    }

    // ── state ticks ─────────────────────────────────────────────────────────

    private static void tickIdle(BotEntry entry, Character bot) {
        // Trigger when bot picks up the first coupon
        if (bot.getItemQuantity(ITEM_COUPON, false) > 0) {
            to(entry, FIRST_WALK);
            entry.kpq.navTarget = getNpcPos(bot);
        }
    }

    private static void tickFirstWalk(BotEntry entry, Character bot) {
        Point npcPos = getNpcPos(bot);
        if (npcPos == null) return;
        entry.kpq.navTarget = npcPos;
        if (near(bot, npcPos, NEAR_NPC_PX)) {
            entry.kpq.navTarget = null;
            entry.kpq.waitUntilMs = System.currentTimeMillis() + WAIT_MS;
            to(entry, FIRST_WAIT);
        }
    }

    private static void tickFirstWait(BotEntry entry, Character bot) {
        if (System.currentTimeMillis() < entry.kpq.waitUntilMs) return;

        EventInstanceManager eim = bot.getEventInstance();
        int question = (eim != null) ? eim.gridCheck(bot) : -1;
        if (question == -1) {
            question = ThreadLocalRandom.current().nextInt(1, ANSWERS.length);
            if (eim != null) eim.gridInsert(bot, question);
        }
        int target = (question < ANSWERS.length) ? ANSWERS[question] : ANSWERS[1];
        entry.kpq.couponTarget = target;
        BotChatManager.queueBotSay(entry, "I need " + target + " coupons. Let's hunt!");
        to(entry, GRINDING);
    }

    private static void tickGrinding(BotEntry entry, Character bot) {
        int have = bot.getItemQuantity(ITEM_COUPON, false);
        int need = entry.kpq.couponTarget;

        if (entry.kpq.chatCooldown <= 0) {
            entry.kpq.chatCooldown = CHAT_TICKS_MIN + ThreadLocalRandom.current().nextInt(CHAT_TICKS_VAR);
            if (have < need) {
                BotChatManager.queueBotSay(entry, have + " / " + need + " coupons.");
            }
        } else {
            entry.kpq.chatCooldown--;
        }

        if (have >= need) {
            BotChatManager.queueBotSay(entry, "Got " + need + "! Turning in now.");
            to(entry, SECOND_WALK);
            entry.kpq.navTarget = getNpcPos(bot);
        }
    }

    private static void tickSecondWalk(BotEntry entry, Character bot) {
        Point npcPos = getNpcPos(bot);
        if (npcPos == null) return;
        entry.kpq.navTarget = npcPos;
        if (near(bot, npcPos, NEAR_NPC_PX)) {
            entry.kpq.navTarget = null;
            entry.kpq.waitUntilMs = System.currentTimeMillis() + WAIT_MS;
            to(entry, SECOND_WAIT);
        }
    }

    private static void tickSecondWait(BotEntry entry, Character bot) {
        if (System.currentTimeMillis() < entry.kpq.waitUntilMs) return;

        int target = entry.kpq.couponTarget;
        if (bot.getItemQuantity(ITEM_COUPON, false) >= target) {
            InventoryManipulator.removeById(bot.getClient(), InventoryType.ETC, ITEM_COUPON, target, false, false);
            InventoryManipulator.addById(bot.getClient(), ITEM_PASS, (short) 1);
            EventInstanceManager eim = bot.getEventInstance();
            if (eim != null) eim.gridInsert(bot, 0);
        }
        BotChatManager.queueBotSay(entry, "Got my pass! Bringing it to you.");
        to(entry, DELIVERING);
    }

    private static void tickDelivering(BotEntry entry, Character bot, Character owner) {
        Point ownerPos = owner.getPosition();
        entry.kpq.navTarget = ownerPos;
        if (near(bot, ownerPos, NEAR_OWNER_PX)) {
            entry.kpq.navTarget = null;
            if (bot.getItemQuantity(ITEM_PASS, false) > 0) {
                InventoryManipulator.removeById(bot.getClient(), InventoryType.ETC, ITEM_PASS, 1, false, false);
                InventoryManipulator.addById(owner.getClient(), ITEM_PASS, (short) 1);
            }
            BotChatManager.queueBotSay(entry, "Here's your pass!");
            to(entry, DONE);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void to(BotEntry entry, int newState) {
        entry.kpq.state = newState;
        entry.kpq.chatCooldown = 0;
    }

    private static void reset(BotEntry entry) {
        entry.kpq.state        = IDLE;
        entry.kpq.couponTarget = -1;
        entry.kpq.waitUntilMs  = 0;
        entry.kpq.chatCooldown = 0;
        entry.kpq.navTarget    = null;
    }

    private static Point getNpcPos(Character bot) {
        NPC npc = bot.getMap().getNPCById(NPC_CLOTO);
        return (npc != null) ? npc.getPosition() : null;
    }

    private static boolean near(Character bot, Point target, int dist) {
        Point p = bot.getPosition();
        return Math.abs(p.x - target.x) <= dist && Math.abs(p.y - target.y) <= dist;
    }
}
