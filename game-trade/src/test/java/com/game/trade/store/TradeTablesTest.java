package com.game.trade.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.pbmysql.PbMysql;
import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingStatus;
import com.game.proto.trade.TradeFavoriteRecord;
import com.game.proto.trade.TradeListingRecord;
import com.game.trade.store.pb.TradeFavoriteRow;
import com.game.trade.store.pb.TradeListingRow;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 两张表的 DDL 与 Go 版逐字节相同（trade-spec §1.5、§5.5「DDL 对拍」、§9.3）：拿 Java 自有的 trade_tables.proto 生成的建表语句，对拍同步来的
 * mmorpg trade_table.proto（{@code com.game.proto.trade.*Record}，即 Go proto2mysql 的输入）生成的，再与 spec §1.5 原文逐字比对。
 * 另钉住枚举列的取值约定与契约枚举逐值相等（§9.1「枚举对拍」）。不连库。
 */
class TradeTablesTest {

    private static final String TAIL = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            + " /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT=";

    /** spec §1.5 的 trade_listing 原文。 */
    static final String LISTING_DDL = String.join("\n",
            "CREATE TABLE IF NOT EXISTS `trade_listing` (",
            "  `listing_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
            "  `seller_player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',",
            "  `seller_account` MEDIUMTEXT COMMENT 'pb:3',",
            "  `market_zone` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',",
            "  `seller_zone_at_listing` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:5',",
            "  `category` int NOT NULL DEFAULT 0 COMMENT 'pb:6',",
            "  `subcategory` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:7',",
            "  `title` MEDIUMTEXT COMMENT 'pb:8',",
            "  `level` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:9',",
            "  `price_fen` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:10',",
            "  `status` int NOT NULL DEFAULT 0 COMMENT 'pb:11',",
            "  `summary` MEDIUMTEXT COMMENT 'pb:12',",
            "  `description` MEDIUMTEXT COMMENT 'pb:13',",
            "  `icon_key` MEDIUMTEXT COMMENT 'pb:14',",
            "  `notice_end_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:15',",
            "  `sale_end_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:16',",
            "  `created_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:17',",
            "  `updated_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:18',",
            "  `version` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:19',",
            "  PRIMARY KEY (`listing_id`) /*T![clustered_index] NONCLUSTERED */,",
            "  INDEX `idx_trade_listing_0` (`market_zone`,`status`,`category`,`subcategory`,`price_fen`),",
            "  INDEX `idx_trade_listing_1` (`status`,`category`,`subcategory`,`price_fen`),",
            "  INDEX `idx_trade_listing_2` (`seller_player_id`,`listing_id`)",
            TAIL + "'trade_listing';");

    /** spec §1.5 的 trade_favorite 原文。 */
    static final String FAVORITE_DDL = String.join("\n",
            "CREATE TABLE IF NOT EXISTS `trade_favorite` (",
            "  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
            "  `listing_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',",
            "  `created_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
            "  PRIMARY KEY (`player_id`,`listing_id`) /*T![clustered_index] NONCLUSTERED */,",
            "  INDEX `idx_trade_favorite_0` (`listing_id`)",
            TAIL + "'trade_favorite';");

    @Test
    void 两张表与同步来的trade_table逐字节相同() {
        PbMysql java = TradeTables.registry();
        PbMysql go = new PbMysql();
        go.register(TradeListingRecord.getDefaultInstance());
        go.register(TradeFavoriteRecord.getDefaultInstance());
        assertThat(java.createTableSql(TradeListingRow.class)).isEqualTo(go.createTableSql(TradeListingRecord.class));
        assertThat(java.createTableSql(TradeFavoriteRow.class)).isEqualTo(go.createTableSql(TradeFavoriteRecord.class));
    }

    @Test
    void 两张表与spec原文逐字相同() {
        PbMysql java = TradeTables.registry();
        assertThat(java.createTableSql(TradeListingRow.class)).isEqualTo(LISTING_DDL);
        assertThat(java.createTableSql(TradeFavoriteRow.class)).isEqualTo(FAVORITE_DDL);
    }

