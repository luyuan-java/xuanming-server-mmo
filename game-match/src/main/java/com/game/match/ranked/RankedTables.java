package com.game.match.ranked;

import com.game.match.support.MatchModes;
import com.game.proto.BattleParticipationFact;
import com.game.proto.match.RankedPresetView;
import com.game.proto.match.RankedTier;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.PlayModeTable;
import com.game.table.RankedPresetTable;
import com.game.table.RankedRuleTable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 论剑排位首发 V1 与玩法白名单配表只读视图（{@code RankedRule} id=1 + {@code RankedPreset} + {@code PlayMode} + {@code Dungeon}）。 */
public final class RankedTables {

    public static final int ROLE_KIND_CHARACTER = 1;
    public static final int ROLE_KIND_PET = 2;
    public static final int BOSS_TRIAL_KIND_GUARD_RESCUE = 5;
    public static final int BOSS_TRIAL_KIND_POLARITY = 6;
    public static final long INITIAL_RATING_CENTI = 150_000L;
    public static final List<Integer> PRIMARY_TEAM_SIZES = List.of(1, 3, 5);
    public static final List<Integer> SPECIAL_TEAM_SIZES = List.of(2, 4);

    private final RankedRuleTable rule;
    private final Map<Integer, RankedPresetTable> presetsById;
    private final List<RankedPresetView> presetViews;
    private final String configFingerprint;
    private final Map<Long, List<Integer>> allowedTeamSizesByModeConfig;
    private final Map<Integer, Integer> bossTrialKindByDungeon;

    private RankedTables(RankedRuleTable rule, Map<Integer, RankedPresetTable> presetsById,
                         List<RankedPresetView> presetViews, String configFingerprint,
                         Map<Long, List<Integer>> allowedTeamSizesByModeConfig,
                         Map<Integer, Integer> bossTrialKindByDungeon) {
        this.rule = rule;
        this.presetsById = presetsById;
        this.presetViews = presetViews;
        this.configFingerprint = configFingerprint;
        this.allowedTeamSizesByModeConfig = allowedTeamSizesByModeConfig;
        this.bossTrialKindByDungeon = bossTrialKindByDungeon;
    }

    public static RankedTables from(ConfigTables config) {
        Objects.requireNonNull(config, "config");
        RankedRuleTable rule = config.rankedRule().find(1)
                .orElseThrow(() -> new IllegalStateException("RankedRule 表缺少 id=1 规则行"));
        List<RankedPresetTable> sorted = config.rankedPreset().all().stream()
                .sorted(Comparator.comparingInt(RankedPresetTable::getSort).thenComparingInt(RankedPresetTable::getId))
                .toList();
        Map<Integer, RankedPresetTable> byId = new LinkedHashMap<>(sorted.size() * 2);
        for (RankedPresetTable row : sorted) {
            byId.put(row.getId(), row);
        }
        RankedPresetTable defChar = byId.get(rule.getDefaultCharacterPresetId());
        RankedPresetTable defPet = byId.get(rule.getDefaultPetPresetId());
        if (defChar == null || defChar.getRoleKind() != ROLE_KIND_CHARACTER
                || defPet == null || defPet.getRoleKind() != ROLE_KIND_PET) {
            throw new IllegalStateException("RankedRule 默认人物/宝宝预设无效");
        }
        List<RankedPresetView> views = sorted.stream().map(RankedTables::toView).toList();
        List<RankedPresetTable> byIdAsc = config.rankedPreset().all().stream()
                .sorted(Comparator.comparingInt(RankedPresetTable::getId))
                .toList();
        String fingerprint = computeConfigFingerprint(rule, byIdAsc);
        Map<Long, List<Integer>> allowedSizes = parsePlayModeAllowedSizes(config.playMode().all());
        Map<Integer, Integer> bossKinds = new HashMap<>();
        for (DungeonTable dungeon : config.dungeon().all()) {
            if (dungeon.getBossTrialKind() != 0) {
                bossKinds.put(dungeon.getId(), dungeon.getBossTrialKind());
            }
        }
        return new RankedTables(rule, Map.copyOf(byId), List.copyOf(views), fingerprint,
                Map.copyOf(allowedSizes), Map.copyOf(bossKinds));
    }

