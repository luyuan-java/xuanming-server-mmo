package com.game.scene.skill;

import static com.game.table.CommonErrorTip.common_error.kInvalidParameter_VALUE;
import static com.game.table.SkillErrorTip.skill_error.*;

import com.game.player.store.state.PlayerSkillEntry;
import com.game.player.store.state.SkillLoadoutSchemeState;
import com.game.proto.SkillLoadoutSchemeInfo;
import com.game.proto.SkillLoadoutSnapshot;
import com.game.scene.attribute.AttributeService;
import com.game.scene.player.PlayerSkills;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * 人物「战法·加点」一体化流派方案纯规则（对齐 C++ {@code skill_loadout_rules.h} 与 {@code player_skill.cpp}）：
 * 永久免费 2 套 + 达 85 级满级自动永久再赠 1 套（0 氪长期纯玩共 3 套免费）+ 仙途轻享卡生效期额外 +2 套
 * + 按套按月租用额外槽位（单套 30 真实灵石/30 天，共享统一到期日，最多叠 180 天），封顶 7 套；
 * 每套含 1 开场 + 3 被动 + 1 追击 + 1:1 绑定的四维加点方案；到期只锁不删。
 */
public final class SkillLoadoutRules {

    public static final int BASE_FREE_SCHEMES = 2;
    public static final int MAX_LEVEL_FREE_SCHEME_UNLOCK_LEVEL = 85;
    public static final int MAX_LEVEL_BONUS_FREE_SCHEMES = 1;
    public static final int BASE_MAX_SCHEMES = BASE_FREE_SCHEMES;
    public static final int LIGHT_CARD_BONUS_SCHEMES = 2;
    public static final int MAX_RENTED_EXTRA_SLOTS = 5;
    public static final int MAX_SCHEMES = 7;
    public static final int MAX_ROLE_TAG = 4; // 0默认 / 1输出 / 2控场 / 3辅助 / 4生存
    public static final int MAX_PASSIVE_SLOTS = 3;
    public static final int MAX_PURSUIT_SLOTS = 1;
    public static final int MAX_OPENING_SLOTS = 1;
    public static final int MAX_NAME_CODE_POINTS = 12;

    public static final long RENT_UNIT_PRICE_DIAMOND = 30L;
    public static final int RENT_PERIOD_DAYS = 30;
    public static final long SECONDS_PER_DAY = 86_400L;
    public static final long RENT_PERIOD_SECONDS = RENT_PERIOD_DAYS * SECONDS_PER_DAY;
    public static final int RENT_MAX_REMAINING_DAYS = 180;
    public static final long RENT_MAX_REMAINING_SECONDS = RENT_MAX_REMAINING_DAYS * SECONDS_PER_DAY;
    public static final int RENT_MAX_PERIODS_PER_CALL = 6;

    private static final String[] BLOCKED_TERMS = {
            "官方", "客服", "管理员", "系统消息", "外挂", "代充", "私服", "gm", "admin"
    };

    public record RentPlan(
            int tipError,
            int newRentedSlots,
            long newExpiresAtSec,
            long prorateCostDiamond,
            long renewCostDiamond,
            long totalCostDiamond) {
        public boolean ok() {
            return tipError == 0;
        }
    }

    public record SnapshotContext(
            boolean lightCardActive,
            long nowUnixSec,
            long diamondBalance,
            long attributeResetCostGold,
            long attributeSwitchCooldownUntil,
            int activeAttributeSchemeId,
            IntFunction<AttributeService.SchemeAllocationSummary> attributeSummary) {
    }

    private SkillLoadoutRules() { }

    public static int activeRentedSlots(int storedRentedSlots, long rentExpiresAtSec, long nowUnixSec) {
        if (Long.compareUnsigned(rentExpiresAtSec, nowUnixSec) <= 0) {
            return 0;
        }
        long u = Integer.toUnsignedLong(storedRentedSlots);
        return (int) Math.min(u, MAX_RENTED_EXTRA_SLOTS);
    }

    public static int freeSchemesForLevel(int effectiveLevel) {
        return BASE_FREE_SCHEMES + (effectiveLevel >= MAX_LEVEL_FREE_SCHEME_UNLOCK_LEVEL ? MAX_LEVEL_BONUS_FREE_SCHEMES : 0);
    }

    public static int maxRentableExtraSlots(int freeSchemes, boolean lightCardActive) {
        int base = freeSchemes + (lightCardActive ? LIGHT_CARD_BONUS_SCHEMES : 0);
        return Math.max(0, MAX_SCHEMES - base);
    }

