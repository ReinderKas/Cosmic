package server.bots;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.Shop;
import server.ShopFactory;
import server.ShopItem;
import server.StatEffect;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class BotShopManager {

    private static final int SHOP_INTERACT_DIST_MIN = 100;
    private static final int SHOP_INTERACT_DIST_MAX = 601;
    private static final int SHOP_APPROACH_DELAY_MAX_MS = 5001;
    private static final int SHOP_STEP_DELAY_MIN_MS = 3000;
    private static final int SHOP_STEP_DELAY_MAX_MS = 6001;

    private BotShopManager() {}

    private record NpcShopMatch(NPC npc, Shop shop, Point npcPos) {}

    private record BuyReport(int itemId, int quantity, int requestedQuantity, boolean broke) {
        boolean hasShortfall() {
            return broke && quantity < requestedQuantity;
        }
    }

    private record ShopSlotItem(short slot, ShopItem shopItem) {}

    private record PurchaseSequence(BotEntry entry,
                                    Character bot,
                                    Point npcPos,
                                    List<PurchaseAction> actions,
                                    List<String> bought,
                                    BuyReport firstShortfall) {
        PurchaseSequence withFirstShortfall(BuyReport report) {
            if (firstShortfall == null && report != null && report.hasShortfall()) {
                return new PurchaseSequence(entry, bot, npcPos, actions, bought, report);
            }
            return this;
        }
    }

    @FunctionalInterface
    private interface PurchaseAction {
        PurchaseSequence run(PurchaseSequence sequence, Shop shop);
    }

    static void onMapChange(BotEntry entry, Character bot) {
        clearShopState(entry);

        NpcShopMatch match = findBestShop(bot);
        if (match == null) {
            return;
        }

        entry.shopVisitPending = true;
        entry.shopNpcPos = match.npcPos;
        entry.shopInteractDist = (int) BotManager.randMs(SHOP_INTERACT_DIST_MIN, SHOP_INTERACT_DIST_MAX);
        entry.shopApproachDelayMs = (int) BotManager.randMs(0, SHOP_APPROACH_DELAY_MAX_MS);
    }

    static boolean tickShopVisit(BotEntry entry, Character bot) {
        if (!entry.shopVisitPending) {
            return false;
        }
        if (entry.shopNpcPos == null) {
            clearShopState(entry);
            return false;
        }
        if (entry.shopApproachDelayMs > 0) {
            entry.shopApproachDelayMs = BotMovementManager.tickDown(entry.shopApproachDelayMs);
            return false;
        }

        Point botPos = bot.getPosition();
        if (botPos.distanceSq(entry.shopNpcPos) <= (long) entry.shopInteractDist * entry.shopInteractDist) {
            if (!entry.shopSequenceActive) {
                entry.shopSequenceActive = true;
                Point npcPos = entry.shopNpcPos;
                BotManager.after(stepDelayMs(), () -> executePurchases(entry, bot, npcPos));
            }
            return true;
        }

        return true;
    }

    private static NpcShopMatch findBestShop(Character bot) {
        List<MapObject> objects = bot.getMap().getMapObjectsInRange(
                new Point(0, 0), Double.POSITIVE_INFINITY,
                Arrays.asList(MapObjectType.NPC));

        for (MapObject obj : objects) {
            NPC npc = (NPC) obj;
            if (!npc.hasShop()) {
                continue;
            }
            Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
            if (shop == null) {
                continue;
            }
            if (shopHasAnythingNeeded(bot, shop)) {
                return new NpcShopMatch(npc, shop, npc.getPosition());
            }
        }
        return null;
    }

    private static boolean shopHasAnythingNeeded(Character bot, Shop shop) {
        WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        if (needsAmmo(bot, wt) && findAmmoItem(shop, wt) != null) {
            return true;
        }
        if (isRechargeWeaponType(wt) && hasRechargeableInShop(bot)) {
            return true;
        }
        int[] pots = BotPotionManager.countPotions(bot);
        if (pots[0] < BotManager.cfg.POT_LOW_WARN * 5 && findPotionItem(shop, bot, true) != null) {
            return true;
        }
        if (pots[1] < BotManager.cfg.POT_LOW_WARN * 5 && findPotionItem(shop, bot, false) != null) {
            return true;
        }
        return false;
    }

    private static void executePurchases(BotEntry entry, Character bot, Point npcPos) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            clearShopState(entry);
            return;
        }

        WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        List<PurchaseAction> actions = new ArrayList<>();

        if (isRechargeWeaponType(wt)) {
            actions.add((sequence, shop) -> {
                int recharged = doRecharge(bot, shop);
                if (recharged > 0) {
                    sequence.bought().add("recharged " + recharged + " stack" + (recharged > 1 ? "s" : ""));
                }
                return sequence;
            });
        }
        if (needsAmmo(bot, wt)) {
            actions.add((sequence, shop) -> appendBuyReport(sequence, buyAmmo(bot, shop, wt), "ammo"));
        }
        actions.add((sequence, shop) -> {
            int[] pots = BotPotionManager.countPotions(bot);
            if (pots[0] < BotManager.cfg.POT_LOW_WARN * 5) {
                return appendBuyReport(sequence, buyPotions(bot, shop, true), "HP pots");
            }
            return sequence;
        });
        actions.add((sequence, shop) -> {
            int[] pots = BotPotionManager.countPotions(bot);
            if (pots[1] < BotManager.cfg.POT_LOW_WARN * 5) {
                return appendBuyReport(sequence, buyPotions(bot, shop, false), "MP pots");
            }
            return sequence;
        });

        runPurchaseStep(new PurchaseSequence(entry, bot, npcPos, actions, new ArrayList<>(), null), 0);
    }

    private static void runPurchaseStep(PurchaseSequence sequence, int index) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            clearShopState(sequence.entry());
            return;
        }
        if (index >= sequence.actions().size()) {
            finishPurchaseSequence(sequence);
            return;
        }

        NPC npc = findNpcNear(sequence.bot(), sequence.npcPos());
        if (npc == null) {
            clearShopState(sequence.entry());
            return;
        }
        Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
        if (shop == null) {
            clearShopState(sequence.entry());
            return;
        }

        PurchaseSequence next = sequence.actions().get(index).run(sequence, shop);
        BotManager.after(stepDelayMs(), () -> runPurchaseStep(next, index + 1));
    }

    private static void finishPurchaseSequence(PurchaseSequence sequence) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            clearShopState(sequence.entry());
            return;
        }

        Runnable finish = () -> {
            if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
                clearShopState(sequence.entry());
                return;
            }
            if (sequence.firstShortfall() != null) {
                BotManager.getInstance().botSay(sequence.bot(), buildShortfallMessage(sequence.firstShortfall()));
            }
            clearShopState(sequence.entry());
        };

        if (!sequence.bought().isEmpty()) {
            BotManager.getInstance().botSay(sequence.bot(), "bought " + String.join(", ", sequence.bought()));
            BotPotionManager.setupAutopotForBot(sequence.bot());
            BotCombatManager.tickAmmoCheck(sequence.entry(), sequence.bot());
            BotManager.after(stepDelayMs(), finish);
            return;
        }

        finish.run();
    }

    private static PurchaseSequence appendBuyReport(PurchaseSequence sequence, BuyReport report, String fallbackName) {
        if (report.quantity() > 0) {
            sequence.bought().add(report.quantity() + " " + resolveItemName(report.itemId(), fallbackName));
        }
        return sequence.withFirstShortfall(report);
    }

    private static boolean needsAmmo(Character bot, WeaponType wt) {
        if (wt == null) {
            return false;
        }
        return wt == WeaponType.BOW || wt == WeaponType.CROSSBOW;
    }

    private static boolean isRechargeWeaponType(WeaponType wt) {
        return wt == WeaponType.CLAW || wt == WeaponType.GUN;
    }

    private static ShopSlotItem findAmmoItem(Shop shop, WeaponType wt) {
        List<ShopItem> items = shop.getItems();
        ShopSlotItem best = null;
        for (int i = 0; i < items.size(); i++) {
            ShopItem si = items.get(i);
            if (si.getPrice() <= 0) {
                continue;
            }
            int id = si.getItemId();
            boolean matches = switch (wt) {
                case BOW -> ItemConstants.isArrowForBow(id);
                case CROSSBOW -> ItemConstants.isArrowForCrossBow(id);
                default -> false;
            };
            if (matches && (best == null || si.getPrice() < best.shopItem.getPrice())) {
                best = new ShopSlotItem((short) i, si);
            }
        }
        return best;
    }

    private static BuyReport buyAmmo(Character bot, Shop shop, WeaponType wt) {
        ShopSlotItem ammo = findAmmoItem(shop, wt);
        if (ammo == null) {
            return new BuyReport(0, 0, 0, false);
        }

        int target = BotCombatManager.cfg.AMMO_LOW_WARN * 5;
        int current = BotCombatManager.countAmmo(bot, wt);
        return buyFixedCostItem(bot, shop, ammo, Math.max(0, target - current), 1000);
    }

    private static boolean hasRechargeableInShop(Character bot) {
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (ItemConstants.isRechargeable(item.getItemId())) {
                short slotMax = ItemInformationProvider.getInstance().getSlotMax(bot.getClient(), item.getItemId());
                if (item.getQuantity() < slotMax) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int doRecharge(Character bot, Shop shop) {
        int recharged = 0;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (!ItemConstants.isRechargeable(item.getItemId())) {
                continue;
            }
            short slotMax = ItemInformationProvider.getInstance().getSlotMax(bot.getClient(), item.getItemId());
            if (item.getQuantity() >= slotMax) {
                continue;
            }

            Shop.TransactionResult result = shop.rechargeDirect(bot, item.getPosition());
            if (result == Shop.TransactionResult.SUCCESS) {
                recharged++;
            }
            if (result == Shop.TransactionResult.NOT_ENOUGH_MESO) {
                break;
            }
        }
        return recharged;
    }

    private static ShopSlotItem findPotionItem(Shop shop, Character bot, boolean forHp) {
        List<ShopItem> items = shop.getItems();
        int maxStat = forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp();
        int minRecover = (int) (maxStat * 0.10);
        int maxRecover = (int) (maxStat * 0.50);

        ShopSlotItem best = null;
        for (int i = 0; i < items.size(); i++) {
            ShopItem si = items.get(i);
            if (si.getPrice() <= 0) {
                continue;
            }
            int id = si.getItemId();
            if (!BotInventoryManager.isRecoveryPotion(id)) {
                continue;
            }

            StatEffect fx = BotInventoryManager.itemEffect(id);
            if (fx == null) {
                continue;
            }
            if (forHp && fx.getHpRate() > 0) {
                continue;
            }
            if (!forHp && fx.getMpRate() > 0) {
                continue;
            }

            int recover = forHp ? fx.getHp() : fx.getMp();
            if (recover <= 0 || recover < minRecover || recover > maxRecover) {
                continue;
            }

            if (best == null || si.getPrice() < best.shopItem.getPrice()) {
                best = new ShopSlotItem((short) i, si);
            }
        }
        return best;
    }

    private static BuyReport buyPotions(Character bot, Shop shop, boolean forHp) {
        ShopSlotItem pot = findPotionItem(shop, bot, forHp);
        if (pot == null) {
            return new BuyReport(0, 0, 0, false);
        }

        int target = BotManager.cfg.POT_LOW_WARN * 5;
        int[] pots = BotPotionManager.countPotions(bot);
        int current = forHp ? pots[0] : pots[1];
        return buyFixedCostItem(bot, shop, pot, Math.max(0, target - current), 100);
    }

    private static BuyReport buyFixedCostItem(Character bot, Shop shop, ShopSlotItem item, int desiredQuantity, int batchSize) {
        if (item == null || desiredQuantity <= 0) {
            return new BuyReport(0, 0, 0, false);
        }

        int totalBought = 0;
        boolean broke = false;
        int price = item.shopItem.getPrice();

        while (totalBought < desiredQuantity) {
            int remaining = desiredQuantity - totalBought;
            short qty = (short) Math.min(remaining, batchSize);
            Shop.TransactionResult result = shop.buyDirect(bot, item.slot, item.shopItem.getItemId(), qty);
            if (result == Shop.TransactionResult.SUCCESS) {
                totalBought += qty;
                continue;
            }
            if (result == Shop.TransactionResult.NOT_ENOUGH_MESO) {
                int affordable = price > 0 ? Math.min(remaining, bot.getMeso() / price) : 0;
                if (affordable > 0) {
                    Shop.TransactionResult partial = shop.buyDirect(bot, item.slot, item.shopItem.getItemId(), (short) affordable);
                    if (partial == Shop.TransactionResult.SUCCESS) {
                        totalBought += affordable;
                    }
                }
                broke = true;
            }
            break;
        }

        return new BuyReport(item.shopItem.getItemId(), totalBought, desiredQuantity, broke);
    }

    private static String buildShortfallMessage(BuyReport report) {
        String itemName = resolveItemName(report.itemId(), "item");
        if (report.quantity() <= 0) {
            return "couldn't afford any " + itemName + " this trip";
        }
        return "could only afford " + GameConstants.numberWithCommas(report.quantity())
                + " " + itemName + " out of " + GameConstants.numberWithCommas(report.requestedQuantity());
    }

    private static String resolveItemName(int itemId, String fallbackName) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name != null ? name : fallbackName;
    }

    private static boolean isShopSequenceValid(BotEntry entry, Character bot, Point npcPos) {
        return entry.shopVisitPending
                && entry.shopSequenceActive
                && npcPos != null
                && bot.getMap() != null
                && bot.getPosition().distanceSq(npcPos) <= (long) entry.shopInteractDist * entry.shopInteractDist
                && findNpcNear(bot, npcPos) != null;
    }

    private static void clearShopState(BotEntry entry) {
        entry.shopVisitPending = false;
        entry.shopNpcPos = null;
        entry.shopInteractDist = 400;
        entry.shopApproachDelayMs = 0;
        entry.shopSequenceActive = false;
    }

    private static long stepDelayMs() {
        return BotManager.randMs(SHOP_STEP_DELAY_MIN_MS, SHOP_STEP_DELAY_MAX_MS);
    }

    private static NPC findNpcNear(Character bot, Point pos) {
        for (MapObject obj : bot.getMap().getMapObjectsInRange(
                pos, SHOP_INTERACT_DIST_MAX * SHOP_INTERACT_DIST_MAX,
                Arrays.asList(MapObjectType.NPC))) {
            NPC npc = (NPC) obj;
            if (npc.hasShop()) {
                return npc;
            }
        }
        return null;
    }
}