    static Map<Long, List<Integer>> parsePlayModeAllowedSizes(List<PlayModeTable> rows) {
        Map<Long, List<Integer>> result = new HashMap<>();
        for (PlayModeTable row : rows) {
            int mode = row.getMatchMode();
            int teamSizeMax = row.getTeamSizeMax();
            List<Integer> rawAllowed = row.getAllowedTeamSizesList();
            if (!rawAllowed.isEmpty()) {
                if (mode == MatchModes.PVE_SOLO) {
                    throw new IllegalStateException("PlayMode id=" + row.getId() + ": PVE_SOLO 不允许配置 allowed_team_sizes");
                }
                if (mode != MatchModes.PVE_TEAM && mode != MatchModes.PVP_TEAM) {
                    throw new IllegalStateException("PlayMode id=" + row.getId() + ": 固定人数模式不允许配置 allowed_team_sizes");
                }
                Set<Integer> seen = new HashSet<>();
                List<Integer> cleaned = new ArrayList<>(rawAllowed.size());
                for (int size : rawAllowed) {
                    if (size < 1 || size > teamSizeMax) {
                        throw new IllegalStateException("PlayMode id=" + row.getId()
                                + ": allowed_team_sizes 项 " + size + " 超出 1..team_size_max(" + teamSizeMax + ")");
                    }
                    if (!seen.add(size)) {
                        throw new IllegalStateException("PlayMode id=" + row.getId()
                                + ": allowed_team_sizes 含重复人数档 " + size);
                    }
                    cleaned.add(size);
                }
                cleaned.sort(Integer::compareTo);
                result.put(modeConfigKey(mode, row.getBattleConfigId()), List.copyOf(cleaned));
            } else if ((mode == MatchModes.PVE_TEAM || mode == MatchModes.PVP_TEAM) && teamSizeMax >= 1) {
                List<Integer> all = new ArrayList<>(teamSizeMax);
                for (int s = 1; s <= teamSizeMax; s++) {
                    all.add(s);
                }
                result.put(modeConfigKey(mode, row.getBattleConfigId()), List.copyOf(all));
            }
        }
        return result;
    }

    private static long modeConfigKey(int mode, int configId) {
        return (Integer.toUnsignedLong(mode) << 32) | Integer.toUnsignedLong(configId);
    }

    /** 返回 (mode, configId) 当前全部开放的人数档（升序）；未配置时返回空列表。 */
    public List<Integer> allowedTeamSizes(int mode, int configId) {
        return allowedTeamSizesByModeConfig.getOrDefault(modeConfigKey(mode, configId), List.of());
    }

    /** 判断指定人数档是否在 PlayMode 白名单内（若表中未登记该组合则回落按默认放行）。 */
    public boolean isAllowedTeamSize(int mode, int configId, int size) {
        List<Integer> allowed = allowedTeamSizes(mode, configId);
        return allowed.isEmpty() || allowed.contains(size);
    }

    /** 返回 Dungeon 表的 boss_trial_kind（0=常规，1..4=首领试炼，5=护卫救援，6=物法易势）。 */
    public int bossTrialKind(int configId) {
        return bossTrialKindByDungeon.getOrDefault(configId, 0);
    }

    /** 娱乐轮换免费 3×3 预设判定：物法易势 5V5 或 护卫救援（Dungeon 109, boss_trial_kind=5）。 */
    public boolean usesFreePreset(int mode, int configId) {
        return mode == MatchModes.POLARITY_5V5 || bossTrialKind(configId) == BOSS_TRIAL_KIND_GUARD_RESCUE;
    }

    public String configFingerprint() {
        return configFingerprint;
    }

    /** 判定单名玩家在排位中是否满足有效参赛（与 C++/Go 逐条一致）。 */
    public static boolean evaluateParticipation(BattleParticipationFact fact, boolean fled) {
        if (fled || fact == null) {
            return false;
        }
        if (fact.getManualActionAccepted()) {
            return true;
        }
        if (fact.getEnteredRoom() && fact.getExplicitAutoEnabled()) {
            return true;
        }
        return fact.getEnteredRoom() && fact.getNoActionOpportunity();
    }

