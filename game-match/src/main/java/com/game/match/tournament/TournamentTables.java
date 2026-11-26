package com.game.match.tournament;

import com.game.proto.common.base.CurrencyType;
import com.game.proto.mail.MailAttachments;
import com.game.proto.mail.MailCurrency;
import com.game.proto.mail.MailItem;
import com.game.proto.match.TournamentRewardItem;
import com.game.proto.match.TournamentRewardTierKind;
import com.game.proto.match.TournamentRewardTierView;
import com.game.table.ConfigTables;
import com.game.table.TournamentRewardTable;
import com.game.table.TournamentRuleTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 全服赛季战队锦标赛《群仙问道赛》V1 配表只读视图（{@code TournamentRule} id=1 + {@code TournamentReward} 全部档位）。 */
public final class TournamentTables {

    // 锦标赛名次/助威/竞猜银两奖励一律发放不可交易绑定银两（3），严禁误发付费灵石（1）。
    public static final int GOLD_CURRENCY_TYPE = CurrencyType.CURRENCY_TYPE_BIND_GOLD_VALUE;
    public static final int DEFAULT_RULE_ID = 1;

    private final TournamentRuleTable rule;
    private final Map<Integer, TournamentRewardTable> rewardsById;
    private final List<TournamentRewardTable> orderedRewards;
    private final List<TournamentRewardTierView> rewardTierViews;

    private TournamentTables(TournamentRuleTable rule,
                             Map<Integer, TournamentRewardTable> rewardsById,
                             List<TournamentRewardTable> orderedRewards,
                             List<TournamentRewardTierView> rewardTierViews) {
        this.rule = rule;
        this.rewardsById = rewardsById;
        this.orderedRewards = orderedRewards;
        this.rewardTierViews = rewardTierViews;
    }

    public static TournamentTables from(ConfigTables config) {
        Objects.requireNonNull(config, "config");
        TournamentRuleTable rule = config.tournamentRule().find(DEFAULT_RULE_ID)
                .orElseThrow(() -> new IllegalStateException("TournamentRule 表缺少 id=1 规则行"));
        validateRule(rule);
        List<TournamentRewardTable> sorted = config.tournamentReward().all().stream()
                .sorted(Comparator.comparingInt(TournamentRewardTable::getId))
                .toList();
        Map<Integer, TournamentRewardTable> byId = new LinkedHashMap<>(sorted.size() * 2);
        List<TournamentRewardTierView> views = new ArrayList<>(sorted.size());
        for (TournamentRewardTable row : sorted) {
            byId.put(row.getId(), row);
            views.add(toTierView(row));
        }
        return new TournamentTables(rule, Map.copyOf(byId), List.copyOf(sorted), List.copyOf(views));
    }

    private static void validateRule(TournamentRuleTable rule) {
        if (rule.getBattleConfigId() <= 0) {
            throw new IllegalStateException("TournamentRule.battle_config_id 必须 > 0");
        }
        int teamSize = rule.getBattleTeamSize();
        int rosterMax = rule.getRosterMaxSize();
        if (teamSize < 1 || teamSize > 5) {
            throw new IllegalStateException("TournamentRule.battle_team_size 越界: " + teamSize);
        }
        if (rosterMax < teamSize || rosterMax > 7) {
            throw new IllegalStateException("TournamentRule.roster_max_size 越界: " + rosterMax);
        }
        int maxTeams = rule.getKnockoutMaxTeams();
        if (maxTeams != 2 && maxTeams != 4 && maxTeams != 8 && maxTeams != 16) {
            throw new IllegalStateException("TournamentRule.knockout_max_teams 必须为 2/4/8/16: " + maxTeams);
        }
        if (Integer.compareUnsigned(rule.getSkillBanSlotsPerTeam(), 3) > 0) {
            throw new IllegalStateException("TournamentRule.skill_ban_slots_per_team 越界: "
                    + Integer.toUnsignedString(rule.getSkillBanSlotsPerTeam()));
        }
    }

