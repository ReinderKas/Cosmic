package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.game.GameConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import server.bots.build.BowmanBuilds;
import server.bots.build.BuildStep;
import server.bots.build.MageBuilds;
import server.bots.build.ThiefBuilds;
import server.bots.build.WarriorBuilds;

class BotBuildManager {

    /**
     * AP build for a warrior bot.
     * dexTarget = 0 means pure STR.
     * dexTarget > 0 means fill DEX to that value first, then all STR.
     */
    public static class ApBuild {
        final int dexTarget;

        public ApBuild(int dexTarget) {
            this.dexTarget = dexTarget;
        }
    }

    /** Stores the AP build, confirms it to the owner, and immediately spends any pending AP. */
    static void setApBuild(BotEntry entry, ApBuild build, String confirmMsg) {
        entry.apBuild = build;
        entry.apPromptSent = false;
        BotManager.getInstance().botSay(entry.bot, confirmMsg);
        autoAssignAp(entry, entry.bot);
    }

    /**
     * Returns a prompt asking the owner to choose an AP build, or null if:
     * no AP is pending, a build is already chosen, a prompt was already sent,
     * or the bot is not on a supported warrior branch.
     */
    static String buildApPrompt(BotEntry entry, Character bot) {
        Job job = bot.getJob();
        if (job != Job.WARRIOR && job != Job.FIGHTER && job != Job.PAGE && job != Job.SPEARMAN) return null;
        if (entry.apBuild != null || entry.apPromptSent || bot.getRemainingAp() < 1) return null;
        entry.apPromptSent = true;
        return "what AP build? type 'pure str' or e.g. '25 dex' to set a dex target";
    }

    /** Spends all remaining AP according to the stored build. */
    static void autoAssignAp(BotEntry entry, Character bot) {
        if (entry.apBuild == null || bot.getRemainingAp() < 1) return;

        int ap = bot.getRemainingAp();
        int strGain = 0;
        int dexGain = 0;
        if (entry.apBuild.dexTarget > 0) {
            int dexNeeded = Math.max(0, entry.apBuild.dexTarget - bot.getDex());
            dexGain = Math.min(dexNeeded, ap);
            ap -= dexGain;
        }
        strGain = ap;

        if (strGain > 0 || dexGain > 0) {
            bot.assignStrDexIntLuk(strGain, dexGain, 0, 0);
        }
    }

    /**
     * Returns a prompt asking for the SP build variant, or null if not needed.
     * Currently only Hero has two documented builds.
     */
    static String buildSpVariantPrompt(BotEntry entry, Character bot) {
        if (bot.getJob() != Job.HERO) return null;
        if (entry.spVariant != null || entry.spVariantPromptSent || bot.getRemainingSps()[3] < 1) return null;
        entry.spVariantPromptSent = true;
        return "hero build: '1h' (1h sword, Brandish first) or '2h' (interleave AC + Brandish for faster charges)?";
    }

    /**
     * Spends all available SP following the configured build order.
     * Hero SP is held until the owner chooses a variant.
     */
    static void autoAssignSp(BotEntry entry, Character bot) {
        if (bot.getJob() == Job.HERO && entry.spVariant == null) return;

        List<BuildStep> steps = getBuildOrder(bot.getJob(), entry.spVariant);
        if (steps == null) return;

        autoAssignSp(bot, steps);
    }

    static String respecSp(BotEntry entry, Character bot) {
        if (bot.getJob() == Job.HERO && entry.spVariant == null) {
            return "need your hero build first. say '1h' or '2h'";
        }

        List<Job> buildPath = getSupportedBuildPath(bot.getJob());
        if (buildPath == null) {
            return "dont have an sp respec build for my job yet";
        }

        int[] refundedSp = new int[5];
        List<Skill> skillsToReset = new ArrayList<>();
        for (Map.Entry<Skill, Character.SkillEntry> learned : bot.getSkills().entrySet()) {
            Skill skill = learned.getKey();
            Character.SkillEntry skillEntry = learned.getValue();
            if (skill == null || skillEntry == null || skillEntry.skillevel <= 0) {
                continue;
            }

            int skillId = skill.getId();
            if (skill.isBeginnerSkill() || GameConstants.isHiddenSkills(skillId)) {
                continue;
            }
            if (!GameConstants.isInJobTree(skillId, bot.getJob().getId())) {
                continue;
            }

            refundedSp[GameConstants.getSkillBook(skillId / 10000)] += skillEntry.skillevel;
            skillsToReset.add(skill);
        }

        for (Skill skill : skillsToReset) {
            bot.changeSkillLevel(skill, (byte) 0, bot.getMasterLevel(skill), bot.getSkillExpiration(skill));
        }
        for (int book = 0; book < refundedSp.length; book++) {
            if (refundedSp[book] > 0) {
                bot.gainSp(refundedSp[book], book, false);
            }
        }

        for (Job job : buildPath) {
            List<BuildStep> steps = getBuildOrder(job, entry.spVariant);
            if (steps != null) {
                autoAssignSp(bot, steps);
            }
        }

        return "ok, rebuilt my sp using the bot build";
    }

