package server.bots;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotManagerTest {
    @Test
    void shouldParseTransferBotCommands() {
        BotManager.BotTransferCommand command = BotManager.matchBotTransferCommand("transfer Jason to Bob");

        assertNotNull(command);
        assertEquals("Jason", command.botName());
        assertEquals("Bob", command.targetName());
    }

    @Test
    void shouldStillAllowTransferWithoutTo() {
        BotManager.BotTransferCommand command = BotManager.matchBotTransferCommand("transfer Jason Bob");

        assertNotNull(command);
        assertEquals("Jason", command.botName());
        assertEquals("Bob", command.targetName());
    }

    @Test
    void shouldNotTreatGivePhrasesAsBotTransfers() {
        assertNull(BotManager.matchBotTransferCommand("give Jason Bob"));
        assertNull(BotManager.matchBotTransferCommand("give me flaming feather"));
        assertNull(BotManager.matchBotTransferCommand("give flaming feather"));
    }

    @Test
    void shouldCountHpMpAndDualRecoveryItemsAsPotions() {
        Item hpItem = mock(Item.class);
        Item mpItem = mock(Item.class);
        Item dualItem = mock(Item.class);
        Item nonPotion = mock(Item.class);

        when(hpItem.getItemId()).thenReturn(2000002);
        when(hpItem.getQuantity()).thenReturn((short) 10);
        when(mpItem.getItemId()).thenReturn(2000003);
        when(mpItem.getQuantity()).thenReturn((short) 7);
        when(dualItem.getItemId()).thenReturn(2000004);
        when(dualItem.getQuantity()).thenReturn((short) 4);
        when(nonPotion.getItemId()).thenReturn(2040002);
        when(nonPotion.getQuantity()).thenReturn((short) 99);

        StatEffect hpEffect = mock(StatEffect.class);
        StatEffect mpEffect = mock(StatEffect.class);
        StatEffect dualEffect = mock(StatEffect.class);
        StatEffect nonPotionEffect = mock(StatEffect.class);

        when(hpEffect.getHp()).thenReturn((short) 300);
        when(hpEffect.getHpRate()).thenReturn(0d);
        when(hpEffect.getMp()).thenReturn((short) 0);
        when(hpEffect.getMpRate()).thenReturn(0d);

        when(mpEffect.getHp()).thenReturn((short) 0);
        when(mpEffect.getHpRate()).thenReturn(0d);
        when(mpEffect.getMp()).thenReturn((short) 100);
        when(mpEffect.getMpRate()).thenReturn(0d);

        when(dualEffect.getHp()).thenReturn((short) 0);
        when(dualEffect.getHpRate()).thenReturn(50d);
        when(dualEffect.getMp()).thenReturn((short) 0);
        when(dualEffect.getMpRate()).thenReturn(50d);

        when(nonPotionEffect.getHp()).thenReturn((short) 0);
        when(nonPotionEffect.getHpRate()).thenReturn(0d);
        when(nonPotionEffect.getMp()).thenReturn((short) 0);
        when(nonPotionEffect.getMpRate()).thenReturn(0d);

        java.util.Map<Integer, StatEffect> effects = java.util.Map.of(
                2000002, hpEffect,
                2000003, mpEffect,
                2000004, dualEffect,
                2040002, nonPotionEffect);

        int[] counts = BotPotionManager.countPotions(
                java.util.List.of(hpItem, mpItem, dualItem, nonPotion),
                effects::get);

        assertEquals(14, counts[0]);
        assertEquals(11, counts[1]);
    }

    @Test
    void shouldUseCombatRetreatTargetOnlyWithinSameGroundRegion() {
        MapleMap map = createEmptyTestMap(910000020);
        FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        BotEntry entry = new BotEntry(bot, null, null);

        assertTrue(BotManager.shouldUseLocalCombatRetreatTarget(
                entry,
                new Point(100, 100),
                new Point(130, 100),
                new Point(60, 100)));
    }

    @Test
    void shouldRejectCombatRetreatTargetWhenMonsterIsInDifferentRegion() {
        MapleMap map = createEmptyTestMap(910000021);
        FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        footholds.insert(new Foothold(new Point(0, 40), new Point(200, 40), 2));
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        BotEntry entry = new BotEntry(bot, null, null);

        assertFalse(BotManager.shouldUseLocalCombatRetreatTarget(
                entry,
                new Point(100, 100),
                new Point(100, 40),
                new Point(60, 100)));
    }

    @Test
    void shouldRejectCombatRetreatTargetWhileClimbing() {
        MapleMap map = createEmptyTestMap(910000022);
        FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        footholds.insert(new Foothold(new Point(0, 40), new Point(200, 40), 2));
        map.addRope(new Rope(100, 40, 100, false));
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(100, 40, 100, false);

        assertFalse(BotManager.shouldUseLocalCombatRetreatTarget(
                entry,
                new Point(100, 100),
                new Point(140, 40),
                new Point(60, 100)));
    }

    @Test
    void shouldKeepTenMinutePotShareBackoffSeparateForHpAndMp() throws Exception {
        BotManager manager = BotManager.getInstance();
        MapleMap map = mock(MapleMap.class);
        Character owner = mock(Character.class);
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, owner, null);

        when(owner.getId()).thenReturn(77);
        when(owner.getName()).thenReturn("Owner");
        when(bot.getId()).thenReturn(88);
        when(bot.getTrade()).thenReturn(null);
        when(bot.getMap()).thenReturn(map);

        @SuppressWarnings("unchecked")
        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> sharedCooldown = (Map<Integer, Long>) field(BotPotionManager.class, "potShareCooldownUntil").get(null);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> hpBackoff = (Map<Integer, Long>) field(BotPotionManager.class, "potShareHpBackoffUntil").get(null);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> mpBackoff = (Map<Integer, Long>) field(BotPotionManager.class, "potShareMpBackoffUntil").get(null);

        bots.put(owner.getId(), List.of(entry));
        sharedCooldown.remove(owner.getId());
        hpBackoff.remove(owner.getId());
        mpBackoff.remove(owner.getId());

        Method requestPotShare = BotPotionManager.class.getDeclaredMethod("requestPotShare", BotEntry.class, Character.class, boolean.class);
        requestPotShare.setAccessible(true);
        try {
            assertTrue((Boolean) requestPotShare.invoke(null, entry, bot, false),
                    "first MP request should broadcast and install MP-only long backoff when no donor exists");
            assertTrue(mpBackoff.get(owner.getId()) > System.currentTimeMillis());
            assertFalse(hpBackoff.containsKey(owner.getId()));

            sharedCooldown.put(owner.getId(), 0L);

            assertTrue((Boolean) requestPotShare.invoke(null, entry, bot, true),
                    "HP request should still be allowed after shared 30 s cooldown even if MP is under 10 min backoff");
            assertTrue(hpBackoff.get(owner.getId()) > System.currentTimeMillis());

            sharedCooldown.put(owner.getId(), 0L);
            assertFalse((Boolean) requestPotShare.invoke(null, entry, bot, false),
                    "MP request should remain blocked by its own 10 min backoff");
        } finally {
            bots.remove(owner.getId());
            sharedCooldown.remove(owner.getId());
            hpBackoff.remove(owner.getId());
            mpBackoff.remove(owner.getId());
        }
    }

    private static MapleMap createEmptyTestMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        map.setFootholds(new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000)));
        return map;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
