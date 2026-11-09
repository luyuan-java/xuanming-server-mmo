package com.game.scene.skill;

import static com.game.scene.world.SceneMessageIds.tip;
import static com.game.table.SkillErrorTip.skill_error.*;

import com.game.battle.engine.ClassSkillRules;
import com.game.proto.ListSkillsResponse;
import com.game.proto.PlayerSkillComp;
import com.game.proto.PlayerSkillListComp;
import com.game.proto.SkillCatalogEntry;
import com.game.proto.UpgradeSkillRequest;
import com.game.proto.UpgradeSkillResponse;
import com.game.scene.attribute.AttributeService;
import com.game.scene.audit.AssetAudit;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.PlayerSkills;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SwitchPhase;
import com.game.table.CommonErrorTip;
import com.game.table.SkillTable;

/** 技能书与修习用例。同步在场景线程执行，扣款和存储重数共用整份存档的归属围栏。 */
public final class ClassSkillService {
    private final CurrencyService currency;
    private final AttributeService attributes;

    public ClassSkillService(CurrencyService currency, AttributeService attributes) {
        this.currency = currency;
        this.attributes = attributes;
    }

    static int busy(ScenePlayer player) {
        return player.inBattle() || player.switchPhase() != SwitchPhase.NONE
                ? kSkillUpgradeBusy_VALUE : 0;
    }

    static int stop(ClassSkillRules.UpgradeStop stop) {
        return switch (stop) {
            case NONE -> 0;
            case MAX_RANK -> kSkillMaxRankReached_VALUE;
            case LEVEL_LIMIT -> kSkillLevelNotEnough_VALUE;
            case GOLD -> kSkillGoldNotEnough_VALUE;
        };
    }

    /** 显式客户端投影，绝不把内部持久未知字段或未来技能传出。 */
    public static ListSkillsResponse book(ScenePlayer player) {
        var catalog = player.skillCatalog();
        var owned = player.learnedSkills();
        int level = PlayerLevels.effective(player.level());
        long balance = player.wallet().balance(Wallet.GOLD);
        var list = PlayerSkillListComp.newBuilder();
        for (int id : player.skills()) {
            SkillTable row = catalog.row(id);
            var entry = PlayerSkillComp.newBuilder().setSkillTableId(id);
            if (row.getClassId() != 0) entry.setRank(owned.rank(id));
            list.addSkillList(entry);
        }
        var result = ListSkillsResponse.newBuilder().setErrorMessage(tip(0)).setSkillList(list)
                .setClassId(player.classId()).setPlayerLevel(level).setCurrencyBalance(balance);
        for (SkillTable row : catalog.classRows(player.classId())) {
            int rank = owned.rank(row.getId());
            int limit = ClassSkillRules.rankLimit(level, row.getUnlockLevel(), row.getMaxRank());
            boolean full = Integer.compareUnsigned(rank, row.getMaxRank()) >= 0;
            long cost = full ? 0 : ClassSkillRules.upgradeCost(rank + 1, row.getUnlockLevel());
            int blocked = busy(player);
            if (blocked == 0) blocked = full ? kSkillMaxRankReached_VALUE
                    : Integer.compareUnsigned(rank, limit) >= 0 ? kSkillLevelNotEnough_VALUE
                    : Long.compareUnsigned(cost, balance) > 0 ? kSkillGoldNotEnough_VALUE : 0;
            result.addCatalog(SkillCatalogEntry.newBuilder().setSkillTableId(row.getId()).setName(row.getName())
                    .setDescription(row.getDescription()).setIconKey(row.getIconKey()).setClassId(row.getClassId())
                    .setCategory(row.getCategory()).setPassive(!row.getPassiveStat().isEmpty())
                    .setTargetTeam(row.getTargetTeam()).setMaxTargets(row.getMaxTargets()).setUnlockLevel(row.getUnlockLevel())
                    .setMaxRank(row.getMaxRank()).setRank(rank).setRankLimit(limit).setManaPercent(row.getManaPercent())
                    .setCooldownRounds(row.getCooldownRounds()).setNextRankCost(cost).setUpgradeBlockTipId(blocked)
                    .setDurationRounds(catalog.durationRounds(row)));
        }
        return result.build();
    }

    public UpgradeSkillResponse upgrade(ScenePlayer player, UpgradeSkillRequest request) {
        if (!ClassSkillRules.isValidUpgradeCount(request.getCount()))
            return rejected(player, kSkillUpgradeCountInvalid_VALUE);
        SkillTable row = player.skillCatalog().row(request.getSkillTableId());
        if (row == null) return rejected(player, CommonErrorTip.common_error.kInvalidTableId_VALUE);
        if (row.getClassId() == 0 || row.getClassId() != player.classId())
            return rejected(player, kSkillNotInClass_VALUE);
        int busy = busy(player);
        if (busy != 0) return rejected(player, busy);
        int oldRank = player.learnedSkills().rank(row.getId());
        if (oldRank != request.getExpectedRank()) return rejected(player, kSkillRankChanged_VALUE);
        int limit = ClassSkillRules.rankLimit(PlayerLevels.effective(player.level()), row.getUnlockLevel(), row.getMaxRank());
        var plan = ClassSkillRules.planUpgrade(oldRank, row.getMaxRank(), limit, row.getUnlockLevel(),
                player.wallet().balance(Wallet.GOLD), request.getCount());
        if (plan.steps() == 0) return rejected(player, stop(plan.stop()));

        PlayerSkills candidate = PlayerSkills.restore(player.learnedSkills().toState());
        candidate.setRank(row.getId(), plan.finalRank());
        var payment = currency.prepareDeduction(player, Wallet.GOLD, plan.totalCost());
        if (payment.tipId() != 0) return rejected(player, payment.tipId());
        payment.commitSkills(candidate, AssetAudit.Reason.SKILL_UPGRADE, Integer.toUnsignedLong(row.getId()));
        if (!row.getPassiveStat().isEmpty()) attributes.skillsChanged(player);
        return UpgradeSkillResponse.newBuilder().setErrorMessage(tip(0)).setState(book(player))
                .setUpgradedCount(plan.steps()).setStopTipId(plan.steps() < request.getCount() ? stop(plan.stop()) : 0).build();
    }

    private static UpgradeSkillResponse rejected(ScenePlayer player, int error) {
        return UpgradeSkillResponse.newBuilder().setErrorMessage(tip(error)).setState(book(player)).build();
    }
}
