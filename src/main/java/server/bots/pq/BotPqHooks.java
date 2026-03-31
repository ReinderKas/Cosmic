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
}
