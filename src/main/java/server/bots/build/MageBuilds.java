package server.bots.build;

import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Bishop;
import constants.skills.Cleric;
import constants.skills.Magician;
import constants.skills.Priest;
import java.util.List;

public final class MageBuilds {

    private MageBuilds() {
    }

    public static List<BuildStep> getBuildOrder(Job job) {
        return switch (job) {
            case MAGICIAN -> magicianBuild();
            case CLERIC -> clericBuild();
            case PRIEST -> priestBuild();
            case BISHOP -> bishopBuild();
            default -> null;
        };
    }

    private static BuildStep s(int id, int to) {
        return new BuildStep(id, to);
    }

    private static int max(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        return skill != null ? skill.getMaxLevel() : 30;
    }

    private static List<BuildStep> magicianBuild() {
        return List.of(
                s(Magician.ENERGY_BOLT, 1),
                s(Magician.IMPROVED_MP_RECOVERY, 5),
                s(Magician.IMPROVED_MAX_MP_INCREASE, 10),
                s(Magician.IMPROVED_MP_RECOVERY, 16),
                s(Magician.MAGIC_CLAW, 20),
                s(Magician.MAGIC_GUARD, 20)
        );
    }

    private static List<BuildStep> clericBuild() {
        return List.of(
                s(Cleric.HEAL, 30),
                s(Cleric.INVINCIBLE, 5),
                s(Cleric.BLESS, 20),
                s(Cleric.TELEPORT, 20),
                s(Cleric.MP_EATER, 20),
                s(Cleric.INVINCIBLE, 20),
                s(Cleric.HOLY_ARROW, 11)
        );
    }

    private static List<BuildStep> priestBuild() {
        return List.of(
                s(Priest.SHINING_RAY, 1),
                s(Priest.DISPEL, 3),
                s(Priest.ELEMENTAL_RESISTANCE, 1),
                s(Priest.MYSTIC_DOOR, 1),
                s(Priest.HOLY_SYMBOL, max(Priest.HOLY_SYMBOL)),
                s(Priest.SHINING_RAY, max(Priest.SHINING_RAY)),
                s(Priest.ELEMENTAL_RESISTANCE, max(Priest.ELEMENTAL_RESISTANCE)),
                s(Priest.SUMMON_DRAGON, max(Priest.SUMMON_DRAGON)),
                s(Priest.DISPEL, max(Priest.DISPEL)),
                s(Priest.MYSTIC_DOOR, max(Priest.MYSTIC_DOOR)),
                s(Priest.DOOM, 1)
        );
    }

    private static List<BuildStep> bishopBuild() {
        return List.of(
                s(Bishop.GENESIS, 10),
                s(Bishop.MAPLE_WARRIOR, 9),
                s(Bishop.RESURRECTION, max(Bishop.RESURRECTION)),
                s(Bishop.ANGEL_RAY, max(Bishop.ANGEL_RAY)),
                s(Bishop.BAHAMUT, max(Bishop.BAHAMUT)),
                s(Bishop.GENESIS, max(Bishop.GENESIS)),
                s(Bishop.MAPLE_WARRIOR, max(Bishop.MAPLE_WARRIOR)),
                s(Bishop.BIG_BANG, max(Bishop.BIG_BANG)),
                s(Bishop.HOLY_SHIELD, max(Bishop.HOLY_SHIELD)),
                s(Bishop.INFINITY, max(Bishop.INFINITY)),
                s(Bishop.MANA_REFLECTION, max(Bishop.MANA_REFLECTION)),
                s(Bishop.HEROS_WILL, max(Bishop.HEROS_WILL))
        );
    }
}