    private static void autoAssignSp(Character bot, List<BuildStep> steps) {
        for (BuildStep step : steps) {
            Skill skill = SkillFactory.getSkill(step.skillId());
            if (skill == null) continue;

            int book = GameConstants.getSkillBook(step.skillId() / 10000);
            if (bot.getRemainingSps()[book] < 1) continue;

            while (bot.getRemainingSps()[book] > 0) {
                int currentLevel = bot.getSkillLevel(skill);
                if (currentLevel >= step.targetLevel()) break;
                if (!canLevelSkill(bot, skill, currentLevel)) return;

                bot.gainSp(-1, book, false);
                bot.changeSkillLevel(
                        skill,
                        (byte) (currentLevel + 1),
                        bot.getMasterLevel(skill),
                        bot.getSkillExpiration(skill)
                );
            }
        }
    }

    private static boolean canLevelSkill(Character bot, Skill skill, int currentLevel) {
        int cap = skill.isFourthJob() ? bot.getMasterLevel(skill) : skill.getMaxLevel();
        return currentLevel < cap;
    }

    private static List<Job> getSupportedBuildPath(Job job) {
        return switch (job) {
            case WARRIOR -> List.of(Job.WARRIOR);
            case FIGHTER -> List.of(Job.WARRIOR, Job.FIGHTER);
            case CRUSADER -> List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER);
            case HERO -> List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER, Job.HERO);
            case BOWMAN -> List.of(Job.BOWMAN);
            case HUNTER -> List.of(Job.BOWMAN, Job.HUNTER);
            case RANGER -> List.of(Job.BOWMAN, Job.HUNTER, Job.RANGER);
            case BOWMASTER -> List.of(Job.BOWMAN, Job.HUNTER, Job.RANGER, Job.BOWMASTER);
            case THIEF -> List.of(Job.THIEF);
            case ASSASSIN -> List.of(Job.THIEF, Job.ASSASSIN);
            case HERMIT -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT);
            case NIGHTLORD -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT, Job.NIGHTLORD);
            case PAGE -> List.of(Job.WARRIOR, Job.PAGE);
            case WHITEKNIGHT -> List.of(Job.WARRIOR, Job.PAGE, Job.WHITEKNIGHT);
            case SPEARMAN -> List.of(Job.WARRIOR, Job.SPEARMAN);
            case DRAGONKNIGHT -> List.of(Job.WARRIOR, Job.SPEARMAN, Job.DRAGONKNIGHT);
            case MAGICIAN -> List.of(Job.MAGICIAN);
            case CLERIC -> List.of(Job.MAGICIAN, Job.CLERIC);
            case PRIEST -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST);
            case BISHOP -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST, Job.BISHOP);
            default -> null;
        };
    }

    private static List<BuildStep> getBuildOrder(Job job, String variant) {
        List<BuildStep> warriorBuild = WarriorBuilds.getBuildOrder(job, variant);
        if (warriorBuild != null) {
            return warriorBuild;
        }
        List<BuildStep> bowmanBuild = BowmanBuilds.getBuildOrder(job);
        if (bowmanBuild != null) {
            return bowmanBuild;
        }
        List<BuildStep> thiefBuild = ThiefBuilds.getBuildOrder(job);
        if (thiefBuild != null) {
            return thiefBuild;
        }
        return MageBuilds.getBuildOrder(job);
    }

    /**
     * Detects level-up and sends prompts before spending SP/AP so gating can apply.
     */
    static void checkLevelUp(BotEntry entry, Character bot) {
        int lvl = bot.getLevel();
        if (entry.lastKnownLevel == lvl) return;

        int prev = entry.lastKnownLevel;
        entry.lastKnownLevel = lvl;
        if (prev == -1) {
            autoAssignSp(entry, bot);
            autoAssignAp(entry, bot);
            return;
        }

        if (lvl == 8 || lvl == 10 || lvl == 30 || lvl == 70 || lvl == 120) {
            entry.grinding = false;
            entry.following = true;
            BotChatManager.checkBotStatus(entry, bot);
        }

        autoAssignSp(entry, bot);
        autoAssignAp(entry, bot);
    }

    /** Returns the next job-advancement prompt, or null if none is pending. */
    static String buildJobPrompt(BotEntry entry, Character bot) {
        int lvl = bot.getLevel();
        Job job = bot.getJob();
        int prompted = entry.jobPromptSent;

        if (job == Job.BEGINNER) {
            if (lvl >= 10 && prompted < 10) {
                entry.jobPromptSent = 10;
                return "hey i can change jobs now!! warrior, mage, bowman, thief, or pirate?";
            } else if (lvl >= 8 && prompted < 8) {
                entry.jobPromptSent = 8;
                return "i can become a mage already if u want, or wait til lv10 for other jobs";
            }
            return null;
        }

        if (lvl >= 30 && prompted < 30) {
            String msg = switch (job) {
                case WARRIOR -> "lv30! 2nd job time~ fighter, page, or spearman?";
                case MAGICIAN -> "lv30! pick 2nd job: f/p wizard, i/l wizard, or cleric?";
                case BOWMAN -> "lv30! hunter or crossbowman?";
                case THIEF -> "lv30! assassin or bandit?";
                case PIRATE -> "lv30! brawler or gunslinger?";
                default -> null;
            };
            if (msg != null) {
                entry.jobPromptSent = 30;
                return msg;
            }
        }

        if (lvl >= 70 && prompted < 70) {
            String msg = switch (job) {
                case FIGHTER -> "lv70!! 3rd job, type 'crusader'";
                case PAGE -> "lv70!! type 'white knight' or 'wk'";
                case SPEARMAN -> "lv70!! type 'dragon knight' or 'dk'";
                case FP_WIZARD -> "lv70!! type 'fp mage'";
                case IL_WIZARD -> "lv70!! type 'il mage'";
                case CLERIC -> "lv70!! type 'priest'";
                case HUNTER -> "lv70!! type 'ranger'";
                case CROSSBOWMAN -> "lv70!! type 'sniper'";
                case ASSASSIN -> "lv70!! type 'hermit'";
                case BANDIT -> "lv70!! type 'chief bandit' or 'cb'";
                case BRAWLER -> "lv70!! type 'marauder'";
                case GUNSLINGER -> "lv70!! type 'outlaw'";
                default -> null;
            };
            if (msg != null) {
                entry.jobPromptSent = 70;
                return msg;
            }
        }

        if (lvl >= 120 && prompted < 120) {
            String msg = switch (job) {
                case CRUSADER -> "lv120!! type 'hero' for 4th job!!";
                case WHITEKNIGHT -> "lv120!! type 'paladin'";
                case DRAGONKNIGHT -> "lv120!! type 'dark knight' or 'drk'";
                case FP_MAGE -> "lv120!! type 'fp archmage' or 'fp arch'";
                case IL_MAGE -> "lv120!! type 'il archmage' or 'il arch'";
                case PRIEST -> "lv120!! type 'bishop'";
                case RANGER -> "lv120!! type 'bowmaster' or 'bm'";
                case SNIPER -> "lv120!! type 'marksman' or 'mm'";
                case HERMIT -> "lv120!! type 'night lord' or 'nl'";
                case CHIEFBANDIT -> "lv120!! type 'shadower'";
                case MARAUDER -> "lv120!! type 'buccaneer' or 'bucc'";
                case OUTLAW -> "lv120!! type 'corsair'";
                default -> null;
            };
            if (msg != null) {
                entry.jobPromptSent = 120;
                return msg;
            }
        }

        return null;
    }
}
