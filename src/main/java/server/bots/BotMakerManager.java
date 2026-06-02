package server.bots;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.processor.action.MakerProcessor;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles the "make monster crystals" bot command: scans for eligible monster-leftover
 * etc stacks and converts them through the shared {@link MakerProcessor} Maker path,
 * one craft every {@link #CRAFT_INTERVAL_MS} ms.
 */
final class BotMakerManager {
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();
    private static final int LEFTOVERS_PER_CRYSTAL = 100;   // Maker type-3 recipe req count
    private static final long CRAFT_INTERVAL_MS = 5000L;    // 5 seconds per craft
    private static final int LONG_BATCH_THRESHOLD = 10;     // "will take a while" past this many crafts
    private static final Set<Integer> ACTIVE = ConcurrentHashMap.newKeySet();

    private static final String[] START_VERBS = {"creating", "making", "crafting"};

    private BotMakerManager() {
    }

    static void handleMakeCrystals(BotEntry entry) {
        Character bot = entry.bot;
        if (bot == null) {
            return;
        }

        if (ACTIVE.contains(bot.getId())) {
            BotManager.getInstance().botReply(entry, "still working on the last batch, hang on");
            return;
        }

        if (MakerProcessor.getMakerSkillLevel(bot) < 1) {
            BotManager.getInstance().botReply(entry, "I can't — I don't have the Maker skill");
            return;
        }

        List<Integer> queue = collectCraftQueue(bot);   // one entry per craft, holding the leftover itemid
        if (queue.isEmpty()) {
            BotManager.getInstance().botReply(entry,
                    "I don't have any leftovers I can turn into monster crystals (need 100+ of a drop)");
            return;
        }

        int total = queue.size();
        String verb = START_VERBS[ThreadLocalRandom.current().nextInt(START_VERBS.length)];
        String msg = "ok " + verb + " " + total + " crystal" + (total == 1 ? "" : "s");
        if (total > LONG_BATCH_THRESHOLD) {
            msg += ", will take a while";
        }
        BotManager.getInstance().botReply(entry, msg);

        ACTIVE.add(bot.getId());
        BotManager.after(BotManager.randMs(900, 1100), () -> craftNext(entry, queue, 0));
    }

    private static List<Integer> collectCraftQueue(Character bot) {
        List<Integer> queue = new ArrayList<>();
        Inventory etc = bot.getInventory(InventoryType.ETC);
        etc.lockInventory();
        try {
            Set<Integer> seen = new HashSet<>();
            for (Item item : etc.list()) {
                int itemId = item.getItemId();
                if (!seen.add(itemId)) {
                    continue;   // count each distinct leftover once; countById sums all stacks
                }
                int crafts = etc.countById(itemId) / LEFTOVERS_PER_CRYSTAL;
                if (crafts <= 0 || ii.getMakerCrystalFromLeftover(itemId) == -1) {
                    continue;
                }
                for (int i = 0; i < crafts; i++) {
                    queue.add(itemId);
                }
            }
        } finally {
            etc.unlockInventory();
        }
        return queue;
    }

    private static void craftNext(BotEntry entry, List<Integer> queue, int index) {
        Character bot = entry.bot;
        if (bot == null || !bot.isLoggedin()) {
            if (bot != null) {
                ACTIVE.remove(bot.getId());
            }
            return;
        }

        if (index >= queue.size()) {
            ACTIVE.remove(bot.getId());
            int total = queue.size();
            BotManager.getInstance().botReply(entry,
                    "done — " + total + " crystal" + (total == 1 ? "" : "s") + " made");
            return;
        }

        Client c = bot.getClient();
        short status;
        if (c == null || !c.tryacquireClient()) {
            ACTIVE.remove(bot.getId());
            BotManager.getInstance().botReply(entry, "had to stop crafting, I'm busy with something else");
            return;
        }
        try {
            status = MakerProcessor.makeLeftoverCrystal(c, queue.get(index));
        } finally {
            c.releaseClient();
        }

        if (status != 0) {
            ACTIVE.remove(bot.getId());
            BotManager.getInstance().botReply(entry, abortReason(status));
            return;
        }

        BotManager.after(CRAFT_INTERVAL_MS, () -> craftNext(entry, queue, index + 1));
    }

    private static String abortReason(short status) {
        return switch (status) {
            case 1 -> "ran out of leftovers, stopping there";
            case 2 -> "I don't have enough mesos to keep going, stopping";
            case 5 -> "my inventory's full, stopping";
            default -> "couldn't finish that crystal, stopping";
        };
    }
}
