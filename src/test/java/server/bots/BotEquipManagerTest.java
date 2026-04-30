package server.bots;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.WeaponType;
import constants.skills.Crusader;
import constants.skills.DragonKnight;
import constants.skills.Fighter;
import constants.skills.Pirate;
import constants.skills.Rogue;
import constants.skills.Spearman;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void fighterUsesSwordOrAxeSkillsToChooseWeaponFamily() {
        Character swordFighter = mock(Character.class);
        when(swordFighter.getJob()).thenReturn(Job.FIGHTER);
        when(swordFighter.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(1);
        when(swordFighter.getSkillLevel(Fighter.SWORD_BOOSTER)).thenReturn(0);
        when(swordFighter.getSkillLevel(Fighter.AXE_MASTERY)).thenReturn(0);
        when(swordFighter.getSkillLevel(Fighter.AXE_BOOSTER)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(swordFighter, WeaponType.SWORD1H));
        assertTrue(BotEquipManager.isWeaponCompatible(swordFighter, WeaponType.SWORD2H));
        assertFalse(BotEquipManager.isWeaponCompatible(swordFighter, WeaponType.GENERAL1H_SWING));

        Character axeFighter = mock(Character.class);
        when(axeFighter.getJob()).thenReturn(Job.FIGHTER);
        when(axeFighter.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(0);
        when(axeFighter.getSkillLevel(Fighter.SWORD_BOOSTER)).thenReturn(0);
        when(axeFighter.getSkillLevel(Fighter.AXE_MASTERY)).thenReturn(1);
        when(axeFighter.getSkillLevel(Fighter.AXE_BOOSTER)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(axeFighter, WeaponType.GENERAL1H_SWING));
        assertTrue(BotEquipManager.isWeaponCompatible(axeFighter, WeaponType.GENERAL2H_SWING));
        assertFalse(BotEquipManager.isWeaponCompatible(axeFighter, WeaponType.SWORD1H));
    }

    @Test
    void spearmanUsesSpearOrPolearmSkillsToChooseWeaponFamily() {
        Character spearBot = mock(Character.class);
        when(spearBot.getJob()).thenReturn(Job.SPEARMAN);
        when(spearBot.getSkillLevel(Spearman.SPEAR_MASTERY)).thenReturn(1);
        when(spearBot.getSkillLevel(Spearman.SPEAR_BOOSTER)).thenReturn(0);
        when(spearBot.getSkillLevel(Spearman.POLEARM_MASTERY)).thenReturn(0);
        when(spearBot.getSkillLevel(Spearman.POLEARM_BOOSTER)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(spearBot, WeaponType.SPEAR_STAB));
        assertTrue(BotEquipManager.isWeaponCompatible(spearBot, WeaponType.SPEAR_SWING));
        assertFalse(BotEquipManager.isWeaponCompatible(spearBot, WeaponType.POLE_ARM_SWING));

        Character polearmBot = mock(Character.class);
        when(polearmBot.getJob()).thenReturn(Job.SPEARMAN);
        when(polearmBot.getSkillLevel(Spearman.SPEAR_MASTERY)).thenReturn(0);
        when(polearmBot.getSkillLevel(Spearman.SPEAR_BOOSTER)).thenReturn(0);
        when(polearmBot.getSkillLevel(Spearman.POLEARM_MASTERY)).thenReturn(1);
        when(polearmBot.getSkillLevel(Spearman.POLEARM_BOOSTER)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(polearmBot, WeaponType.POLE_ARM_SWING));
        assertTrue(BotEquipManager.isWeaponCompatible(polearmBot, WeaponType.POLE_ARM_STAB));
        assertFalse(BotEquipManager.isWeaponCompatible(polearmBot, WeaponType.SPEAR_STAB));
    }

    @Test
    void fourthJobWarriorsKeepTheirThirdJobWeaponFamily() {
        Character hero = mock(Character.class);
        when(hero.getJob()).thenReturn(Job.HERO);
        when(hero.getSkillLevel(Crusader.SWORD_COMA)).thenReturn(1);
        when(hero.getSkillLevel(Crusader.SWORD_PANIC)).thenReturn(0);
        when(hero.getSkillLevel(Crusader.AXE_COMA)).thenReturn(0);
        when(hero.getSkillLevel(Crusader.AXE_PANIC)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(hero, WeaponType.SWORD1H));
        assertFalse(BotEquipManager.isWeaponCompatible(hero, WeaponType.GENERAL1H_SWING));

        Character darkKnight = mock(Character.class);
        when(darkKnight.getJob()).thenReturn(Job.DARKKNIGHT);
        when(darkKnight.getSkillLevel(DragonKnight.SPEAR_CRUSHER)).thenReturn(0);
        when(darkKnight.getSkillLevel(DragonKnight.SPEAR_DRAGON_FURY)).thenReturn(0);
        when(darkKnight.getSkillLevel(DragonKnight.POLE_ARM_CRUSHER)).thenReturn(1);
        when(darkKnight.getSkillLevel(DragonKnight.POLE_ARM_DRAGON_FURY)).thenReturn(0);

        assertTrue(BotEquipManager.isWeaponCompatible(darkKnight, WeaponType.POLE_ARM_SWING));
        assertFalse(BotEquipManager.isWeaponCompatible(darkKnight, WeaponType.SPEAR_STAB));
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

    @Test
    void mageUsefulGearScoreIgnoresDexAndValuesInt() {
        Equip dexOverall = mock(Equip.class);
        when(dexOverall.getDex()).thenReturn((short) 10);
        Equip intOverall = mock(Equip.class);
        when(intOverall.getInt()).thenReturn((short) 5);

        assertTrue(BotEquipManager.usefulStatSum(intOverall, Job.MAGICIAN)
                > BotEquipManager.usefulStatSum(dexOverall, Job.MAGICIAN));
    }

    @Test
    void expectedDamageReducesToMidMinusWdefBelowMin() {
        // wdef <= rawMin: every roll is unclamped, expected = (rawMin + rawMax)/2 - wdef
        assertEquals(60.0, BotEquipManager.expectedDamageAfterDef(100, 15), 0.01);
    }

    @Test
    void expectedDamageClampsToOneWhenWdefExceedsMax() {
        assertEquals(1.0, BotEquipManager.expectedDamageAfterDef(100, 200), 0.01);
    }

    @Test
    void expectedDamagePreservesUpperTailWhenWdefExceedsMid() {
        // rawMax=100, rawMin=50, wdef=80 (between mid=75 and max=100). Old formula would have
        // returned max(1, 75-80)=1, erasing the [80,100] upper tail. Integral of clamped uniform
        // here equals 0.6 (clamped fraction) + 400/(2*50) = 0.6 + 4 = 4.6.
        assertEquals(4.6, BotEquipManager.expectedDamageAfterDef(100, 80), 0.01);
    }

    @Test
    void expectedDamageDifferentiatesCandidatesAgainstHighWdefMob() {
        // Two candidates with slightly different rawMax against a high-WDEF mob: old formula
        // collapsed both to 1; new formula keeps a meaningful gap.
        double weaker = BotEquipManager.expectedDamageAfterDef(100, 80);
        double stronger = BotEquipManager.expectedDamageAfterDef(120, 80);
        assertTrue(stronger > weaker + 0.5,
                "expected stronger > weaker by margin; got " + stronger + " vs " + weaker);
    }
}
