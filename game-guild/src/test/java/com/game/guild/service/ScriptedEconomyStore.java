package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.guild.store.EconomyStore;
import com.game.guild.store.GuildStore.ZoneFence;
import com.game.guild.store.TxOutcome;
import com.game.guild.store.pb.GuildAssetOpRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 经济服务单测用的存储替身：读走内存表，写走每个用例自己给的脚本；没给脚本的写一律 {@link AssertionError}（「不该碰库」本身就是断言）。
 * 每次调用写进 journal；记下最后一次预留的入参。
 */
public final class ScriptedEconomyStore implements EconomyStore {

    private final Consumer<String> journal;

    // ---- 读 ----
    /** (guild, player) → 帮贡；不在表里 = 不是成员。 */
    final Map<String, Contribution> contributions = new HashMap<>();
    final Map<Integer, Integer> donateUsage = new HashMap<>();
    final Map<ShopUsageKey, Integer> shopUsage = new HashMap<>();
    public final Map<Long, OpState> opStates = new HashMap<>();
    final Map<Integer, List<GuildAssetOpRow>> pending = new HashMap<>();
    final Map<Integer, List<GuildAssetOpRow>> recent = new HashMap<>();
    /** 非 null 时所有读都抛它。 */
    volatile RuntimeException readFailure;
    /** 非 null 时 opState 抛它（回读失败）。 */
    volatile RuntimeException opStateFailure;

    // ---- 写脚本 ----
    public Function<DonationReserve, TxOutcome<Reserved>> donate;
    public Function<ShopReserve, TxOutcome<ShopReserved>> shop;
    public BiFunction<Integer, UpgradeLevels, TxOutcome<Upgraded>> upgrade;

    // ---- 记下的入参 ----
    final List<DonationReserve> donations = Collections.synchronizedList(new ArrayList<>());
    final List<ShopReserve> shopOrders = Collections.synchronizedList(new ArrayList<>());
    volatile ZoneFence lastFence;
    volatile int lastDayKey;
    volatile int lastWeekKey;

    ScriptedEconomyStore(Consumer<String> journal) {
        this.journal = journal;
    }

    static String key(long guildId, long playerId) {
        return guildId + ":" + playerId;
    }

    void contribution(long guildId, long playerId, long total, long balance) {
        contributions.put(key(guildId, playerId), new Contribution(total, balance));
    }

    private void maybeFail(String what) {
        journal.accept("economy." + what);
        RuntimeException e = readFailure;
        if (e != null) {
            throw e;
        }
    }

    @Override
    public TxOutcome<Reserved> reserveDonation(DonationReserve in, Deadline deadline) {
        journal.accept("economy.reserveDonation " + Long.toUnsignedString(in.opId()));
        if (donate == null) {
            throw new AssertionError("不该调用捐献预留");
        }
        String invalid = in.invalidReason();
        if (invalid != null) {
            throw new AssertionError("预留入参违约: " + invalid);
        }
        donations.add(in);
        lastFence = in.fence();
        return donate.apply(in);
    }

    @Override
    public TxOutcome<ShopReserved> reserveShopOrder(ShopReserve in, Deadline deadline) {
        journal.accept("economy.reserveShopOrder " + Long.toUnsignedString(in.opId()));
        if (shop == null) {
            throw new AssertionError("不该调用兑换预留");
        }
        String invalid = in.invalidReason();
        if (invalid != null) {
            throw new AssertionError("预留入参违约: " + invalid);
        }
        shopOrders.add(in);
        lastFence = in.fence();
        return shop.apply(in);
    }

    @Override
    public TxOutcome<Upgraded> upgradeGuild(long guildId, long playerId, int expectedLevel, UpgradeLevels levels,
                                            ZoneFence fence, Deadline deadline) {
        journal.accept("economy.upgrade " + guildId + " " + expectedLevel);
        if (upgrade == null) {
            throw new AssertionError("不该调用升级");
        }
        lastFence = fence;
        return upgrade.apply(expectedLevel, levels);
    }

    @Override
    public Map<Integer, Integer> donateUsage(long playerId, int dayKey, Deadline deadline) {
        maybeFail("donateUsage");
        lastDayKey = dayKey;
        return Map.copyOf(donateUsage);
    }

    @Override
    public Map<ShopUsageKey, Integer> shopUsage(long playerId, int dayKey, int weekKey, Deadline deadline) {
        maybeFail("shopUsage");
        lastDayKey = dayKey;
        lastWeekKey = weekKey;
        return Map.copyOf(shopUsage);
    }

    @Override
    public Optional<Contribution> memberContribution(long guildId, long playerId, Deadline deadline) {
        maybeFail("memberContribution");
        return Optional.ofNullable(contributions.get(key(guildId, playerId)));
    }

    @Override
    public Optional<OpState> opState(long opId, Deadline deadline) {
        journal.accept("economy.opState " + Long.toUnsignedString(opId));
        RuntimeException e = opStateFailure;
        if (e != null) {
            throw e;
        }
        return Optional.ofNullable(opStates.get(opId));
    }

    @Override
    public List<GuildAssetOpRow> pendingOps(long playerId, int stream, int limit, Deadline deadline) {
        maybeFail("pendingOps " + stream + " " + limit);
        return pending.getOrDefault(stream, List.of());
    }

    @Override
    public List<GuildAssetOpRow> recentOps(long playerId, int stream, int limit, Deadline deadline) {
        maybeFail("recentOps " + stream + " " + limit);
        return recent.getOrDefault(stream, List.of());
    }
}
