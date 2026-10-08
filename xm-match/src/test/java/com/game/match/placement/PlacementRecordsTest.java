package com.game.match.placement;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.placement.PlacementRecords.Parsed;
import com.game.match.proto.BattlePlacement;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 落点 HASH 的存储形状（字段名、损坏判据）：{@link RedissonPlacementStore}（179）与 6.5 的观战存储共用这一份。两个口径：
 * 只看 {@code pb} 的（179，与 6.4 相同）；连 attempt 字段一起核对的（观战：读到的 attempt 要拿去做剔除守护与公开条件）。
 */
class PlacementRecordsTest {

    private static final long BATTLE = Long.MIN_VALUE + 77;

    private static BattlePlacement placement(int attempt) {
        return BattlePlacement.newBuilder().setBattleId(BATTLE).setBattleNodeId(3).setBattleInstanceId("inst-a").setRpcHost("10.0.0.3").setRpcPort(21200)
                .setAttempt(attempt).setMode(3).addPlayerNames("甲").setCreatedAtMs(1_800_000_000_000L).build();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void 字段名逐字钉住_写脚本与读脚本里的字面量就是它们() {
        assertThat(PlacementRecords.FIELD_ATTEMPT).isEqualTo("a");
        assertThat(PlacementRecords.FIELD_PLACEMENT).isEqualTo("pb");
        assertThat(RedissonPlacementStore.PLACE_LUA).as("写：一次 HSET 同时写两个字段")
                .contains("'" + PlacementRecords.FIELD_ATTEMPT + "', ARGV[1], '" + PlacementRecords.FIELD_PLACEMENT + "', ARGV[2]")
                .contains("redis.call('HGET', KEYS[1], '" + PlacementRecords.FIELD_ATTEMPT + "')");
        assertThat(RedissonPlacementStore.READ_LUA)
                .contains("redis.call('HGET', KEYS[1], '" + PlacementRecords.FIELD_ATTEMPT + "')")
                .contains("redis.call('HGET', KEYS[1], '" + PlacementRecords.FIELD_PLACEMENT + "')");
        assertThat(PlacementRecords.attemptField(1)).isEqualTo("1");
        assertThat(PlacementRecords.attemptField(2)).isEqualTo("2");
        assertThat(PlacementRecords.attemptField(-1)).as("attempt 是 uint32").isEqualTo("4294967295");
    }

    @Test
    void 只看pb_一条好记录原样解析出来() {
        BattlePlacement placement = placement(2);

        Parsed parsed = PlacementRecords.parse(BATTLE, placement.toByteArray());

        assertThat(parsed).isEqualTo(new Parsed.Ok(placement));
    }

    @Test
    void 只看pb_缺字段_解析失败_battle_id与键不符_都是损坏_原因写得出是哪一种() {
        Parsed missing = PlacementRecords.parse(BATTLE, null);
        Parsed empty = PlacementRecords.parse(BATTLE, new byte[0]);
        Parsed garbage = PlacementRecords.parse(BATTLE, new byte[] {0x0A, 0x7F, 0x01});
        Parsed otherKey = PlacementRecords.parse(BATTLE + 1, placement(1).toByteArray());

        assertThat(((Parsed.Corrupt) missing).why()).contains("缺 pb 字段");
        assertThat(((Parsed.Corrupt) empty).why()).contains("缺 pb 字段");
        assertThat(((Parsed.Corrupt) garbage).why()).contains("解析失败");
        assertThat(((Parsed.Corrupt) otherKey).why()).contains("与键不符").contains("9223372036854775885");
    }

    @Test
    void 连attempt一起核对_字段与消息一致才是好记录() {
        BattlePlacement placement = placement(2);

        assertThat(PlacementRecords.parse(BATTLE, ascii("2"), placement.toByteArray())).isEqualTo(new Parsed.Ok(placement));
        assertThat(PlacementRecords.parse(BATTLE, ascii(PlacementRecords.attemptField(1)), placement(1).toByteArray()))
                .isEqualTo(new Parsed.Ok(placement(1)));
    }

    @Test
    void 连attempt一起核对_字段缺失或与消息不一致_是损坏_观战不拿它去做attempt守护() {
        byte[] pb = placement(2).toByteArray();

        assertThat(((Parsed.Corrupt) PlacementRecords.parse(BATTLE, null, pb)).why()).contains("缺 a 字段");
        assertThat(((Parsed.Corrupt) PlacementRecords.parse(BATTLE, new byte[0], pb)).why()).contains("缺 a 字段");
        assertThat(((Parsed.Corrupt) PlacementRecords.parse(BATTLE, ascii("1"), pb)).why()).as("字段是 1、消息里是 2").contains("不符").contains("attempt=2");
        assertThat(PlacementRecords.parse(BATTLE, ascii("02"), pb)).as("不是规范写法也算不一致").isInstanceOf(Parsed.Corrupt.class);
        assertThat(PlacementRecords.parse(BATTLE, ascii("abc"), pb)).isInstanceOf(Parsed.Corrupt.class);
    }

    @Test
    void 连attempt一起核对_pb那三条照样先判_179的口径不看attempt字段() {
        assertThat(((Parsed.Corrupt) PlacementRecords.parse(BATTLE, ascii("1"), null)).why()).contains("缺 pb 字段");
        assertThat(((Parsed.Corrupt) PlacementRecords.parse(BATTLE + 1, ascii("1"), placement(1).toByteArray())).why()).contains("与键不符");
        // 同一条「attempt 字段对不上」的记录：179 的口径照样是好记录（它只用地址），观战的口径是损坏
        byte[] pb = placement(2).toByteArray();
        assertThat(PlacementRecords.parse(BATTLE, pb)).isInstanceOf(Parsed.Ok.class);
        assertThat(PlacementRecords.parse(BATTLE, ascii("1"), pb)).isInstanceOf(Parsed.Corrupt.class);
    }
}
