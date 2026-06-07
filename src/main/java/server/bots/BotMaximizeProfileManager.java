package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.Stat;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.processor.stat.AssignAPProcessor;
import com.esotericsoftware.yamlbeans.YamlReader;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.inventory.EquipSlot;
import constants.inventory.ItemConstants;
import constants.string.CharsetConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Level-based profile overrides for bot AP/SP/equipment decisions.
 *
 * <p>When maximize profile mode is enabled on a bot entry, BotBuildManager routes
 * level-up stat/skill allocation through this file and skips normal AP/SP builds.
 */
final class BotMaximizeProfileManager {
    private static final Logger log = LoggerFactory.getLogger(BotMaximizeProfileManager.class);
    private static final Path PROFILE_PATH = Path.of("bot-maximize-profile.yaml");

    private static final Object lock = new Object();
    private static volatile ProfileSnapshot snapshot = ProfileSnapshot.empty();
    private static volatile FileTime lastLoadedMtime = null;

    private BotMaximizeProfileManager() {
    }

    static String profilePath() {
        return PROFILE_PATH.toString();
    }

    static ApplyResult applyForCurrentLevel(BotEntry entry, Character bot, boolean forceEquip) {
        return applyForCurrentLevel(entry, bot, forceEquip, false);
    }

    static ApplyResult applyForCurrentLevel(BotEntry entry, Character bot, boolean forceEquip, boolean forceReload) {
        if (entry == null || bot == null || !entry.maximizeProfileEnabled) {
            return ApplyResult.disabled();
        }

        if (forceReload) {
            reloadNow();
        } else {
            reloadIfChanged();
        }
        LevelPlan plan = snapshot.resolvePlan(bot.getJob(), bot.getLevel());
        if (plan == null) {
            return ApplyResult.noLevelPlan(bot.getLevel());
        }

        int apSpent = applyApTargets(bot, plan.apTargets);
        int spSpent = applySpTargets(bot, plan.spTargets);
        int forcedEquips = applyPreferredEquips(bot, plan.equipItemIds);
        boolean optimized = false;
        if (plan.optimizeEquip) {
            BotEquipManager.autoEquip(bot, entry.owner, entry.pendingLootOfferItem, forceEquip);
            optimized = true;
        }
        return new ApplyResult(true, bot.getLevel(), apSpent, spSpent, forcedEquips, optimized, false);
    }

    private static int applyApTargets(Character bot, Map<BotBuildManager.StatType, Integer> targets) {
        if (targets == null || targets.isEmpty()) {
            return 0;
        }

        int apBefore = bot.getRemainingAp();

        // If we cannot satisfy all configured level minima using current remaining AP,
        // reset AP distribution back to job floors first.
        if (!canReachAllTargets(bot, targets, apBefore)) {
            resetApToJobFloors(bot);
        }

        spendApTowardTargets(bot, targets);
        dumpRemainingApToPrimaryStat(bot);
        return Math.max(0, apBefore - bot.getRemainingAp());
    }

    private static void spendApTowardTargets(Character bot, Map<BotBuildManager.StatType, Integer> targets) {
        int remaining = bot.getRemainingAp();
        if (remaining < 1) {
            return;
        }

        int gainStr = 0;
        int gainDex = 0;
        int gainInt = 0;
        int gainLuk = 0;
        for (BotBuildManager.StatType stat : List.of(
                BotBuildManager.StatType.STR,
                BotBuildManager.StatType.DEX,
                BotBuildManager.StatType.INT,
                BotBuildManager.StatType.LUK)) {
            Integer target = targets.get(stat);
            if (target == null) {
                continue;
            }
            int current = switch (stat) {
                case STR -> bot.getStr() + gainStr;
                case DEX -> bot.getDex() + gainDex;
                case INT -> bot.getInt() + gainInt;
                case LUK -> bot.getLuk() + gainLuk;
            };
            int need = Math.max(0, target - current);
            int spend = Math.min(need, remaining);
            if (spend < 1) {
                continue;
            }
            switch (stat) {
                case STR -> gainStr += spend;
                case DEX -> gainDex += spend;
                case INT -> gainInt += spend;
                case LUK -> gainLuk += spend;
            }
            remaining -= spend;
            if (remaining < 1) {
                break;
            }
        }

        int spent = gainStr + gainDex + gainInt + gainLuk;
        if (spent > 0) {
            bot.assignStrDexIntLuk(gainStr, gainDex, gainInt, gainLuk);
        }
    }

