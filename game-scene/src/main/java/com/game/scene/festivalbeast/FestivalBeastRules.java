package com.game.scene.festivalbeast;

import com.game.proto.FestivalBeastPhase;
import com.game.table.DungeonTable;
import com.game.table.FestivalBeastActivityTable;
import com.game.table.FestivalBeastTierTable;
import com.game.table.PetTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 瑞兽迎春限时活动纯规则（零 IO / 零场景依赖，供生产与单测共用；严格对齐 C++ {@code festival_beast_rules.h}）。
 */
public final class FestivalBeastRules {

    public static final long DAY_MS = 86_400_000L;
    public static final long REQUIRED_OPEN_DURATION_MS = 14L * DAY_MS;
    public static final int DEFAULT_SHOWCASE_DAYS = 7;
    public static final int REQUIRED_MIN_LEVEL = 1;
    public static final int REQUIRED_MAX_LEVEL = 120;
    public static final int BOSS_TRIAL_KIND_FESTIVAL_BEAST = 7;
    public static final long LAUNCH_RPC_DEADLINE_MS = 4_000L;

    private FestivalBeastRules() {
    }

    /** 标准 36 字符 UUID 解析结果（高 64 位、低 64 位，拒绝全 0）。 */
    public record Uuid128(long high, long low) {
    }

