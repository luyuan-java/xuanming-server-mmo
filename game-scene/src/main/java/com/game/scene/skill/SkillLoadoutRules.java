package com.game.scene.skill;

import static com.game.table.CommonErrorTip.common_error.kInvalidParameter_VALUE;
import static com.game.table.SkillErrorTip.skill_error.*;

import com.game.player.store.state.PlayerSkillEntry;
import com.game.player.store.state.SkillLoadoutSchemeState;
import com.game.proto.SkillLoadoutSchemeInfo;
import com.game.proto.SkillLoadoutSnapshot;
import com.game.scene.player.PlayerSkills;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 人物战法构筑装配纯规则（对齐 C++ {@code skill_loadout_rules.h} 与 {@code player_skill.cpp}）：
 * 3 套流派方案，每套支持 3 被动 + 1 追击 + 1 开场；主动技与当季免费赛季技全量可用；同 {@code exclusive_group} 互斥。
 */
public final class SkillLoadoutRules {

    public static final int MAX_SCHEMES = 3;
    public static final int MAX_ROLE_TAG = 3;
    public static final int MAX_PASSIVE_SLOTS = 3;
    public static final int MAX_PURSUIT_SLOTS = 1;
    public static final int MAX_OPENING_SLOTS = 1;

    private SkillLoadoutRules() { }

    public static boolean validSchemeId(int schemeId) {
        long u = Integer.toUnsignedLong(schemeId);
        return u >= 1 && u <= MAX_SCHEMES;
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

    /** 校验一份待保存的构筑方案；0 表示通过，否则返回对应的 tip 码。 */
    public static int validateScheme(int schemeId, int roleTag, List<Integer> passiveIds,
                                     List<Integer> pursuitIds, List<Integer> openingIds,
                                     ClassSkillCatalog catalog, int classId, int level, PlayerSkills owned) {
        if (!validSchemeId(schemeId) || !validRoleTag(roleTag)) {
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

    /**
     * 计算当前生效的非主动常驻现代战法集合（被动 / 追击 / 开场）：
     * <ul>
     *   <li>{@code active_loadout_scheme_id == 0}（兼容态）：已学且可用者按无符号 ID 升序全量生效，同互斥组只取首条；</li>
     *   <li>{@code active_loadout_scheme_id in 1..3}：严格按当前方案内仍合法且未越槽/未冲突的战法生效。</li>
     * </ul>
     */
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

    /** 构造下发给客户端的 3 套流派构筑快照；未自定义前为方案 1 生成建议初始装配。 */
    public static SkillLoadoutSnapshot buildSnapshot(ClassSkillCatalog catalog, int classId,
                                                     int level, PlayerSkills owned) {
        boolean customEnabled = validSchemeId(owned.activeLoadoutSchemeId());
        SkillLoadoutSnapshot.Builder out = SkillLoadoutSnapshot.newBuilder()
                .setCustomEnabled(customEnabled)
                .setActiveSchemeId(customEnabled ? owned.activeLoadoutSchemeId() : 1)
                .setStateVersion(version(owned.loadoutStateVersion()))
                .setMaxPassiveSlots(MAX_PASSIVE_SLOTS)
                .setMaxPursuitSlots(MAX_PURSUIT_SLOTS)
                .setMaxOpeningSlots(MAX_OPENING_SLOTS)
                .setLastRequestId(owned.loadoutLastRequestId());
        for (int schemeId = 1; schemeId <= MAX_SCHEMES; schemeId++) {
            SkillLoadoutSchemeInfo.Builder info = SkillLoadoutSchemeInfo.newBuilder().setSchemeId(schemeId);
            SkillLoadoutSchemeState stored = owned.loadoutScheme(schemeId);
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
