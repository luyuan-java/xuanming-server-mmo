package com.game.guild.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.game.discovery.presence.PlayerPushes.Outcome;
import com.game.guild.metrics.GuildMetrics;
import com.game.proto.MessageContent;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.GuildChangedS2C;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * 推送组件（guild-spec §4.2–§4.5；基线 push.go 与 guild_manage_logic_test.go:467-600）：收件人去重丢 0、载荷四个字段、按收件人计结局
 * （SENT → ok、OFFLINE → offline、GATE_UNREACHABLE → error、在线目录失败 → session_error、超时 → error），任何失败都不抛。
 * 各 RPC 的收件人矩阵与「推送在失效之后」在服务层单测里断言（GuildServiceTest / GuildManageServiceTest）。
 */
class GuildPushesTest {

    private static final int NOTIFY = 220;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GuildMetrics metrics = new GuildMetrics(registry);
    private final List<List<Long>> sent = new ArrayList<>();
    private final List<MessageContent> contents = new ArrayList<>();

    private GuildPushes pushes(GuildPushes.Pusher pusher) {
        return new GuildPushes((ids, content) -> {
            sent.add(List.copyOf(ids));
            contents.add(content);
            return pusher.push(ids, content);
        }, metrics, NOTIFY, Duration.ofMillis(200));
    }

    private static CompletionStage<Map<Long, Outcome>> outcomes(Collection<Long> ids, Map<Long, Outcome> byId) {
        Map<Long, Outcome> out = new LinkedHashMap<>();
        for (long id : ids) {
            out.put(id, byId.getOrDefault(id, Outcome.OFFLINE));
        }
        return CompletableFuture.completedFuture(out);
    }

    private double count(String kind, String outcome) {
        return registry.get("xm.guild.pushes").tag("kind", kind).tag("outcome", outcome).counter().count();
    }

    @Test
    void 收件人去重丢0保持首次出现顺序_载荷只有四个字段_消息号220_id为0() throws Exception {
        GuildPushes p = pushes((ids, c) -> outcomes(ids, Map.of()));
        p.notify(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_KICKED, 10, 42, 7, List.of(5L, 0L, 7L, 5L, 8L, 7L));
        assertThat(sent).containsExactly(List.of(5L, 7L, 8L));
        MessageContent content = contents.getFirst();
        assertThat(content.getMessageId()).isEqualTo(NOTIFY);
        assertThat(content.getId()).isZero();
        assertThat(GuildChangedS2C.parseFrom(content.getSerializedMessage())).isEqualTo(GuildChangedS2C.newBuilder()
                .setKind(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_KICKED).setGuildId(10).setActorPlayerId(42)
                .setTargetPlayerId(7).build());
    }

    @Test
    void 没有收件人就不发() {
        GuildPushes p = pushes((ids, c) -> outcomes(ids, Map.of()));
        p.notify(GuildChangeKind.GUILD_CHANGE_KIND_DISBANDED, 10, 42, 0, List.of());
        p.notify(GuildChangeKind.GUILD_CHANGE_KIND_DISBANDED, 10, 42, 0, List.of(0L, 0L));
        p.notify(GuildChangeKind.GUILD_CHANGE_KIND_DISBANDED, 10, 42, 0, null);
        assertThat(sent).isEmpty();
    }

    @Test
    void 按收件人计结局_SENT_ok_OFFLINE_offline_GATE_UNREACHABLE_error() {
        GuildPushes p = pushes((ids, c) -> outcomes(ids, Map.of(1L, Outcome.SENT, 2L, Outcome.SENT,
                3L, Outcome.GATE_UNREACHABLE)));
        p.notify(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_JOINED, 10, 42, 7, List.of(1L, 2L, 3L, 4L));
        assertThat(count("member_joined", "ok")).isEqualTo(2);
        assertThat(count("member_joined", "error")).isEqualTo(1);
        assertThat(count("member_joined", "offline")).isEqualTo(1);
        assertThat(count("member_joined", "session_error")).isZero();
    }

    @Test
    void 在线目录读失败整批计session_error_同步抛出也是() {
        GuildPushes p = pushes((ids, c) -> CompletableFuture.failedFuture(new IllegalStateException("presence down")));
        p.notify(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_LEFT, 10, 42, 42, List.of(1L, 2L));
        assertThat(count("member_left", "session_error")).isEqualTo(2);

        GuildPushes throwing = pushes((ids, c) -> {
            throw new IllegalStateException("redisson shut down");
        });
        assertThatCode(() -> throwing.notify(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_LEFT, 10, 42, 42, List.of(3L)))
                .doesNotThrowAnyException();
        assertThat(count("member_left", "session_error")).isEqualTo(3);
    }

    @Test
    void 超过推送上限整批计error_不抛() throws Exception {
        CompletableFuture<Map<Long, Outcome>> never = new CompletableFuture<>();
        GuildPushes p = pushes((ids, c) -> never);
        assertThatCode(() -> p.notify(GuildChangeKind.GUILD_CHANGE_KIND_ROLE_CHANGED, 10, 42, 7, List.of(1L, 2L, 3L)))
                .doesNotThrowAnyException();
        long until = System.nanoTime() + 5_000_000_000L;
        while (count("role_changed", "error") < 3 && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(count("role_changed", "error")).isEqualTo(3);
    }

    @Test
    void 单收件人入口() {
        GuildPushes p = pushes((ids, c) -> outcomes(ids, Map.of(5L, Outcome.SENT)));
        p.notifyPlayer(GuildChangeKind.GUILD_CHANGE_KIND_FUNDS_CHANGED, 10, 0, 5, 5);
        assertThat(sent).containsExactly(List.of(5L));
        assertThat(count("funds_changed", "ok")).isEqualTo(1);
    }

    @Test
    void kind标签是固定集合_未知归other_全部组合启动即预建() {
        for (GuildChangeKind kind : EnumSet.allOf(GuildChangeKind.class)) {
            assertThat(GuildPushes.KIND_LABELS).contains(GuildPushes.kindLabel(kind));
        }
        assertThat(GuildPushes.kindLabel(GuildChangeKind.GUILD_CHANGE_KIND_UNSPECIFIED)).isEqualTo("other");
        assertThat(GuildPushes.kindLabel(GuildChangeKind.UNRECOGNIZED)).isEqualTo("other");
        assertThat(GuildPushes.KIND_LABELS).hasSize(14).doesNotHaveDuplicates();
        for (String kind : GuildPushes.KIND_LABELS) {
            for (String outcome : List.of("ok", "offline", "error", "session_error")) {
                assertThat(count(kind, outcome)).isZero();
            }
        }
    }

    @Test
    void 指标出口抛异常也不影响推送() {
        GuildPushes p = new GuildPushes((ids, c) -> outcomes(ids, Map.of()), (kind, outcome, n) -> {
            throw new IllegalStateException("metrics broken");
        }, NOTIFY, Duration.ofSeconds(1));
        assertThatCode(() -> p.notify(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_LEFT, 10, 42, 42, List.of(1L)))
                .doesNotThrowAnyException();
    }
}
