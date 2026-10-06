package com.game.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.proto.BattlePlacement;
import com.game.match.rating.pb.MatchRatingAppliedRow;
import com.game.match.rating.pb.MatchRatingRow;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.Test;

/**
 * xm-match 自有的两份 proto（match-spec §4.3、§5.2）：落点记录的字段号钉住（Redis 里的记录跨版本要读得出来，6.5 也读它）；
 * 评分两张表的定义 pbmysql 认得、生成的列 / 主键 / 索引与规格一致（建表与事务归评分的工作包，这里只保证表定义本身是对的）。不连库。
 */
class MatchProtoTest {

    @Test
    void 落点记录的字段号与类型钉住() {
        Descriptor d = BattlePlacement.getDescriptor();

        assertThat(d.getFullName()).isEqualTo("xm.match.BattlePlacement");
        assertThat(d.getFields()).hasSize(11);
        assertField(d, "battle_id", 1, FieldDescriptor.Type.UINT64);
        assertField(d, "battle_node_id", 2, FieldDescriptor.Type.UINT32);
        assertField(d, "battle_instance_id", 3, FieldDescriptor.Type.STRING);
        assertField(d, "rpc_host", 4, FieldDescriptor.Type.STRING);
        assertField(d, "rpc_port", 5, FieldDescriptor.Type.UINT32);
        assertField(d, "attempt", 6, FieldDescriptor.Type.UINT32);
        assertField(d, "mode", 7, FieldDescriptor.Type.UINT32);
        assertField(d, "battle_config_id", 8, FieldDescriptor.Type.UINT32);
        assertField(d, "player_names", 9, FieldDescriptor.Type.STRING);
        assertField(d, "created_at_ms", 10, FieldDescriptor.Type.UINT64);
        assertField(d, "deadline_ms", 11, FieldDescriptor.Type.UINT64);
        assertThat(d.findFieldByName("player_names").isRepeated()).isTrue();
    }

    @Test
    void 落点记录往返_名字顺序与不认识的模式值都保得住() throws Exception {
        BattlePlacement placement = BattlePlacement.newBuilder()
                .setBattleId(Long.MIN_VALUE + 5).setBattleNodeId(3).setBattleInstanceId("inst-a").setRpcHost("10.0.0.7").setRpcPort(21200)
                .setAttempt(2).setMode(42).setBattleConfigId(-1)
                .addPlayerNames("乙").addPlayerNames("甲").addPlayerNames("乙")
                .setCreatedAtMs(1_800_000_000_123L).setDeadlineMs(1_800_000_300_000L).build();

        BattlePlacement parsed = BattlePlacement.parseFrom(placement.toByteArray());

        assertThat(parsed).isEqualTo(placement);
        assertThat(parsed.getPlayerNamesList()).as("按成员顺序，重名也原样保留").containsExactly("乙", "甲", "乙");
        assertThat(parsed.getMode()).as("模式存数值：契约之外的值也不丢").isEqualTo(42);
        assertThat(Integer.toUnsignedString(parsed.getBattleConfigId())).isEqualTo("4294967295");
        assertThat(Long.toUnsignedString(parsed.getBattleId())).isEqualTo("9223372036854775813");
    }

    @Test
    void 评分表_列与主键() {
        String ddl = registry().createTableSql(MatchRatingRow.class);

        assertThat(ddl).startsWith("CREATE TABLE IF NOT EXISTS `match_rating` (");
        assertThat(ddl).contains("`player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1'");
        assertThat(ddl).as("评分是有符号的 64 位整数（× 100 的定点数）").contains("`rating_centi` bigint NOT NULL DEFAULT 0 COMMENT 'pb:2'");
        assertThat(ddl).contains("`games` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3'");
        assertThat(ddl).contains("`updated_at_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4'");
        assertThat(ddl).contains("PRIMARY KEY (`player_id`)");
        assertThat(ddl).doesNotContain("INDEX `idx_match_rating_0`");
        assertThat(columns(ddl)).isEqualTo(4);
    }

    @Test
    void 入账标记表_列_主键与按时间清理的索引() {
        String ddl = registry().createTableSql(MatchRatingAppliedRow.class);

        assertThat(ddl).startsWith("CREATE TABLE IF NOT EXISTS `match_rating_applied` (");
        assertThat(ddl).contains("`battle_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1'");
        assertThat(ddl).contains("`match_mode` int NOT NULL DEFAULT 0 COMMENT 'pb:2'");
        assertThat(ddl).contains("`delta_a_centi` bigint NOT NULL DEFAULT 0 COMMENT 'pb:3'");
        assertThat(ddl).contains("`applied_at_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4'");
        assertThat(ddl).as("battle_id 是主键：重复投递靠主键冲突挡住").contains("PRIMARY KEY (`battle_id`)");
        assertThat(ddl).contains("INDEX `idx_match_rating_applied_0` (`applied_at_ms`)");
        assertThat(columns(ddl)).isEqualTo(4);
    }

    @Test
    void 两张表的消息在自己的包里_不与同步来的契约类混在一起() {
        assertThat(MatchRatingRow.getDescriptor().getFullName()).isEqualTo("xm.match.MatchRatingRow");
        assertThat(MatchRatingAppliedRow.getDescriptor().getFullName()).isEqualTo("xm.match.MatchRatingAppliedRow");
        assertThat(MatchRatingRow.class.getPackageName()).isEqualTo("com.game.match.rating.pb");
        assertThat(BattlePlacement.class.getPackageName()).isEqualTo("com.game.match.proto");
    }

    private static PbMysql registry() {
        PbMysql db = new PbMysql();
        db.register(MatchRatingRow.getDefaultInstance());
        db.register(MatchRatingAppliedRow.getDefaultInstance());
        return db;
    }

    private static long columns(String ddl) {
        return ddl.lines().filter(line -> line.contains("COMMENT 'pb:")).count();
    }

    private static void assertField(Descriptor descriptor, String name, int number, FieldDescriptor.Type type) {
        FieldDescriptor field = descriptor.findFieldByName(name);
        assertThat(field).as(name).isNotNull();
        assertThat(field.getNumber()).as(name + " 的字段号").isEqualTo(number);
        assertThat(field.getType()).as(name + " 的类型").isEqualTo(type);
    }
}