    private static String computeConfigFingerprint(RankedRuleTable rule, List<RankedPresetTable> presetsByIdAsc) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("ranked-v1\n");
        sb.append("rule:id=1,ver=").append(Integer.toUnsignedString(rule.getRuleVersion()))
                .append(",cfg=").append(Integer.toUnsignedString(rule.getBattleConfigId()))
                .append(",cls=").append(Integer.toUnsignedString(rule.getReferenceClassId()))
                .append(",lvl=").append(Integer.toUnsignedString(rule.getCompetitiveLevel()))
                .append(",defc=").append(Integer.toUnsignedString(rule.getDefaultCharacterPresetId()))
                .append(",defp=").append(Integer.toUnsignedString(rule.getDefaultPetPresetId()))
                .append('\n');
        for (RankedPresetTable p : presetsByIdAsc) {
            sb.append("preset:").append(Integer.toUnsignedString(p.getId()))
                    .append(",kind=").append(Integer.toUnsignedString(p.getRoleKind()))
                    .append(",pet=").append(Integer.toUnsignedString(p.getPetTableId()))
                    .append(",w=").append(Integer.toUnsignedString(p.getWeightCon()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightSpi()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightStr()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightAgi()))
                    .append(",sk=");
            for (int i = 0; i < p.getSkillIdsCount(); i++) {
                if (i > 0) sb.append('|');
                sb.append(Integer.toUnsignedString(p.getSkillIds(i)));
            }
            sb.append(",pe=");
            int pairCount = Math.min(p.getPetEntryIdsCount(), p.getPetEntryPointsCount());
            for (int i = 0; i < pairCount; i++) {
                if (i > 0) sb.append('|');
                sb.append(Integer.toUnsignedString(p.getPetEntryIds(i)))
                        .append(':')
                        .append(Integer.toUnsignedString(p.getPetEntryPoints(i)));
            }
            sb.append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public RankedRuleTable rule() {
        return rule;
    }

    public int ruleVersion() {
        return rule.getRuleVersion();
    }

    public List<RankedPresetView> presetViews() {
        return presetViews;
    }

    public boolean isValidPair(int characterPresetId, int petPresetId) {
        RankedPresetTable ch = presetsById.get(characterPresetId);
        RankedPresetTable pet = presetsById.get(petPresetId);
        return ch != null && ch.getRoleKind() == ROLE_KIND_CHARACTER
                && pet != null && pet.getRoleKind() == ROLE_KIND_PET;
    }

    /** 硬上限排位分差窗口（永不退化为 +Inf）。 */
    public int spreadPoints(long waitedSeconds) {
        if (waitedSeconds < Integer.toUnsignedLong(rule.getSpreadTier1Seconds())) {
            return rule.getSpreadTier1Points();
        }
        if (waitedSeconds < Integer.toUnsignedLong(rule.getSpreadTier2Seconds())) {
            return rule.getSpreadTier2Points();
        }
        return rule.getSpreadTier3Points();
    }

    /** 服务端原精度段位映射；未亮段调用方返回 UNSPECIFIED。 */
    public RankedTier tierFor(long ratingCenti, boolean qualified) {
        if (!qualified) {
            return RankedTier.RANKED_TIER_UNSPECIFIED;
        }
        if (ratingCenti >= Integer.toUnsignedLong(rule.getTierWushuangCenti())) {
            return RankedTier.RANKED_TIER_WUSHUANG;
        }
        if (ratingCenti >= Integer.toUnsignedLong(rule.getTierTianshuCenti())) {
            return RankedTier.RANKED_TIER_TIANSHU;
        }
        if (ratingCenti >= Integer.toUnsignedLong(rule.getTierYunzhangCenti())) {
            return RankedTier.RANKED_TIER_YUNZHANG;
        }
        if (ratingCenti >= Integer.toUnsignedLong(rule.getTierQingfengCenti())) {
            return RankedTier.RANKED_TIER_QINGFENG;
        }
        return RankedTier.RANKED_TIER_CHUSHI;
    }

    private static RankedPresetView toView(RankedPresetTable row) {
        return RankedPresetView.newBuilder()
                .setPresetId(row.getId())
                .setRoleKind(row.getRoleKind())
                .setCode(row.getCode())
                .setName(row.getName())
                .setRoleTag(row.getRoleTag())
                .setPetTableId(row.getPetTableId())
                .addWeights(row.getWeightCon())
                .addWeights(row.getWeightSpi())
                .addWeights(row.getWeightStr())
                .addWeights(row.getWeightAgi())
                .addAllSkillIds(row.getSkillIdsList())
                .addAllPetEntryIds(row.getPetEntryIdsList())
                .addAllPetEntryPoints(row.getPetEntryPointsList())
                .setSort(row.getSort())
                .setDesc(row.getDesc())
                .build();
    }
}
