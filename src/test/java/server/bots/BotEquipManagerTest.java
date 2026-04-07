package server.bots;

import client.Character;
import client.Job;
import client.inventory.WeaponType;
import constants.skills.Pirate;
import constants.skills.Rogue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotEquipManagerTest {

    @Test
    void firstJobBowmanAcceptsBowAndCrossbowOnly() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BOWMAN);

        assertTrue(BotEquipManager.isWeaponCompatible(bot, WeaponType.BOW));
        assertTrue(BotEquipManager.isWeaponCompatible(bot, WeaponType.CROSSBOW));
        assertFalse(BotEquipManager.isWeaponCompatible(bot, WeaponType.CLAW));
    }

    @Test
    void hunterOnlyAcceptsBows() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.HUNTER);

        assertTrue(BotEquipManager.isWeaponCompatible(bot, WeaponType.BOW));
        assertFalse(BotEquipManager.isWeaponCompatible(bot, WeaponType.CROSSBOW));
    }

    @Test
    void firstJobPirateUsesSkillsToPickGunOrKnuckle() {
        Character gunPirate = mock(Character.class);
        when(gunPirate.getJob()).thenReturn(Job.PIRATE);
        when(gunPirate.getSkillLevel(Pirate.DOUBLE_SHOT)).thenReturn(1);
        when(gunPirate.getSkillLevel(Pirate.FLASH_FIST)).thenReturn(0);
        when(gunPirate.getSkillLevel(Pirate.SOMERSAULT_KICK)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(gunPirate, WeaponType.GUN));
        assertFalse(BotEquipManager.isWeaponCompatible(gunPirate, WeaponType.KNUCKLE));

        Character knucklePirate = mock(Character.class);
        when(knucklePirate.getJob()).thenReturn(Job.PIRATE);
        when(knucklePirate.getSkillLevel(Pirate.DOUBLE_SHOT)).thenReturn(0);
        when(knucklePirate.getSkillLevel(Pirate.FLASH_FIST)).thenReturn(1);
        when(knucklePirate.getSkillLevel(Pirate.SOMERSAULT_KICK)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(knucklePirate, WeaponType.KNUCKLE));
        assertFalse(BotEquipManager.isWeaponCompatible(knucklePirate, WeaponType.GUN));
    }

    @Test
    void luckySevenThiefPrefersClaws() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.THIEF);
        when(bot.getSkillLevel(Rogue.LUCKY_SEVEN)).thenReturn(1);
        when(bot.getSkillLevel(Rogue.DOUBLE_STAB)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(bot, WeaponType.CLAW));
        assertFalse(BotEquipManager.isWeaponCompatible(bot, WeaponType.DAGGER_OTHER));
    }
}
