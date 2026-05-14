package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotInventoryManagerTest {
    @Test
    void shouldOnlyAnnounceTradeInviteOnFirstBatchOfSequence() throws Exception {
        BotEntry entry = new BotEntry(mock(Character.class), mock(Character.class), null);
        Character bot = entry.bot;
        Character owner = entry.owner;
        BotManager manager = spy(BotManager.getInstance());
        Method startTradeSequence = method(BotInventoryManager.class,
                "startTradeSequence",
                String.class, Character.class, List.class, int.class, boolean.class, BotEntry.class, Character.class);
        Method openTradeBatch = method(BotInventoryManager.class,
                "openTradeBatch",
                BotEntry.class, Character.class, List.class, int.class);

        when(owner.getId()).thenReturn(42);
        when(owner.getTrade()).thenReturn(null);
        when(bot.getTrade()).thenReturn(null);
        doAnswer(invocation -> null).when(manager).botReply(eq(entry), anyString());

        try (MockedStatic<BotManager> botManagers = mockStatic(BotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            botManagers.when(BotManager::getInstance).thenReturn(manager);

            startTradeSequence.invoke(null, "trash", owner, List.of(mock(Item.class)), 0, false, entry, bot);
            openTradeBatch.invoke(null, entry, bot, List.of(mock(Item.class)), 0);

            verify(manager, times(1)).botReply(eq(entry), anyString());
        }
    }

    @Test
    void shouldExposeExpandedTradeMessagePools() throws Exception {
        List<String> invitationMsgs = listField("TRADE_INVITATION_MSGS");
        List<String> freebieMsgs = listField("TRADE_FREEBIE_QUIPS");

        assertTrue(invitationMsgs.size() >= 20);
        assertTrue(invitationMsgs.contains("ready when u are"));
        assertTrue(invitationMsgs.contains("trade time"));

        assertTrue(freebieMsgs.size() >= 16);
        assertTrue(freebieMsgs.contains("enjoy"));
        assertTrue(freebieMsgs.contains(":)"));
        assertTrue(freebieMsgs.contains("hope that helps"));
    }

    @Test
    void shouldCancelUnmanagedBotTradeWhenManualTimeoutExpires() {
        BotEntry entry = new BotEntry(mock(Character.class), null, null);
        Character bot = entry.bot;
        Trade trade = mock(Trade.class);

        when(bot.getId()).thenReturn(99);
        when(bot.getTrade()).thenReturn(trade);

        BotInventoryManager.tickManualTrade(entry, bot);
        entry.manualTradeTimeoutMs = BotMovementManager.cfg.TICK_MS;

        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            BotInventoryManager.tickManualTrade(entry, bot);

            trades.verify(() -> Trade.cancelTrade(bot, Trade.TradeResult.NO_RESPONSE));
            assertNull(entry.manualTradeRef);
            assertTrue(entry.manualTradeTimeoutMs == 0);
        }
    }

    @Test
    void shouldFilterProtectedEquipsOutOfSellTrashOnly() {
        Equip sellable = mock(Equip.class);
        Equip highIntAllJob = mock(Equip.class);
        Equip highDexWarrior = mock(Equip.class);
        Equip nonWeaponWatk = mock(Equip.class);
        Equip scrolled = mock(Equip.class);
        Equip pirateDex = mock(Equip.class);
        Equip currentWarriorWeapon = mock(Equip.class);
        Equip baseWarriorWeapon = mock(Equip.class);
        Equip currentMageWeapon = mock(Equip.class);
        Equip baseMageWeapon = mock(Equip.class);

        when(sellable.getItemId()).thenReturn(1000001);
        when(highIntAllJob.getItemId()).thenReturn(1082001);
        when(highDexWarrior.getItemId()).thenReturn(1000002);
        when(nonWeaponWatk.getItemId()).thenReturn(1072001);
        when(scrolled.getItemId()).thenReturn(1040001);
        when(pirateDex.getItemId()).thenReturn(1050001);
        when(currentWarriorWeapon.getItemId()).thenReturn(1302000);
        when(currentMageWeapon.getItemId()).thenReturn(1372000);

        when(sellable.getStr()).thenReturn((short) 3);
        when(highIntAllJob.getInt()).thenReturn((short) 6);
        when(highDexWarrior.getDex()).thenReturn((short) 6);
        when(nonWeaponWatk.getWatk()).thenReturn((short) 1);
        when(scrolled.getLevel()).thenReturn((byte) 1);
        when(pirateDex.getDex()).thenReturn((short) 6);
        when(currentWarriorWeapon.getWatk()).thenReturn((short) 24);
        when(baseWarriorWeapon.getWatk()).thenReturn((short) 20);
        when(currentMageWeapon.getMatk()).thenReturn((short) 29);
        when(baseMageWeapon.getMatk()).thenReturn((short) 25);

        assertTrue(!BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 1), sellable, 6));
        assertTrue(BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 0), highIntAllJob, 6));
        assertTrue(BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 1), highDexWarrior, 6));
        assertTrue(BotInventoryManager.shouldKeepForSellTrash(null, nonWeaponWatk));
        assertTrue(BotInventoryManager.shouldKeepForSellTrash(null, scrolled));
        assertTrue(BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 16), pirateDex, 6));
        assertTrue(BotInventoryManager.hasProtectedSellTrashWeaponStat(Map.of("reqJob", 1), currentWarriorWeapon, baseWarriorWeapon));
        assertTrue(BotInventoryManager.hasProtectedSellTrashWeaponStat(Map.of("reqJob", 2), currentMageWeapon, baseMageWeapon));
    }

    @SuppressWarnings("unchecked")
    private static List<String> listField(String name) throws Exception {
        return (List<String>) field(BotInventoryManager.class, name).get(null);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }
}
