package com.game.data.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.query.TxCursor;
import com.game.data.snapshot.SnapshotCauses;
import org.junit.jupiter.api.Test;

/** 运维请求的公共校验：幂等键、原因 / 备注、无符号号码、请求指纹、游标、快照原因名。 */
class OpsRequestsTest {

    @Test
    void 幂等键_必填_1到64个可见ASCII() {
        assertThat(OpsRequests.idempotencyKey("a-B_9:~")).isEqualTo("a-B_9:~");
        assertThat(OpsRequests.idempotencyKey("x".repeat(64))).hasSize(64);
        for (String bad : new String[] {null, "", "x".repeat(65), "has space", "中文", "tab\t"}) {
            assertThatThrownBy(() -> OpsRequests.idempotencyKey(bad)).as(String.valueOf(bad))
                    .isInstanceOfSatisfying(OpsException.class,
                            e -> assertThat(e.code()).isEqualTo(OpsException.INVALID_REQUEST));
        }
    }

    @Test
    void 原因必填不含控制字符_备注可选_无符号号码() {
        assertThat(OpsRequests.reason("客诉 #12")).isEqualTo("客诉 #12");
        assertThatThrownBy(() -> OpsRequests.reason(" ")).isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> OpsRequests.reason("a\nforged")).isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> OpsRequests.reason("长".repeat(257))).isInstanceOf(OpsException.class);
        assertThat(OpsRequests.text("note", null)).isEmpty();
        assertThat(OpsRequests.u64("p", "18446744073709551615")).isEqualTo(-1L);
        assertThatThrownBy(() -> OpsRequests.u64("p", "-1")).isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> OpsRequests.u64("p", "+1")).isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> OpsRequests.u64("p", "18446744073709551616")).isInstanceOf(OpsException.class);
        assertThat(OpsRequests.u32("c", "0")).isZero();
        assertThat(OpsRequests.u32("c", "4294967295")).isEqualTo(-1);
    }

    @Test
    void 请求指纹只随方法路径与规范化请求变() {
        String a = OpsRequests.requestHash("POST", "/admin/player-snapshots", "player=1");
        assertThat(a).hasSize(64).isEqualTo(OpsRequests.requestHash("POST", "/admin/player-snapshots", "player=1"));
        assertThat(a).isNotEqualTo(OpsRequests.requestHash("POST", "/admin/player-snapshots", "player=2"))
                .isNotEqualTo(OpsRequests.requestHash("POST", "/admin/recalls", "player=1"));
    }

    @Test
    void 游标格式_时间加无符号流水号() {
        TxCursor c = TxCursor.parse("1700:18446744073709551615");
        assertThat(c).isEqualTo(new TxCursor(1700, -1L));
        assertThat(c.format()).isEqualTo("1700:18446744073709551615");
        assertThat(TxCursor.parse(null)).isNull();
        for (String bad : new String[] {"1700", ":5", "5:", "a:b", "1:-2"}) {
            assertThatThrownBy(() -> TxCursor.parse(bad)).as(bad).isInstanceOf(OpsException.class);
        }
    }

    @Test
    void 快照原因名_去前缀_不区分大小写_数值_不认识的给UNKNOWN() {
        assertThat(SnapshotCauses.name(SnapshotCauses.GM_MANUAL)).isEqualTo("GM_MANUAL");
        assertThat(SnapshotCauses.name(1001)).isEqualTo("PRE_ROLLBACK");
        assertThat(SnapshotCauses.name(4242)).isEqualTo("UNKNOWN_4242");
        assertThat(SnapshotCauses.parse("gm_manual")).isEqualTo(6);
        assertThat(SnapshotCauses.parse("SNAPSHOT_LOGOUT")).isEqualTo(2);
        assertThat(SnapshotCauses.parse("1002")).isEqualTo(1002);
        assertThat(SnapshotCauses.parse("CAUSE_UNSPECIFIED")).isEqualTo(-1);
        assertThat(SnapshotCauses.parse("0")).isEqualTo(-1);
        assertThat(SnapshotCauses.parse("nope")).isEqualTo(-1);
        assertThat(SnapshotCauses.POINT_IN_TIME_SOURCES).doesNotContain(SnapshotCauses.PRE_ROLLBACK,
                SnapshotCauses.PRE_GM_EDIT);
    }
}