    private static boolean canReachAllTargets(Character bot,
                                              Map<BotBuildManager.StatType, Integer> targets,
                                              int remainingAp) {
        int need = 0;
        for (Map.Entry<BotBuildManager.StatType, Integer> e : targets.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            int target = Math.max(0, e.getValue());
            int current = currentStat(bot, e.getKey());
            need += Math.max(0, target - current);
            if (need > remainingAp) {
                return false;
            }
        }
        return true;
    }

    private static void resetApToJobFloors(Character bot) {
        int minStr = AssignAPProcessor.getMinStatFloor(bot.getJob(), Stat.STR);
        int minDex = AssignAPProcessor.getMinStatFloor(bot.getJob(), Stat.DEX);
        int minInt = AssignAPProcessor.getMinStatFloor(bot.getJob(), Stat.INT);
        int minLuk = AssignAPProcessor.getMinStatFloor(bot.getJob(), Stat.LUK);

        bot.assignStrDexIntLuk(
                minStr - bot.getStr(),
                minDex - bot.getDex(),
                minInt - bot.getInt(),
                minLuk - bot.getLuk());
    }

    private static void dumpRemainingApToPrimaryStat(Character bot) {
        int remaining = bot.getRemainingAp();
        if (remaining < 1) {
            return;
        }

        BotBuildManager.StatType primary = primaryStatForJob(bot.getJob());
        int current = currentStat(bot, primary);
        int cap = Math.max(0, YamlConfig.config.server.MAX_AP - current);
        int gain = Math.min(remaining, cap);
        if (gain < 1) {
            return;
        }

        int gainStr = 0;
        int gainDex = 0;
        int gainInt = 0;
        int gainLuk = 0;
        switch (primary) {
            case STR -> gainStr = gain;
            case DEX -> gainDex = gain;
            case INT -> gainInt = gain;
            case LUK -> gainLuk = gain;
        }
        bot.assignStrDexIntLuk(gainStr, gainDex, gainInt, gainLuk);
    }

    private static BotBuildManager.StatType primaryStatForJob(Job job) {
        if (job == null) {
            return BotBuildManager.StatType.STR;
        }
        if (job.isA(Job.MAGICIAN) || job.isA(Job.BLAZEWIZARD1)) {
            return BotBuildManager.StatType.INT;
        }
        if (job.isA(Job.THIEF) || job.isA(Job.NIGHTWALKER1)) {
            return BotBuildManager.StatType.LUK;
        }
        if (job.isA(Job.BOWMAN) || job.isA(Job.WINDARCHER1)) {
            return BotBuildManager.StatType.DEX;
        }
        return BotBuildManager.StatType.STR;
    }

    private static int currentStat(Character bot, BotBuildManager.StatType stat) {
        return switch (stat) {
            case STR -> bot.getStr();
            case DEX -> bot.getDex();
            case INT -> bot.getInt();
            case LUK -> bot.getLuk();
        };
    }

    private static int applySpTargets(Character bot, Map<Integer, Integer> targets) {
        if (targets == null || targets.isEmpty()) {
            return 0;
        }

        int spent = 0;
        List<Map.Entry<Integer, Integer>> ordered = new ArrayList<>(targets.entrySet());
        ordered.sort(Comparator.comparingInt(Map.Entry::getKey));

        for (Map.Entry<Integer, Integer> e : ordered) {
            int skillId = e.getKey();
            int targetLevel = Math.max(0, e.getValue());
            Skill skill = SkillFactory.getSkill(skillId);
            if (skill == null) {
                continue;
            }
            int book = GameConstants.getSkillBook(skillId / 10000);
            while (bot.getRemainingSps()[book] > 0) {
                int current = bot.getSkillLevel(skill);
                if (current >= targetLevel) {
                    break;
                }
                if (!BotBuildManager.canLevelSkill(bot, skill, current)) {
                    break;
                }
                bot.gainSp(-1, book, false);
                bot.changeSkillLevel(skill, (byte) (current + 1), bot.getMasterLevel(skill), bot.getSkillExpiration(skill));
                spent++;
            }
        }
        return spent;
    }