    public TournamentRuleTable rule() {
        return rule;
    }

    public List<TournamentRewardTierView> rewardTierViews() {
        return rewardTierViews;
    }

    public Optional<TournamentRewardTable> rewardById(int id) {
        return Optional.ofNullable(rewardsById.get(id));
    }

    /** 返回名次对应的最高一档奖励；不在前 16 且完赛达标则返回参赛保底档。 */
    public Optional<TournamentRewardTable> rewardForPlacement(int finalRank, int qualifierPlayed) {
        if (finalRank == 3 && rule.getThirdPlaceMatchEnabled()) {
            for (TournamentRewardTable row : orderedRewards) {
                if (row.getTierKind() == TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_THIRD_PLACE_VALUE) {
                    return Optional.of(row);
                }
            }
        }
        if (finalRank > 0) {
            for (TournamentRewardTable row : orderedRewards) {
                if (row.getTierKind() == TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_THIRD_PLACE_VALUE
                        && !rule.getThirdPlaceMatchEnabled()) {
                    continue;
                }
                if (row.getMinRank() > 0 && finalRank >= row.getMinRank() && finalRank <= row.getMaxRank()) {
                    return Optional.of(row);
                }
            }
        }
        if (qualifierPlayed >= rule.getParticipationMinMatches()) {
            for (TournamentRewardTable row : orderedRewards) {
                if (row.getTierKind() == TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_PARTICIPANT_VALUE) {
                    return Optional.of(row);
                }
            }
        }
        return Optional.empty();
    }

    /** 返回免费助威命中的纪念奖励档。 */
    public Optional<TournamentRewardTable> cheerReward() {
        for (TournamentRewardTable row : orderedRewards) {
            if (row.getTierKind() == TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_CHEER_VALUE) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /** 返回神机竞猜命中达标的纪念奖励档（V3）。 */
    public Optional<TournamentRewardTable> predictionReward() {
        for (TournamentRewardTable row : orderedRewards) {
            if (row.getTierKind() == TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_PREDICTION_VALUE) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /** 构造绑定银两与绑定物品邮件附件字节（与 Go 侧 BuildMailAttachments 同口径）。 */
    public static MailAttachments buildMailAttachments(TournamentRewardTable row) {
        MailAttachments.Builder att = MailAttachments.newBuilder();
        if (row.getGoldAmount() > 0L) {
            att.addCurrencies(MailCurrency.newBuilder()
                    .setCurrencyType(GOLD_CURRENCY_TYPE)
                    .setAmount(row.getGoldAmount())
                    .build());
        }
        int n = Math.min(row.getItemIdsCount(), row.getItemCountsCount());
        for (int i = 0; i < n; i++) {
            int itemId = row.getItemIds(i);
            int count = row.getItemCounts(i);
            if (itemId != 0 && count != 0) {
                att.addItems(MailItem.newBuilder().setConfigId(itemId).setCount(count).build());
            }
        }
        return att.build();
    }

    private static TournamentRewardTierView toTierView(TournamentRewardTable row) {
        TournamentRewardTierKind kind = TournamentRewardTierKind.forNumber(row.getTierKind());
        TournamentRewardTierView.Builder b = TournamentRewardTierView.newBuilder()
                .setTierId(row.getId())
                .setTierKind(kind == null ? TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_UNSPECIFIED : kind)
                .setMinRank(row.getMinRank())
                .setMaxRank(row.getMaxRank())
                .setGoldAmount(row.getGoldAmount())
                .setTitleCode(row.getTitleCode())
                .setBadgeCode(row.getBadgeCode())
                .setDesc(row.getDesc());
        int n = Math.min(row.getItemIdsCount(), row.getItemCountsCount());
        for (int i = 0; i < n; i++) {
            int itemId = row.getItemIds(i);
            int count = row.getItemCounts(i);
            if (itemId != 0 && count != 0) {
                b.addItems(TournamentRewardItem.newBuilder().setItemId(itemId).setCount(count).build());
            }
        }
        return b.build();
    }
}