    public static int maxRentableExtraSlots(boolean lightCardActive) {
        return maxRentableExtraSlots(BASE_FREE_SCHEMES, lightCardActive);
    }

    public static int unlockedSchemes(int freeSchemes, boolean lightCardActive, int storedRentedSlots,
                                      long rentExpiresAtSec, long nowUnixSec) {
        int total = freeSchemes
                + (lightCardActive ? LIGHT_CARD_BONUS_SCHEMES : 0)
                + activeRentedSlots(storedRentedSlots, rentExpiresAtSec, nowUnixSec);
        return Math.min(total, MAX_SCHEMES);
    }

    public static int unlockedSchemes(boolean lightCardActive, int storedRentedSlots,
                                      long rentExpiresAtSec, long nowUnixSec) {
        return unlockedSchemes(BASE_FREE_SCHEMES, lightCardActive, storedRentedSlots, rentExpiresAtSec, nowUnixSec);
    }

    public static int unlockedSchemes(boolean lightCardActive) {
        return unlockedSchemes(BASE_FREE_SCHEMES, lightCardActive, 0, 0L, 0L);
    }

    public static boolean validSchemeId(int schemeId) {
        return validSchemeId(schemeId, MAX_SCHEMES);
    }

    public static boolean validSchemeId(int schemeId, int maxAllowed) {
        long u = Integer.toUnsignedLong(schemeId);
        return u >= 1 && u <= Integer.toUnsignedLong(maxAllowed);
    }

    public static boolean validRoleTag(int roleTag) {
        return Integer.toUnsignedLong(roleTag) <= MAX_ROLE_TAG;
    }

    public static boolean validRequestId(String requestId) {
        return requestId != null && requestId.matches("[a-zA-Z0-9_-]{16,64}");
    }

    public static long version(long stored) {
        return stored == 0 ? 1L : stored;
    }

    public static String defaultSchemeName(int schemeId) {
        return switch (schemeId) {
            case 1 -> "方案一";
            case 2 -> "方案二";
            case 3 -> "方案三";
            case 4 -> "方案四";
            case 5 -> "方案五";
            case 6 -> "方案六";
            case 7 -> "方案七";
            default -> "方案" + Integer.toUnsignedString(schemeId);
        };
    }

    private static boolean isInvisibleCodePoint(int cp) {
        return cp == 0x20 || cp == 0xA0 || (cp >= 0x2000 && cp <= 0x200F) || cp == 0x2028 || cp == 0x2029
                || cp == 0x202F || cp == 0x205F || cp == 0x2060 || cp == 0x3000 || cp == 0xFEFF;
    }