    private static int applyPreferredEquips(Character bot, List<Integer> equipItemIds) {
        if (equipItemIds == null || equipItemIds.isEmpty()) {
            return 0;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory eqpInv = bot.getInventory(InventoryType.EQUIP);
        Inventory eqdInv = bot.getInventory(InventoryType.EQUIPPED);

        grantMissingProfileEquips(bot, equipItemIds, eqpInv, eqdInv);

        int moved = 0;

        for (int itemId : equipItemIds) {
            Equip equip = findEquipInBagByItemId(eqpInv, itemId);
            if (equip == null) {
                continue;
            }
            EquipSlot slot = EquipSlot.getFromTextSlot(ii.getEquipmentSlot(itemId));
            if (slot == null || slot == EquipSlot.PET_EQUIP) {
                continue;
            }
            short targetSlot = (short) slot.getPrimarySlot();
            if (targetSlot == 0 || !ii.canWearEquipment(bot, equip, targetSlot)) {
                continue;
            }
            if (!isPreferredEquipUpgrade(bot, equip, targetSlot)) {
                continue;
            }
            short from = equip.getPosition();
            if (from <= 0) {
                continue;
            }
            InventoryManipulator.handleItemMove(bot.getClient(), InventoryType.EQUIP, from, targetSlot, (short) 1);
            moved++;
        }
        return moved;
    }

    private static void grantMissingProfileEquips(Character bot, List<Integer> equipItemIds,
                                                  Inventory eqpInv, Inventory eqdInv) {
        for (int itemId : equipItemIds) {
            if (ItemConstants.getInventoryType(itemId) != InventoryType.EQUIP) {
                continue;
            }
            if (hasEquipByItemId(eqpInv, itemId) || hasEquipByItemId(eqdInv, itemId)) {
                continue;
            }
            InventoryManipulator.addById(bot.getClient(), itemId, (short) 1);
        }
    }

    private static boolean isPreferredEquipUpgrade(Character bot, Equip equip, short targetSlot) {
        List<BotEquipManager.EquipRecommendation> recs =
                BotEquipManager.findRecommendedEquipsFromItems(bot, List.of(equip));
        for (BotEquipManager.EquipRecommendation rec : recs) {
            if (rec.targetSlot() == targetSlot && rec.candidate() == equip) {
                return true;
            }
        }
        return false;
    }

    private static Equip findEquipInBagByItemId(Inventory eqpInv, int itemId) {
        for (Item item : eqpInv.list()) {
            if (item instanceof Equip equip && item.getItemId() == itemId) {
                return equip;
            }
        }
        return null;
    }

    private static boolean hasEquipByItemId(Inventory inv, int itemId) {
        for (Item item : inv.list()) {
            if (item instanceof Equip && item.getItemId() == itemId) {
                return true;
            }
        }
        return false;
    }

    private static void reloadIfChanged() {
        try {
            if (!Files.exists(PROFILE_PATH)) {
                synchronized (lock) {
                    snapshot = ProfileSnapshot.empty();
                    lastLoadedMtime = null;
                }
                return;
            }

            FileTime mtime = Files.getLastModifiedTime(PROFILE_PATH);
            if (lastLoadedMtime != null && lastLoadedMtime.equals(mtime)) {
                return;
            }

            synchronized (lock) {
                if (lastLoadedMtime != null && lastLoadedMtime.equals(mtime)) {
                    return;
                }
                snapshot = loadSnapshot(PROFILE_PATH);
                lastLoadedMtime = mtime;
            }
        } catch (IOException e) {
            log.warn("failed to load maximize profile {}", PROFILE_PATH, e);
        }
    }

    private static void reloadNow() {
        try {
            if (!Files.exists(PROFILE_PATH)) {
                synchronized (lock) {
                    snapshot = ProfileSnapshot.empty();
                    lastLoadedMtime = null;
                }
                return;
            }

            synchronized (lock) {
                snapshot = loadSnapshot(PROFILE_PATH);
                lastLoadedMtime = Files.getLastModifiedTime(PROFILE_PATH);
            }
        } catch (IOException e) {
            log.warn("failed to force-load maximize profile {}", PROFILE_PATH, e);
        }
    }

    private static ProfileSnapshot loadSnapshot(Path path) throws IOException {
        try (YamlReader reader = new YamlReader(Files.newBufferedReader(path, CharsetConstants.CHARSET))) {
            Object rootObj = reader.read();
            if (!(rootObj instanceof Map<?, ?> root)) {
                return ProfileSnapshot.empty();
            }

            Map<String, JobProfile> profiles = new LinkedHashMap<>();
            Object profilesObj = root.get("profiles");
            if (profilesObj instanceof Map<?, ?> profilesMap) {
                for (Map.Entry<?, ?> e : profilesMap.entrySet()) {
                    if (e.getKey() == null) {
                        continue;
                    }
                    String key = e.getKey().toString().trim().toLowerCase();
                    if (!(e.getValue() instanceof Map<?, ?> profileMapRaw)) {
                        continue;
                    }
                    Object levelsObj = profileMapRaw.get("levels");
                    if (!(levelsObj instanceof Map<?, ?> levelsMapRaw)) {
                        continue;
                    }
                    Map<Integer, LevelPlan> levels = parseLevelsMap(levelsMapRaw);
                    if (!levels.isEmpty()) {
                        profiles.put(key, new JobProfile(levels));
                    }
                }
            }
            return new ProfileSnapshot(profiles);
        }
    }

    private static Map<Integer, LevelPlan> parseLevelsMap(Map<?, ?> levelsMap) {
        Map<Integer, LevelPlan> levels = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : levelsMap.entrySet()) {
            Integer level = toInt(e.getKey());
            if (level == null || level < 1) {
                continue;
            }
            if (!(e.getValue() instanceof Map<?, ?> levelMap)) {
                continue;
            }
            levels.put(level, parseLevelPlan(levelMap));
        }
        return levels;
    }

