package com.game.scene.bag;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.player.ItemCatalog.ItemSpec;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 正式表（与 mmorpg 同步的 Item / EquipSlot）读出来的规则。 */
class BagTablesTest {

    private static BagTables load() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        return BagTables.from(ConfigTables.load(dir));
    }

    @Test
    void 装备槽按部位分桶保持表序_物品规则() {
        BagTables tables = load();

        assertThat(tables.equipSlots(1)).containsExactly(0, 1);
        assertThat(tables.equipSlots(2)).containsExactly(2);
        assertThat(tables.equipSlots(0)).isEmpty();
        assertThat(tables.equipSlots(99)).isEmpty();
        assertThat(tables.item(1)).isEqualTo(new ItemSpec(1, 1, 1));
        assertThat(tables.item(9)).isEqualTo(new ItemSpec(9, 2, 0));
        assertThat(tables.item(10)).isEqualTo(new ItemSpec(10, 999, 0));
        assertThat(tables.item(10).stackable()).isTrue();
        assertThat(tables.item(1).stackable()).isFalse();
        assertThat(tables.item(987654)).isNull();
    }
}