    /** 解析标准 36 字符 UUID（xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx，拒绝全 0）。 */
    public static Uuid128 parseUuid128(String text) {
        if (text == null || text.length() != 36) {
            return null;
        }
        long high = 0L;
        long low = 0L;
        int hexCount = 0;
        for (int i = 0; i < 36; i++) {
            char ch = text.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (ch != '-') {
                    return null;
                }
                continue;
            }
            long nibble;
            if (ch >= '0' && ch <= '9') {
                nibble = ch - '0';
            } else if (ch >= 'a' && ch <= 'f') {
                nibble = 10L + (ch - 'a');
            } else if (ch >= 'A' && ch <= 'F') {
                nibble = 10L + (ch - 'A');
            } else {
                return null;
            }
            if (hexCount < 16) {
                high = (high << 4) | nibble;
            } else {
                low = (low << 4) | nibble;
            }
            hexCount++;
        }
        if (hexCount != 32 || (high == 0L && low == 0L)) {
            return null;
        }
        return new Uuid128(high, low);
    }

    public static long claimShowcaseEndAtMs(long effectiveEndMs, int showcaseDays) {
        if (effectiveEndMs == 0L) {
            return 0L;
        }
        long days = showcaseDays > 0 ? Integer.toUnsignedLong(showcaseDays) : DEFAULT_SHOWCASE_DAYS;
        return effectiveEndMs + days * DAY_MS;
    }

    /** 服务端 UTC 毫秒裁定活动阶段；左闭右开 [start, end)，结束后集中领奖展示 [end, end + showcaseDays)。 */
    public static FestivalBeastPhase resolvePhase(boolean enabled, long startMs, long endMs,
                                                  int showcaseDays, long nowMs) {
        if (!enabled || startMs == 0L || Long.compareUnsigned(endMs, startMs) <= 0) {
            return FestivalBeastPhase.FESTIVAL_BEAST_PHASE_UNSCHEDULED;
        }
        if (Long.compareUnsigned(nowMs, startMs) < 0) {
            return FestivalBeastPhase.FESTIVAL_BEAST_PHASE_UPCOMING;
        }
        if (Long.compareUnsigned(nowMs, endMs) < 0) {
            return FestivalBeastPhase.FESTIVAL_BEAST_PHASE_OPEN;
        }
        if (Long.compareUnsigned(nowMs, claimShowcaseEndAtMs(endMs, showcaseDays)) < 0) {
            return FestivalBeastPhase.FESTIVAL_BEAST_PHASE_CLAIM_SHOWCASE;
        }
        return FestivalBeastPhase.FESTIVAL_BEAST_PHASE_HISTORY;
    }

    /** 正式神兽表行和资源引用完整性门禁：缺失或不齐时禁止开放发放。 */
    public static boolean isValidRewardPetRow(PetTable row) {
        if (row == null || row.getId() == 0 || row.getName().isEmpty() || row.getModelId() == 0) {
            return false;
        }
        if (row.getAcquisitionTier() != 2 || row.getCultivationTier() != 4
                || row.getTransferPolicy() != 1 || row.getExclusiveGiftSlots() == 0
                || !row.getFixedInitialAptitude()) {
            return false;
        }
        if (row.getAptitudeMinCount() < 4 || row.getAptitudeMaxCount() < 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (row.getAptitudeMin(i) == 0 || row.getAptitudeMin(i) != row.getAptitudeMax(i)) {
                return false;
            }
        }
        return true;
    }

    /** 等级档完整性门禁：非空、无重叠、无空洞、连续覆盖 1..120 级，且数值合法。 */
    public static boolean validateTiers(List<FestivalBeastTierTable> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return false;
        }
        List<FestivalBeastTierTable> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingLong((FestivalBeastTierTable t) -> Integer.toUnsignedLong(t.getMinLevel()))
                .thenComparingLong(t -> Integer.toUnsignedLong(t.getId())));
        long expectedMin = REQUIRED_MIN_LEVEL;
        for (FestivalBeastTierTable tier : sorted) {
            long minLevel = Integer.toUnsignedLong(tier.getMinLevel());
            long maxLevel = Integer.toUnsignedLong(tier.getMaxLevel());
            if (tier.getId() == 0 || minLevel != expectedMin || maxLevel < minLevel) {
                return false;
            }
            if (tier.getBossHealth() == 0L || tier.getBossPhysicalAttack() == 0L
                    || tier.getBossSpeed() == 0L || Long.compareUnsigned(tier.getBossResistance(), 100L) > 0) {
                return false;
            }
            if (maxLevel >= 0xFFFF_FFFFL) {
                return false;
            }
            expectedMin = maxLevel + 1L;
        }
        return expectedMin > REQUIRED_MAX_LEVEL;
    }

    public static FestivalBeastTierTable findTierForLevel(List<FestivalBeastTierTable> tiers, int ownerLevel) {
        long clamped = Math.max(REQUIRED_MIN_LEVEL, Integer.toUnsignedLong(ownerLevel));
        for (FestivalBeastTierTable tier : tiers) {
            if (clamped >= Integer.toUnsignedLong(tier.getMinLevel())
                    && clamped <= Integer.toUnsignedLong(tier.getMaxLevel())) {
                return tier;
            }
        }
        return null;
    }

    public static boolean validateActivityRow(FestivalBeastActivityTable row,
                                              DungeonTable dungeon,
                                              PetTable rewardPet,
                                              List<FestivalBeastTierTable> tiers) {
        if (row == null || row.getId() == 0 || row.getConfigRevision() == 0
                || row.getActivityName().isEmpty() || row.getBossName().isEmpty()
                || row.getBannerAssetKey().isEmpty() || row.getIconKey().isEmpty()
                || row.getRewardIdentity().isEmpty() || row.getRewardDefinitionVersion() == 0
                || row.getRequiredWins() == 0 || row.getRoundLimit() == 0
                || row.getCycleRounds() == 0 || row.getFirstHeavyRound() == 0
                || Integer.compareUnsigned(row.getFirstHeavyRound(), row.getCycleRounds()) > 0
                || row.getTelegraphLeadRounds() == 0
                || Integer.compareUnsigned(row.getTelegraphLeadRounds(), row.getFirstHeavyRound()) >= 0
                || row.getHeavyDamagePercent() == 0) {
            return false;
        }
        if (dungeon == null || dungeon.getId() != row.getBattleConfigId()
                || dungeon.getBossTrialKind() != BOSS_TRIAL_KIND_FESTIVAL_BEAST
                || dungeon.getMaxTeamSize() != 1 || dungeon.getAllowPetCapture() != 0
                || dungeon.getMonsterCount() == 0) {
            return false;
        }
        int nonZeroMonsters = 0;
        for (int mid : dungeon.getMonsterList()) {
            if (mid != 0) {
                nonZeroMonsters++;
            }
        }
        if (nonZeroMonsters != 1) {
            return false;
        }
        if (!isValidRewardPetRow(rewardPet) || rewardPet.getId() != row.getRewardPetTableId()) {
            return false;
        }
        if (!validateTiers(tiers)) {
            return false;
        }
        if (row.getEnabled()) {
            if (row.getEffectiveStartAtMs() == 0L
                    || Long.compareUnsigned(row.getEffectiveEndAtMs(), row.getEffectiveStartAtMs()) <= 0) {
                return false;
            }
            if (row.getEffectiveEndAtMs() - row.getEffectiveStartAtMs() != REQUIRED_OPEN_DURATION_MS) {
                return false;
            }
        }
        return true;
    }
}
