package server.bots.build;

import client.Job;
import constants.skills.Crusader;
import constants.skills.DragonKnight;
import constants.skills.Fighter;
import constants.skills.Hero;
import constants.skills.Page;
import constants.skills.Spearman;
import constants.skills.Warrior;
import constants.skills.WhiteKnight;
import java.util.List;

public final class WarriorBuilds {

    private WarriorBuilds() {
    }

    public static List<BuildStep> getBuildOrder(Job job, String variant) {
        return switch (job) {
            case WARRIOR -> warriorBuild();
            case FIGHTER -> fighterBuild();
            case CRUSADER -> crusaderBuild();
            case HERO -> "2h".equals(variant) ? hero2hBuild() : hero1hBuild();
            case PAGE -> pageBuild();
            case WHITEKNIGHT -> whiteKnightBuild();
            case SPEARMAN -> spearmanBuild();
            case DRAGONKNIGHT -> dragonKnightBuild();
            default -> null;
        };
    }

    private static BuildStep s(int id, int to) {
        return new BuildStep(id, to);
    }

    private static List<BuildStep> warriorBuild() {
        return List.of(
                s(Warrior.IMPROVED_HPREC, 5),
                s(Warrior.IMPROVED_MAXHP, 10),
                s(Warrior.POWER_STRIKE, 1),
                s(Warrior.SLASH_BLAST, 20),
                s(Warrior.POWER_STRIKE, 20),
                s(Warrior.IMPROVED_HPREC, 16)
        );
    }

    private static List<BuildStep> fighterBuild() {
        return List.of(
                s(Fighter.SWORD_MASTERY, 19),
                s(Fighter.SWORD_BOOSTER, 6),
                s(Fighter.RAGE, 3),
                s(Fighter.POWER_GUARD, 30),
                s(Fighter.RAGE, 30),
                s(Fighter.SWORD_BOOSTER, 20),
                s(Fighter.SWORD_MASTERY, 20)
        );
    }

    private static List<BuildStep> crusaderBuild() {
        return List.of(
                s(Crusader.COMBO, 30),
                s(Crusader.SWORD_COMA, 30),
                s(Crusader.SWORD_PANIC, 30),
                s(Crusader.ARMOR_CRASH, 20),
                s(Crusader.SHOUT, 20),
                s(Crusader.SHIELD_MASTERY, 20),
                s(Crusader.IMPROVING_MPREC, 20)
        );
    }

    private static List<BuildStep> hero1hBuild() {
        return List.of(
                s(Hero.RUSH, 1),
                s(Hero.BRANDISH, 30),
                s(Hero.ADVANCED_COMBO, 30),
                s(Hero.STANCE, 30),
                s(Hero.MAPLE_WARRIOR, 13),
                s(Hero.HEROS_WILL, 1),
                s(Hero.MAPLE_WARRIOR, 19),
                s(Hero.ACHILLES, 30),
                s(Hero.HEROS_WILL, 5),
                s(Hero.GUARDIAN, 30),
                s(Hero.ENRAGE, 30),
                s(Hero.RUSH, 30),
                s(Hero.MAPLE_WARRIOR, 30)
        );
    }

    private static List<BuildStep> hero2hBuild() {
        return List.of(
                s(Hero.RUSH, 1),
                s(Hero.BRANDISH, 1),
                s(Hero.ADVANCED_COMBO, 1),
                s(Hero.BRANDISH, 21),
                s(Hero.ADVANCED_COMBO, 30),
                s(Hero.BRANDISH, 30),
                s(Hero.STANCE, 30),
                s(Hero.MAPLE_WARRIOR, 13),
                s(Hero.HEROS_WILL, 1),
                s(Hero.MAPLE_WARRIOR, 19),
                s(Hero.ACHILLES, 30),
                s(Hero.HEROS_WILL, 5),
                s(Hero.GUARDIAN, 30),
                s(Hero.ENRAGE, 30),
                s(Hero.RUSH, 30),
                s(Hero.MAPLE_WARRIOR, 30)
        );
    }

    private static List<BuildStep> pageBuild() {
        return List.of(
                s(Page.SWORD_MASTERY, 20),
                s(Page.THREATEN, 20),
                s(Page.POWER_GUARD, 30),
                s(Page.SWORD_BOOSTER, 20)
        );
    }

    private static List<BuildStep> whiteKnightBuild() {
        return List.of(
                s(WhiteKnight.CHARGE_BLOW, 30),
                s(WhiteKnight.SWORD_LIT_CHARGE, 30),
                s(WhiteKnight.MAGIC_CRASH, 20),
                s(WhiteKnight.SHIELD_MASTERY, 20),
                s(WhiteKnight.IMPROVING_MP_RECOVERY, 20)
        );
    }

    private static List<BuildStep> spearmanBuild() {
        return List.of(
                s(Spearman.HYPER_BODY, 30),
                s(Spearman.SPEAR_MASTERY, 20),
                s(Spearman.IRON_WILL, 20),
                s(Spearman.SPEAR_BOOSTER, 20)
        );
    }

    private static List<BuildStep> dragonKnightBuild() {
        return List.of(
                s(DragonKnight.DRAGON_ROAR, 30),
                s(DragonKnight.DRAGON_BLOOD, 30),
                s(DragonKnight.SPEAR_CRUSHER, 30),
                s(DragonKnight.SPEAR_DRAGON_FURY, 30),
                s(DragonKnight.SACRIFICE, 20),
                s(DragonKnight.POWER_CRASH, 20),
                s(DragonKnight.ELEMENTAL_RESISTANCE, 20)
        );
    }
}
