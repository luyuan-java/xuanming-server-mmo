package com.game.guild.service;

import com.game.guild.store.EconomyStore;
import com.game.table.ConfigTables;
import com.game.table.GuildDonateTable;
import com.game.table.GuildShopTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 经济配表现查的替身：数据取仓库里真实的配表（config-data/tables，与 robot / 基线同一份），另可模拟「运行期缺行」（GuildRule 缺行、Item 缺行）——
 * {@link ConfigTables} 只能从磁盘整体加载，造不出缺行的快照。
 */
public final class FakeEconomyLookup implements EconomyTables.Lookup {

    static final ConfigTables TABLES = ConfigTables.load(tableDir());

    private final EconomyTables.Lookup real = EconomyTables.Lookup.of(() -> TABLES);
    volatile boolean ruleMissing;
    final Set<Integer> missingItems = new HashSet<>();

    static Path tableDir() {
        return Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
    }

    @Override
    public GuildDonateTable donateOption(int donateId) {
        return real.donateOption(donateId);
    }

    @Override
    public GuildShopTable shopGoods(int goodsId) {
        return real.shopGoods(goodsId);
    }

    @Override
    public OptionalInt itemMaxStack(int itemId) {
        return missingItems.contains(itemId) ? OptionalInt.empty() : real.itemMaxStack(itemId);
    }

    @Override
    public OptionalLong assetOpDeadlineMs() {
        return ruleMissing ? OptionalLong.empty() : real.assetOpDeadlineMs();
    }

    @Override
    public long assetOpRetryBaseMs() {
        return ruleMissing ? 0 : real.assetOpRetryBaseMs();
    }

    @Override
    public EconomyStore.UpgradeLevel upgradeLevel(int level) {
        return real.upgradeLevel(level);
    }

    @Override
    public List<GuildDonateTable> sortedDonateRows() {
        return real.sortedDonateRows();
    }

    @Override
    public List<GuildShopTable> sortedShopRows() {
        return real.sortedShopRows();
    }
}