    @Test
    void 登记顺序与表名() {
        assertThat(TradeTables.NAMES).containsExactly("trade_listing", "trade_favorite");
        PbMysql db = TradeTables.registry();
        List<String> registered = TradeTables.PROTOTYPES.stream().map(m -> db.schema(m).tableName()).toList();
        assertThat(registered).isEqualTo(TradeTables.NAMES);
        assertThat(TradeTables.LISTING).isEqualTo(db.schema(TradeListingRow.class).tableName());
        assertThat(TradeTables.FAVORITE).isEqualTo(db.schema(TradeFavoriteRow.class).tableName());
        // 手写 SQL 里的表名字面量与登记的一致
        assertThat(ListingSql.COUNT_LISTINGS).contains(" FROM " + TradeTables.LISTING);
        assertThat(ListingSql.COUNT_FAVORITES).contains(" FROM " + TradeTables.FAVORITE);
    }

    /** 字段号、名字与基线 Record 逐个相同；枚举列在 Java 侧是 int32（生成 {@code int}，不是 {@code int unsigned}；§1.5 末条）。 */
    @Test
    void 字段与基线Record一一对应() {
        assertSameFields(TradeListingRow.getDescriptor().getFields(), TradeListingRecord.getDescriptor().getFields());
        assertSameFields(TradeFavoriteRow.getDescriptor().getFields(), TradeFavoriteRecord.getDescriptor().getFields());
        assertThat(TradeListingRow.getDescriptor().findFieldByName("category").getType()).isEqualTo(FieldDescriptor.Type.INT32);
        assertThat(TradeListingRow.getDescriptor().findFieldByName("status").getType()).isEqualTo(FieldDescriptor.Type.INT32);
        // Listing 记录的分量与表字段同名同序（camelCase）
        List<String> components = java.util.Arrays.stream(Listing.class.getRecordComponents()).map(c -> c.getName()).toList();
        List<String> fields = TradeListingRow.getDescriptor().getFields().stream().map(FieldDescriptor::getJsonName).toList();
        assertThat(components).isEqualTo(fields);
    }

    private static void assertSameFields(List<FieldDescriptor> java, List<FieldDescriptor> go) {
        assertThat(java).hasSameSizeAs(go);
        for (int i = 0; i < java.size(); i++) {
            assertThat(java.get(i).getName()).isEqualTo(go.get(i).getName());
            assertThat(java.get(i).getNumber()).as(java.get(i).getName()).isEqualTo(go.get(i).getNumber());
        }
    }

    /** ListingStatuses 与契约 ListingStatus（trade_table.proto:17-26）逐值相等、互相覆盖。 */
    @Test
    void 存储状态与契约枚举逐值相等() throws IllegalAccessException {
        Map<String, Integer> java = new HashMap<>();
        for (Field f : ListingStatuses.class.getDeclaredFields()) {
            if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                java.put(f.getName(), f.getInt(null));
            }
        }
        Map<String, Integer> contract = new HashMap<>();
        for (ListingStatus s : ListingStatus.values()) {
            if (s != ListingStatus.UNRECOGNIZED) {
                contract.put(s.name().substring("LISTING_STATUS_".length()), s.getNumber());
            }
        }
        assertThat(java).isEqualTo(contract);
    }

    /** category 列存的就是契约 ListingCategory 的值（1..9）：契约枚举不得改号（已上架商品不会自动迁移）。 */
    @Test
    void 类目编码与契约枚举一致() {
        assertThat(ListingCategory.LISTING_CATEGORY_CHARACTER_VALUE).isEqualTo(1);
        assertThat(ListingCategory.LISTING_CATEGORY_PET_VALUE).isEqualTo(2);
        assertThat(ListingCategory.LISTING_CATEGORY_WEAPON_VALUE).isEqualTo(3);
        assertThat(ListingCategory.LISTING_CATEGORY_ARMOR_VALUE).isEqualTo(4);
        assertThat(ListingCategory.LISTING_CATEGORY_SET_VALUE).isEqualTo(5);
        assertThat(ListingCategory.LISTING_CATEGORY_TREASURE_VALUE).isEqualTo(6);
        assertThat(ListingCategory.LISTING_CATEGORY_JEWELRY_VALUE).isEqualTo(7);
        assertThat(ListingCategory.LISTING_CATEGORY_SUMMONING_ORDER_VALUE).isEqualTo(8);
        assertThat(ListingCategory.LISTING_CATEGORY_CURRENCY_VALUE).isEqualTo(9);
        assertThat(ListingCategory.values()).hasSize(11); // UNSPECIFIED + 9 + UNRECOGNIZED
    }
}