    /** 方案名合法性校验：UTF-8 1..12 字符、非全空白、无控制字符/尖括号、过敏感词检查（对齐 C++ ValidateSchemeName）。 */
    public static boolean validateSchemeName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        int codePoints = 0;
        boolean hasVisible = false;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp <= 0x9F) || cp == '<' || cp == '>') {
                return false;
            }
            codePoints++;
            if (codePoints > MAX_NAME_CODE_POINTS) {
                return false;
            }
            if (!isInvisibleCodePoint(cp)) {
                hasVisible = true;
            }
        }
        if (!hasVisible) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String term : BLOCKED_TERMS) {
            if (lower.contains(term)) {
                return false;
            }
        }
        return true;
    }

    /** 按套按月租用 / 扩容补差价 / 续期纯规划（与 C++ skillloadout::PlanRent 逐项一致）。 */
    public static RentPlan planRent(boolean lightCardActive, int storedRentedSlots, long currentExpiresAtSec,
                                    long nowUnixSec, int targetRentedSlots, int addPeriods) {
        return planRent(BASE_FREE_SCHEMES, lightCardActive, storedRentedSlots, currentExpiresAtSec,
                nowUnixSec, targetRentedSlots, addPeriods);
    }

    public static RentPlan planRent(int freeSchemes, boolean lightCardActive, int storedRentedSlots, long currentExpiresAtSec,
                                    long nowUnixSec, int targetRentedSlots, int addPeriods) {
        int maxRentable = maxRentableExtraSlots(freeSchemes, lightCardActive);
        int activeRented = activeRentedSlots(storedRentedSlots, currentExpiresAtSec, nowUnixSec);
        boolean rentActive = activeRented > 0 && Long.compareUnsigned(currentExpiresAtSec, nowUnixSec) > 0;
        long remainingSec = rentActive ? (currentExpiresAtSec - nowUnixSec) : 0L;

        long targetUnsigned = Integer.toUnsignedLong(targetRentedSlots);
        long periodsUnsigned = Integer.toUnsignedLong(addPeriods);
        if (targetUnsigned < 1 || targetUnsigned > maxRentable) {
            return new RentPlan(kSkillLoadoutRentSlotsInvalid_VALUE, 0, 0, 0, 0, 0);
        }
        int minAllowedInTerm = Math.min(activeRented, maxRentable);
        if (rentActive && targetUnsigned < minAllowedInTerm) {
            return new RentPlan(kSkillLoadoutRentSlotsInvalid_VALUE, 0, 0, 0, 0, 0);
        }
        if (periodsUnsigned > RENT_MAX_PERIODS_PER_CALL) {
            return new RentPlan(kSkillLoadoutRentStackLimit_VALUE, 0, 0, 0, 0, 0);
        }
        if (!rentActive && periodsUnsigned == 0) {
            return new RentPlan(kInvalidParameter_VALUE, 0, 0, 0, 0, 0);
        }
        if (rentActive && periodsUnsigned == 0 && targetUnsigned <= activeRented) {
            return new RentPlan(kInvalidParameter_VALUE, 0, 0, 0, 0, 0);
        }

        long addSec = periodsUnsigned * RENT_PERIOD_SECONDS;
        if (periodsUnsigned > 0) {
            if (Long.compareUnsigned(remainingSec, RENT_MAX_REMAINING_SECONDS) > 0
                    || Long.compareUnsigned(RENT_MAX_REMAINING_SECONDS - remainingSec, addSec) < 0) {
                return new RentPlan(kSkillLoadoutRentStackLimit_VALUE, 0, 0, 0, 0, 0);
            }
            if (nowUnixSec < 0 || remainingSec > Long.MAX_VALUE - addSec || nowUnixSec > Long.MAX_VALUE - remainingSec - addSec) {
                return new RentPlan(kSkillLoadoutRentStackLimit_VALUE, 0, 0, 0, 0, 0);
            }
        }

        int target = (int) targetUnsigned;
        int addedSlots = (rentActive && target > activeRented) ? (target - activeRented) : 0;
        long prorateCost = 0L;
        if (addedSlots > 0 && remainingSec > 0) {
            long perPeriod = addedSlots * RENT_UNIT_PRICE_DIAMOND;
            long fullPeriods = Long.divideUnsigned(remainingSec, RENT_PERIOD_SECONDS);
            long remSec = Long.remainderUnsigned(remainingSec, RENT_PERIOD_SECONDS);
            prorateCost = fullPeriods * perPeriod
                    + (remSec == 0L ? 0L : (perPeriod * remSec + RENT_PERIOD_SECONDS - 1L) / RENT_PERIOD_SECONDS);
            if (prorateCost == 0L) {
                prorateCost = 1L;
            }
        }
        long renewCost = target * RENT_UNIT_PRICE_DIAMOND * periodsUnsigned;
        long newExpires = periodsUnsigned > 0 ? (nowUnixSec + remainingSec + addSec) : currentExpiresAtSec;
        return new RentPlan(0, target, newExpires, prorateCost, renewCost, prorateCost + renewCost);
    }

    /** 校验一份待保存的构筑方案；0 表示通过，否则返回对应的 tip 码。 */
    public static int validateScheme(int schemeId, int roleTag, List<Integer> passiveIds,
                                     List<Integer> pursuitIds, List<Integer> openingIds,
                                     ClassSkillCatalog catalog, int classId, int level, PlayerSkills owned) {
        return validateScheme(schemeId, roleTag, passiveIds, pursuitIds, openingIds,
                catalog, classId, level, owned, MAX_SCHEMES);
    }

    public static int validateScheme(int schemeId, int roleTag, List<Integer> passiveIds,
                                     List<Integer> pursuitIds, List<Integer> openingIds,
                                     ClassSkillCatalog catalog, int classId, int level, PlayerSkills owned,
                                     int maxAllowedSchemes) {
        if (!validSchemeId(schemeId, maxAllowedSchemes) || !validRoleTag(roleTag)) {
            return kSkillLoadoutSchemeInvalid_VALUE;
        }
        if (passiveIds.size() > MAX_PASSIVE_SLOTS
                || pursuitIds.size() > MAX_PURSUIT_SLOTS
                || openingIds.size() > MAX_OPENING_SLOTS) {
            return kSkillLoadoutSlotLimitReached_VALUE;
        }
        Set<Integer> seenSkills = new HashSet<>();
        Set<Integer> occupiedGroups = new HashSet<>();
        int err = checkSlotList(passiveIds, 2, catalog, classId, level, owned, seenSkills, occupiedGroups);
        if (err != 0) return err;
        err = checkSlotList(pursuitIds, 3, catalog, classId, level, owned, seenSkills, occupiedGroups);
        if (err != 0) return err;
        return checkSlotList(openingIds, 4, catalog, classId, level, owned, seenSkills, occupiedGroups);
    }

    private static int checkSlotList(List<Integer> ids, int expectedKind, ClassSkillCatalog catalog,
                                     int classId, int level, PlayerSkills owned,
                                     Set<Integer> seenSkills, Set<Integer> occupiedGroups) {
        for (int skillId : ids) {
            if (skillId == 0 || !seenSkills.add(skillId)) {
                return kInvalidParameter_VALUE;
            }
            SkillTable row = catalog.row(skillId);
            if (row == null || row.getSeasonId() != 0 || !ClassSkillCatalog.forSubject(row, 1)
                    || row.getAbilityKind() != expectedKind) {
                return kInvalidParameter_VALUE;
            }
            if (!catalog.usable(classId, level, owned, skillId)) {
                return kSkillLoadoutNotLearned_VALUE;
            }
            if (row.getExclusiveGroup() != 0 && !occupiedGroups.add(row.getExclusiveGroup())) {
                return kSkillLoadoutConflict_VALUE;
            }
        }
        return 0;
    }

    public static Set<Integer> collectEquippedNonActiveSkills(ClassSkillCatalog catalog, int classId,
                                                              int level, PlayerSkills owned) {
        Set<Integer> equipped = new HashSet<>();
        Set<Integer> occupiedGroups = new HashSet<>();
        if (validSchemeId(owned.activeLoadoutSchemeId())) {
            SkillLoadoutSchemeState scheme = owned.loadoutScheme(owned.activeLoadoutSchemeId());
            if (scheme == null) {
                return equipped;
            }
            addSchemeSlot(scheme.getPassiveSkillIdsList(), 2, MAX_PASSIVE_SLOTS, catalog, classId, level, owned, equipped, occupiedGroups);
            addSchemeSlot(scheme.getPursuitSkillIdsList(), 3, MAX_PURSUIT_SLOTS, catalog, classId, level, owned, equipped, occupiedGroups);
            addSchemeSlot(scheme.getOpeningSkillIdsList(), 4, MAX_OPENING_SLOTS, catalog, classId, level, owned, equipped, occupiedGroups);
            return equipped;
        }
        List<Integer> learnedIds = new ArrayList<>();
        for (PlayerSkillEntry entry : owned.entries()) {
            if (usableNonActiveModern(catalog, classId, level, owned, entry.getSkillId()) != null) {
                learnedIds.add(entry.getSkillId());
            }
        }
        learnedIds.sort(Integer::compareUnsigned);
        for (int id : learnedIds) {
            SkillTable row = catalog.row(id);
            if (row == null) continue;
            if (row.getExclusiveGroup() != 0 && !occupiedGroups.add(row.getExclusiveGroup())) continue;
            equipped.add(id);
        }
        return equipped;
    }

    private static void addSchemeSlot(List<Integer> ids, int expectedKind, int limit,
                                      ClassSkillCatalog catalog, int classId, int level, PlayerSkills owned,
                                      Set<Integer> equipped, Set<Integer> occupiedGroups) {
        int count = 0;
        for (int id : ids) {
            if (count >= limit) break;
            SkillTable row = usableNonActiveModern(catalog, classId, level, owned, id);
            if (row == null || row.getAbilityKind() != expectedKind) continue;
            if (row.getExclusiveGroup() != 0 && !occupiedGroups.add(row.getExclusiveGroup())) continue;
            if (equipped.add(id)) count++;
        }
    }

    private static SkillTable usableNonActiveModern(ClassSkillCatalog catalog, int classId, int level,
                                                    PlayerSkills owned, int skillId) {
        SkillTable row = catalog.row(skillId);
        if (row == null || row.getBattleModel() != 1 || row.getSeasonId() != 0 || row.getAbilityKind() <= 1) {
            return null;
        }
        return catalog.usable(classId, level, owned, skillId) ? row : null;
    }

    public static SkillLoadoutSnapshot buildSnapshot(ClassSkillCatalog catalog, int classId,
                                                     int level, PlayerSkills owned) {
        return buildSnapshot(catalog, classId, level, owned,
                new SnapshotContext(false, 0L, 0L, 0L, 0L, 1, null));
    }

    public static SkillLoadoutSnapshot buildSnapshot(ClassSkillCatalog catalog, int classId,
                                                     int level, PlayerSkills owned, int unlockedSchemes) {
        boolean lc = unlockedSchemes >= BASE_FREE_SCHEMES + LIGHT_CARD_BONUS_SCHEMES;
        return buildSnapshot(catalog, classId, level, owned,
                new SnapshotContext(lc, 0L, 0L, 0L, 0L, 1, null));
    }

    public static SkillLoadoutSnapshot buildSnapshot(ClassSkillCatalog catalog, int classId,
                                                     int level, PlayerSkills owned, SnapshotContext ctx) {
        int freeSchemes = freeSchemesForLevel(level);
        int activeRented = activeRentedSlots(owned.rentedExtraSlots(), owned.rentExpiresAtSec(), ctx.nowUnixSec());
        int unlocked = unlockedSchemes(freeSchemes, ctx.lightCardActive(), owned.rentedExtraSlots(), owned.rentExpiresAtSec(), ctx.nowUnixSec());
        boolean customEnabled = validSchemeId(owned.activeLoadoutSchemeId());
        SkillLoadoutSnapshot.Builder out = SkillLoadoutSnapshot.newBuilder()
                .setCustomEnabled(customEnabled)
                .setActiveSchemeId(customEnabled ? owned.activeLoadoutSchemeId() : 1)
                .setStateVersion(version(owned.loadoutStateVersion()))
                .setMaxPassiveSlots(MAX_PASSIVE_SLOTS)
                .setMaxPursuitSlots(MAX_PURSUIT_SLOTS)
                .setMaxOpeningSlots(MAX_OPENING_SLOTS)
                .setLastRequestId(owned.loadoutLastRequestId())
                .setUnlockedSchemes(unlocked)
                .setMaxSchemes(MAX_SCHEMES)
                .setFreeSchemes(freeSchemes)
                .setLightCardBonusSchemes(ctx.lightCardActive() ? LIGHT_CARD_BONUS_SCHEMES : 0)
                .setRentedExtraSlots(activeRented)
                .setRentExpiresAtSec(activeRented > 0 ? owned.rentExpiresAtSec() : 0L)
                .setServerNowSec(ctx.nowUnixSec())
                .setRentUnitPriceDiamond(RENT_UNIT_PRICE_DIAMOND)
                .setRentPeriodDays(RENT_PERIOD_DAYS)
                .setRentMaxRemainingDays(RENT_MAX_REMAINING_DAYS)
                .setDiamondBalance(ctx.diamondBalance())
                .setAttributeResetCostGold(ctx.attributeResetCostGold())
                .setAttributeSwitchCooldownUntil(ctx.attributeSwitchCooldownUntil())
                .setActiveAttributeSchemeId(ctx.activeAttributeSchemeId());

        for (int schemeId = 1; schemeId <= MAX_SCHEMES; schemeId++) {
            SkillLoadoutSchemeState stored = owned.loadoutScheme(schemeId);
            if (schemeId > unlocked && stored == null) {
                continue;
            }
            String displayName = (stored != null && !stored.getName().isEmpty())
                    ? stored.getName()
                    : defaultSchemeName(schemeId);
            SkillLoadoutSchemeInfo.Builder info = SkillLoadoutSchemeInfo.newBuilder()
                    .setSchemeId(schemeId)
                    .setName(displayName);
            if (ctx.attributeSummary() != null) {
                var summary = ctx.attributeSummary().apply(schemeId);
                if (summary != null) {
                    info.putAllAttributeAllocated(summary.allocated())
                            .setAttributeRemaining(summary.remaining())
                            .setAttributeTotalPoints(summary.totalPoints());
                }
            }
            if (stored != null) {
                info.setRoleTag(stored.getRoleTag())
                        .addAllPassiveSkillIds(stored.getPassiveSkillIdsList())
                        .addAllPursuitSkillIds(stored.getPursuitSkillIdsList())
                        .addAllOpeningSkillIds(stored.getOpeningSkillIdsList());
            } else if (!customEnabled && schemeId == 1) {
                List<Integer> learnedIds = new ArrayList<>();
                for (PlayerSkillEntry entry : owned.entries()) {
                    if (usableNonActiveModern(catalog, classId, level, owned, entry.getSkillId()) != null) {
                        learnedIds.add(entry.getSkillId());
                    }
                }
                learnedIds.sort(Integer::compareUnsigned);
                Set<Integer> groups = new HashSet<>();
                for (int id : learnedIds) {
                    SkillTable row = catalog.row(id);
                    if (row == null) continue;
                    if (row.getExclusiveGroup() != 0 && groups.contains(row.getExclusiveGroup())) continue;
                    boolean added = false;
                    if (row.getAbilityKind() == 2 && info.getPassiveSkillIdsCount() < MAX_PASSIVE_SLOTS) {
                        info.addPassiveSkillIds(id);
                        added = true;
                    } else if (row.getAbilityKind() == 3 && info.getPursuitSkillIdsCount() < MAX_PURSUIT_SLOTS) {
                        info.addPursuitSkillIds(id);
                        added = true;
                    } else if (row.getAbilityKind() == 4 && info.getOpeningSkillIdsCount() < MAX_OPENING_SLOTS) {
                        info.addOpeningSkillIds(id);
                        added = true;
                    }
                    if (added && row.getExclusiveGroup() != 0) {
                        groups.add(row.getExclusiveGroup());
                    }
                }
            }
            out.addSchemes(info);
        }
        return out.build();
    }

    /** 已启用自定义方案时，新习得的常驻非主动战法在有空槽且无互斥冲突时自动装入当前方案（按 ID 升序）。 */
    public static void tryAutoSlotNewlyLearned(ClassSkillCatalog catalog, PlayerSkills owned, SkillTable row) {
        if (row == null || row.getBattleModel() != 1 || row.getSeasonId() != 0
                || row.getAbilityKind() <= 1 || !validSchemeId(owned.activeLoadoutSchemeId())) {
            return;
        }
        int activeSchemeId = owned.activeLoadoutSchemeId();
        SkillLoadoutSchemeState existing = owned.loadoutScheme(activeSchemeId);
        SkillLoadoutSchemeState.Builder scheme = existing == null
                ? SkillLoadoutSchemeState.newBuilder().setSchemeId(activeSchemeId)
                : existing.toBuilder();
        List<Integer> currentSlot = switch (row.getAbilityKind()) {
            case 2 -> new ArrayList<>(scheme.getPassiveSkillIdsList());
            case 3 -> new ArrayList<>(scheme.getPursuitSkillIdsList());
            default -> new ArrayList<>(scheme.getOpeningSkillIdsList());
        };
        int limit = switch (row.getAbilityKind()) {
            case 2 -> MAX_PASSIVE_SLOTS;
            case 3 -> MAX_PURSUIT_SLOTS;
            default -> MAX_OPENING_SLOTS;
        };
        if (currentSlot.size() >= limit || currentSlot.contains(row.getId())) {
            return;
        }
        if (row.getExclusiveGroup() != 0 && (conflictsWith(catalog, scheme.getPassiveSkillIdsList(), row.getExclusiveGroup())
                || conflictsWith(catalog, scheme.getPursuitSkillIdsList(), row.getExclusiveGroup())
                || conflictsWith(catalog, scheme.getOpeningSkillIdsList(), row.getExclusiveGroup()))) {
            return;
        }
        currentSlot.add(row.getId());
        currentSlot.sort(Integer::compareUnsigned);
        if (row.getAbilityKind() == 2) {
            scheme.clearPassiveSkillIds().addAllPassiveSkillIds(currentSlot);
        } else if (row.getAbilityKind() == 3) {
            scheme.clearPursuitSkillIds().addAllPursuitSkillIds(currentSlot);
        } else {
            scheme.clearOpeningSkillIds().addAllOpeningSkillIds(currentSlot);
        }
        owned.putLoadoutScheme(scheme.build());
    }

    private static boolean conflictsWith(ClassSkillCatalog catalog, List<Integer> ids, int exclusiveGroup) {
        for (int existingId : ids) {
            SkillTable existing = catalog.row(existingId);
            if (existing != null && existing.getExclusiveGroup() == exclusiveGroup) {
                return true;
            }
        }
        return false;
    }
}
