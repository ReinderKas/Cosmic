package server.bots.pq;

import client.Character;
import server.bots.BotEntry;

/**
 * Single call-site for all per-map party-quest bot automation.
 * BotManager calls {@link #tick} once per bot tick; each PQ class handles its own map range.
 */
public final class BotPqHooks {

    private BotPqHooks() {}

    public static void tick(BotEntry entry, Character bot, Character owner) {
        BotKpqStage1.tick(entry, bot, owner);
    }

    /**
     * Returns true when the bot should stand idle at an NPC and skip normal AI.
     */
    public static boolean isNpcLocked(BotEntry entry) {
        return BotKpqStage1.isNpcLocked(entry);
    }

    /**
     * Returns true if the bot is in a PQ map that requires grind mode to be active.
     */
    public static boolean requiresGrind(BotEntry entry, Character bot) {
        int mapId = bot.getMapId();
        return mapId >= BotKpqStage1.KPQ_MAP_MIN && mapId <= BotKpqStage1.KPQ_MAP_MAX;
    }
}
