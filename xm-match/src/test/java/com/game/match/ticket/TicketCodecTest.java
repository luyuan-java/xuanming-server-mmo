package com.game.match.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * 票据 HASH 与队列成员的文本形状（{@link TicketCodec}）：全字段解码、缺省值、损坏的 HASH 按故障抛出（绝不折成「没有票」）、
 * 成员串只认规范的无符号十进制、镜像分的解析。
 */
class TicketCodecTest {

    private static final String QUEUE_KEY = "xm:{match}:queue:3:7";

    @Test
    void 全字段解码_数字按无符号读() {
        List<String> flat = List.of("ticket", "9f1c2d3e-0000-4000-8000-000000000001", "mode", "3", "config", "4294967295", "state", "ready",
                "enqueued_at_ms", "1800000000123", "zone_id", "2", "queue_key", QUEUE_KEY, "rating_centi", "162550",
                "team_id", "18446744073709551615", "battle_id", "9223372036854775885", "not_before_ms", "1800000002123");

        Ticket ticket = TicketCodec.decode(flat, 1001).orElseThrow();

        assertThat(ticket).isEqualTo(new Ticket("9f1c2d3e-0000-4000-8000-000000000001", 3, -1, TicketState.READY, 1_800_000_000_123L, 2, QUEUE_KEY,
                162_550, -1L, Long.MIN_VALUE + 77, 1_800_000_002_123L));
    }

    @Test
    void 键不存在是没有票() {
        assertThat(TicketCodec.decode(List.of(), 1001)).isEmpty();
    }

    @Test
    void 可选字段缺失取缺省_评分缺失按1500() {
        Ticket ticket = TicketCodec.decode(List.of("ticket", "t-1", "state", "matched"), 1001).orElseThrow();

        assertThat(ticket).isEqualTo(new Ticket("t-1", 0, 0, TicketState.MATCHED, 0, 0, "", 150_000, 0, 0, 0));
    }

    @Test
    void 状态不认识是UNKNOWN_不认识的字段忽略_数字写坏按0() {
        Ticket ticket = TicketCodec.decode(List.of("ticket", "t-1", "state", "entering", "mode", "x", "config", "-1", "enqueued_at_ms", "soon",
                "zone_id", "", "rating_centi", "high", "future_field", "v"), 1001).orElseThrow();

        assertThat(ticket.state()).isEqualTo(TicketState.UNKNOWN);
        assertThat(ticket.mode()).isZero();
        assertThat(ticket.configId()).isZero();
        assertThat(ticket.enqueuedAtMs()).isZero();
        assertThat(ticket.zoneId()).isZero();
        assertThat(ticket.ratingCenti()).isEqualTo(150_000);
    }

    @Test
    void HASH存在却没有票号_按故障抛出_绝不折成没有票() {
        assertThatThrownBy(() -> TicketCodec.decode(List.of("state", "queued", "mode", "3"), 1001))
                .isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("没有票号").hasMessageContaining("1001");
        assertThatThrownBy(() -> TicketCodec.decode(List.of("ticket", "", "state", "queued"), 1001))
                .isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> TicketCodec.decode(List.of("ticket", "t-1", "state"), 1001)).as("回复不成对")
                .isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> TicketCodec.decode(null, 1001)).isInstanceOf(Deadline.DependencyException.class);
    }

    @Test
    void 成员串是玩家号的无符号十进制_只认规范形() {
        assertThat(TicketCodec.member(1001)).isEqualTo("1001");
        assertThat(TicketCodec.member(Long.MIN_VALUE + 4301)).isEqualTo("9223372036854780109");
        assertThat(TicketCodec.member(-1L)).isEqualTo("18446744073709551615");

        assertThat(TicketCodec.parseMember("1001")).isEqualTo(1001);
        assertThat(TicketCodec.parseMember("9223372036854780109")).isEqualTo(Long.MIN_VALUE + 4301);
        assertThat(TicketCodec.parseMember("18446744073709551615")).isEqualTo(-1L);
        assertThat(TicketCodec.parseMember("18446744073709551616")).as("超出 uint64").isZero();
        assertThat(TicketCodec.parseMember("99999999999999999999")).isZero();
        assertThat(TicketCodec.parseMember("007")).as("前导零不是规范形").isZero();
        assertThat(TicketCodec.parseMember("0")).isZero();
        assertThat(TicketCodec.parseMember("-5")).isZero();
        assertThat(TicketCodec.parseMember("+5")).isZero();
        assertThat(TicketCodec.parseMember("12a")).isZero();
        assertThat(TicketCodec.parseMember(" 12")).isZero();
        assertThat(TicketCodec.parseMember("")).isZero();
        assertThat(TicketCodec.parseMember(null)).isZero();
        assertThat(TicketCodec.parseMember("123456789012345678901")).as("21 位").isZero();
    }

    @Test
    void 成员串往返() {
        for (long playerId : new long[] {1, 1001, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            assertThat(TicketCodec.parseMember(TicketCodec.member(playerId))).isEqualTo(playerId);
        }
    }

    @Test
    void 镜像分_Redis按双精度输出的文本转成centi_缺失与非数字为空() {
        assertThat(TicketCodec.parseScore("150000")).isEqualTo(OptionalLong.of(150_000));
        assertThat(TicketCodec.parseScore("162550")).isEqualTo(OptionalLong.of(162_550));
        assertThat(TicketCodec.parseScore("1.5e+05")).isEqualTo(OptionalLong.of(150_000));
        assertThat(TicketCodec.parseScore("0")).isEqualTo(OptionalLong.of(0));
        assertThat(TicketCodec.parseScore("")).as("镜像里没有这个成员").isEmpty();
        assertThat(TicketCodec.parseScore(null)).isEmpty();
        assertThat(TicketCodec.parseScore("inf")).isEmpty();
        assertThat(TicketCodec.parseScore("-inf")).isEmpty();
        assertThat(TicketCodec.parseScore("NaN")).isEmpty();
        assertThat(TicketCodec.parseScore("Infinity")).isEmpty();
        assertThat(TicketCodec.parseScore("abc")).isEmpty();
    }
}
