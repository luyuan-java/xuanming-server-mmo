package com.game.scene.progression;

import com.game.table.BattleProgressionRewardTable;
import com.game.table.BattleProgressionRuleTable;
import com.game.table.ConfigTables;
import com.game.table.PlayerLevelExpTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 角色升级曲线与全玩法经验/银两周软上限配表视图（PlayerLevelExp / BattleProgressionReward / BattleProgressionRule）。
 * 不可变，加载后任意线程可读；生产启动经 {@link #validate()} 强校验（fail-closed），旧单测无表时使用 {@link #EMPTY}。
 */
public final class BattleProgressionTables {

    public static final BattleProgressionTables EMPTY = new BattleProgressionTables(List.of(), List.of(), null);

    private final List<BattleProgressionRules.LevelExpRow> levelExpRows;
    private final List<BattleProgressionRules.ModeRewardRow> rewardRows;
    private final BattleProgressionRules.WeeklySilverRule weeklySilverRule;

    public BattleProgressionTables(List<BattleProgressionRules.LevelExpRow> levelExpRows,
                                   List<BattleProgressionRules.ModeRewardRow> rewardRows,
                                   BattleProgressionRules.WeeklySilverRule weeklySilverRule) {
        this.levelExpRows = List.copyOf(levelExpRows);
        this.rewardRows = List.copyOf(rewardRows);
        this.weeklySilverRule = weeklySilverRule;
    }

    public static BattleProgressionTables from(ConfigTables tables) {
        List<BattleProgressionRules.LevelExpRow> expRows = new ArrayList<>();
        for (PlayerLevelExpTable row : tables.playerLevelExp().all()) {
            expRows.add(new BattleProgressionRules.LevelExpRow(row.getId(), row.getRequiredTotalExp()));
        }
        expRows.sort(Comparator.comparingInt(BattleProgressionRules.LevelExpRow::level));

        List<BattleProgressionRules.ModeRewardRow> rewards = new ArrayList<>();
        for (BattleProgressionRewardTable row : tables.battleProgressionReward().all()) {
            rewards.add(new BattleProgressionRules.ModeRewardRow(
                    row.getMatchMode(), row.getDifficulty(), row.getOutcomeTier(),
                    row.getExpGain(), row.getSilverGain()));
        }

        BattleProgressionRules.WeeklySilverRule rule = tables.battleProgressionRule().find(1)
                .map(BattleProgressionTables::toRule)
                .orElse(null);
        return new BattleProgressionTables(expRows, rewards, rule);
    }

    private static BattleProgressionRules.WeeklySilverRule toRule(BattleProgressionRuleTable row) {
        return new BattleProgressionRules.WeeklySilverRule(
                row.getWeeklySilverSoftCap(),
                row.getDiminishedRatePermyriad(),
                row.getWeeklySilverHardCap());
    }

    /** 生产启动 fail-closed 校验：与 C++ {@code PlayerAttributeSystem::ValidateProgressionTables} 逐项一致。 */
    public void validate() {
        if (!BattleProgressionRules.validateLevelExpRows(levelExpRows)) {
            throw new IllegalStateException("PlayerLevelExp 表无效（必须覆盖 1..85 连续严格递增且 1 级为 0）");
        }
        if (rewardRows.isEmpty()) {
            throw new IllegalStateException("BattleProgressionReward 表为空");
        }
        for (BattleProgressionRules.ModeRewardRow row : rewardRows) {
            if (BattleProgressionRules.isGuildWarMode(row.matchMode()) && row.silverGain() != 0L) {
                throw new IllegalStateException("BattleProgressionReward 帮战模式银两必须为 0: mode=" + row.matchMode());
            }
        }
        if (!BattleProgressionRules.validateWeeklySilverRule(weeklySilverRule)) {
            throw new IllegalStateException("BattleProgressionRule(id=1) 无效");
        }
    }

    public List<BattleProgressionRules.LevelExpRow> levelExpRows() {
        return levelExpRows;
    }

    public List<BattleProgressionRules.ModeRewardRow> rewardRows() {
        return rewardRows;
    }

    public BattleProgressionRules.WeeklySilverRule weeklySilverRule() {
        return weeklySilverRule;
    }
}