    private static LevelPlan parseLevelPlan(Map<?, ?> levelMap) {
        Map<BotBuildManager.StatType, Integer> apTargets = new LinkedHashMap<>();
        Object apObj = levelMap.get("apTargets");
        if (apObj instanceof Map<?, ?> apMap) {
            for (Map.Entry<?, ?> e : apMap.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                try {
                    BotBuildManager.StatType stat = BotBuildManager.StatType.valueOf(e.getKey().toString().trim().toUpperCase());
                    Integer value = toInt(e.getValue());
                    if (value != null) {
                        apTargets.put(stat, Math.max(0, value));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore unknown stat key.
                }
            }
        }

        Map<Integer, Integer> spTargets = new LinkedHashMap<>();
        Object spObj = levelMap.get("spTargets");
        if (spObj instanceof Map<?, ?> spMap) {
            for (Map.Entry<?, ?> e : spMap.entrySet()) {
                Integer skillId = toInt(e.getKey());
                Integer target = toInt(e.getValue());
                if (skillId != null && target != null && skillId > 0 && target >= 0) {
                    spTargets.put(skillId, target);
                }
            }
        }

        List<Integer> equipItemIds = new ArrayList<>();
        Object equipsObj = levelMap.get("equipItemIds");
        if (equipsObj instanceof List<?> list) {
            for (Object o : list) {
                Integer id = toInt(o);
                if (id != null && id > 0) {
                    equipItemIds.add(id);
                }
            }
        }

        boolean optimizeEquip = toBoolean(levelMap.get("optimizeEquip"), true);
        return new LevelPlan(apTargets, spTargets, equipItemIds, optimizeEquip);
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return switch (value.toString().trim().toLowerCase()) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> defaultValue;
        };
    }

    private record ProfileSnapshot(Map<String, JobProfile> profiles) {
        static ProfileSnapshot empty() {
            return new ProfileSnapshot(Map.of());
        }

        LevelPlan resolvePlan(Job job, int level) {
            if (profiles.isEmpty()) {
                return null;
            }
            for (String key : profileLookupOrder(job)) {
                JobProfile profile = profiles.get(key);
                if (profile == null) {
                    continue;
                }
                LevelPlan plan = profile.levels().get(level);
                if (plan != null) {
                    return plan;
                }
            }
            return null;
        }

        private static List<String> profileLookupOrder(Job job) {
            List<String> order = new ArrayList<>();
            if (job != null) {
                order.add(job.name().toLowerCase());
                if (job.isA(Job.WARRIOR)) order.add("warrior");
                if (job.isA(Job.MAGICIAN)) order.add("magician");
                if (job.isA(Job.BOWMAN)) order.add("bowman");
                if (job.isA(Job.THIEF)) order.add("thief");
                if (job.isA(Job.PIRATE)) order.add("pirate");
                switch (job.getJobNiche()) {
                    case 1 -> addIfMissing(order, "warrior");
                    case 2 -> addIfMissing(order, "magician");
                    case 3 -> addIfMissing(order, "bowman");
                    case 4 -> addIfMissing(order, "thief");
                    case 5 -> addIfMissing(order, "pirate");
                }
                if (job == Job.BEGINNER) order.add("beginner");
            }
            order.add("default");
            return order;
        }

        private static void addIfMissing(List<String> order, String key) {
            if (!order.contains(key)) {
                order.add(key);
            }
        }
    }

    private record JobProfile(Map<Integer, LevelPlan> levels) {
    }

    private record LevelPlan(
            Map<BotBuildManager.StatType, Integer> apTargets,
            Map<Integer, Integer> spTargets,
            List<Integer> equipItemIds,
            boolean optimizeEquip) {
    }

    record ApplyResult(
            boolean applied,
            int level,
            int apSpent,
            int spSpent,
            int forcedEquips,
            boolean optimized,
            boolean noPlanForLevel) {
        static ApplyResult disabled() {
            return new ApplyResult(false, 0, 0, 0, 0, false, false);
        }

        static ApplyResult noLevelPlan(int level) {
            return new ApplyResult(false, level, 0, 0, 0, false, true);
        }
    }
}