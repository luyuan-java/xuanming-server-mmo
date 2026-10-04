package com.game.guild.push;

import com.game.discovery.presence.PlayerPushes;
import com.game.proto.MessageContent;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.GuildChangedS2C;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会变更推送 220 NotifyGuildChanged（基线 push.go 的 KafkaGuildNotifier + notify 助手；guild-spec §4.2–§4.5，D7）。
 *
 * <p>四条纪律（push.go:5-15）：
 * <ol>
 *   <li>推送只是「提示客户端去拉一次」：载荷只有 {@code GuildChangedS2C} 的四个字段，不下发快照（推送与响应先后不可控，带状态的晚到推送会把
 *       客户端刷回旧数据）；</li>
 *   <li>只在 MySQL 提交、缓存同步失效之后调用，至多一次：离线不推、gate 找不到会话即丢、发布失败不重试；</li>
 *   <li><b>绝不影响 RPC 结果</b>：失败只记指标与日志，本类任何方法都不抛；</li>
 *   <li>不占 RPC 预算：发布是异步的（{@link PlayerPushes#pushToPlayers} 不阻塞调用线程），整批套独立上限 {@code xm.guild.push-timeout}
 *       （缺省 3 s，从调用时刻起算，<b>不继承</b>请求的 Deadline）。</li>
 * </ol>
 *
 * <p>收件人去重、丢弃 0，保持首次出现的顺序（uniqueNonZero，push.go:209-230）；操作者是否在收件人里由调用方的收件矩阵决定（§4.3），这里不再剔除。
 * 结局按<b>收件人</b>计（{@code xm_guild_pushes_total{kind, outcome}}）：SENT → ok，OFFLINE → offline，GATE_UNREACHABLE → error；
 * 整个 stage 异常（在线目录读失败）→ 全体 session_error；超出推送上限 → 全体 error（D7）。
 *
 * <p>4.5 的 FUNDS_CHANGED / DELIVERY_DONE 只推本人：用 {@link #notifyPlayer}。线程安全。
 */
public final class GuildPushes {

    private static final Logger log = LoggerFactory.getLogger(GuildPushes.class);

    /** 推一条 {@code MessageContent} 给一批玩家（生产 {@code PlayerPushes::pushToPlayers}：按 gate 分组，每个 gate 发一条）。 */
    @FunctionalInterface
    public interface Pusher {
        CompletionStage<Map<Long, PlayerPushes.Outcome>> push(Collection<Long> playerIds, MessageContent content);
    }

    /** 推送结局（标签 {@code outcome}，基线 push.go:47-56）。 */
    public enum PushOutcome {
        /** 已发布到收件人所在、正在订阅的 gate。 */
        OK("ok"),
        /** 收件人不在游戏里（在线目录没有条目）。 */
        OFFLINE("offline"),
        /** 发布了但 gate 不在订阅、或整批超出推送上限。 */
        ERROR("error"),
        /** 在线目录读不出来，整批没法路由（与 error 分开：前者是在线目录 Redis 的问题）。 */
        SESSION_ERROR("session_error");

        private final String label;

        PushOutcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 推送结局的指标出口（{@code GuildMetrics} 实现；必须便宜、不抛）。 */
    @FunctionalInterface
    public interface PushMetrics {
        /** 按收件人计数：{@code kindLabel} 只取 {@link #KIND_LABELS} 里的值。 */
        void pushed(String kindLabel, PushOutcome outcome, int recipients);

        PushMetrics NONE = (kind, outcome, n) -> {
        };
    }

    /** 未知的 kind 一律归到这个标签（基线 changeKindLabel 的 default）。 */
    public static final String OTHER_KIND = "other";

    /** kind 标签的固定集合：13 个有意义的值 + other（基线 push.go:232-269，含 4.5 / 4.6 的四个；指标启动即全部预建）。 */
    public static final List<String> KIND_LABELS = List.of("member_joined", "member_left", "member_kicked", "role_changed",
            "leader_transferred", "disbanded", "application_received", "application_rejected", "funds_changed", "level_up",
            "announcement_changed", "activity_changed", "delivery_done", OTHER_KIND);

    private final Pusher pusher;
    private final PushMetrics metrics;
    private final int messageId;
    private final long timeoutMillis;

    /**
     * @param messageId 220 NotifyGuildChanged 的消息号（取自 {@code MessageIdRegistry}，不写数字）
     * @param timeout   一批推送的上界（{@code xm.guild.push-timeout}）
     */
    public GuildPushes(Pusher pusher, PushMetrics metrics, int messageId, Duration timeout) {
        this.pusher = pusher;
        this.metrics = metrics;
        this.messageId = messageId;
        this.timeoutMillis = timeout.toMillis();
    }

    /**
     * 推一条 {@code GuildChangedS2C{kind, guild_id, actor, target}} 给 {@code recipients}（基线 notify + Notify，push.go:105-155、:278-292）。
     * guild_id 显式传参：解散之后已经没有快照可取。立即返回；永不抛。
     *
     * @param actor  发起者；系统触发为 0
     * @param target 被操作者；没有则 0
     */
    public void notify(GuildChangeKind kind, long guildId, long actor, long target, Collection<Long> recipients) {
        String label = kindLabel(kind);
        List<Long> ids;
        try {
            ids = uniqueNonZero(recipients);
        } catch (RuntimeException e) {
            log.error("[guild] 推送收件人列表无法处理 kind={} guild={}", label, Long.toUnsignedString(guildId), e);
            return;
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            GuildChangedS2C change = GuildChangedS2C.newBuilder().setKind(kind).setGuildId(guildId)
                    .setActorPlayerId(actor).setTargetPlayerId(target).build();
            MessageContent content = MessageContent.newBuilder().setMessageId(messageId)
                    .setSerializedMessage(change.toByteString()).build();
            pusher.push(ids, content).toCompletableFuture()
                    .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .whenComplete((outcomes, error) -> record(label, guildId, ids, outcomes, error));
        } catch (RuntimeException e) {
            // 同步抛出 = 发布没发出去：与「在线目录读失败」同一处置（整批不推），计 session_error
            safeCount(label, PushOutcome.SESSION_ERROR, ids.size());
            log.error("[guild] 推送发不出去 kind={} guild={} recipients={}: {}", label, Long.toUnsignedString(guildId),
                    ids.size(), e.toString());
        }
    }

    /** 单收件人入口（4.5 的 FUNDS_CHANGED / DELIVERY_DONE 只推本人）。 */
    public void notifyPlayer(GuildChangeKind kind, long guildId, long actor, long target, long playerId) {
        notify(kind, guildId, actor, target, List.of(playerId));
    }

    private void record(String label, long guildId, List<Long> ids, Map<Long, PlayerPushes.Outcome> outcomes,
                        Throwable error) {
        try {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                if (cause instanceof TimeoutException) {
                    safeCount(label, PushOutcome.ERROR, ids.size());
                    log.warn("[guild] 推送超过 {} ms 上限 kind={} guild={} recipients={}", timeoutMillis, label,
                            Long.toUnsignedString(guildId), ids.size());
                } else {
                    safeCount(label, PushOutcome.SESSION_ERROR, ids.size());
                    log.error("[guild] 推送读在线目录 / 发布失败，整批不推 kind={} guild={} recipients={}: {}", label,
                            Long.toUnsignedString(guildId), ids.size(), cause.toString());
                }
                return;
            }
            int ok = 0;
            int offline = 0;
            int failed = 0;
            for (long id : ids) {
                PlayerPushes.Outcome outcome = outcomes == null ? null : outcomes.get(id);
                if (outcome == null) {
                    failed++;
                    continue;
                }
                switch (outcome) {
                    case SENT -> ok++;
                    case OFFLINE -> offline++;
                    case GATE_UNREACHABLE -> failed++;
                }
            }
            safeCount(label, PushOutcome.OK, ok);
            safeCount(label, PushOutcome.OFFLINE, offline);
            safeCount(label, PushOutcome.ERROR, failed);
            if (failed > 0) {
                log.warn("[guild] 推送有 {} 人没有送达 gate kind={} guild={}", failed, label, Long.toUnsignedString(guildId));
            }
        } catch (RuntimeException e) {
            log.error("[guild] 记录推送结局失败 kind={}", label, e);
        }
    }

    private void safeCount(String label, PushOutcome outcome, int n) {
        if (n <= 0) {
            return;
        }
        try {
            metrics.pushed(label, outcome, n);
        } catch (RuntimeException e) {
            log.error("[guild] 推送指标出错 kind={} outcome={}", label, outcome.label(), e);
        }
    }

    /**
     * 去重并丢掉 0，保持首次出现的顺序（基线 uniqueNonZero，push.go:209-230）。0 表示「没有这个人」（解散的 target、读不到的帮主）。
     */
    public static List<Long> uniqueNonZero(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id != 0) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    /**
     * kind → 固定的小写标签（基线 changeKindLabel，push.go:232-269）：不用枚举名，proto 改名不会让时间序列断成两条；未知值归 {@code other}。
     */
    public static String kindLabel(GuildChangeKind kind) {
        if (kind == null) {
            return OTHER_KIND;
        }
        return switch (kind) {
            case GUILD_CHANGE_KIND_MEMBER_JOINED -> "member_joined";
            case GUILD_CHANGE_KIND_MEMBER_LEFT -> "member_left";
            case GUILD_CHANGE_KIND_MEMBER_KICKED -> "member_kicked";
            case GUILD_CHANGE_KIND_ROLE_CHANGED -> "role_changed";
            case GUILD_CHANGE_KIND_LEADER_TRANSFERRED -> "leader_transferred";
            case GUILD_CHANGE_KIND_DISBANDED -> "disbanded";
            case GUILD_CHANGE_KIND_APPLICATION_RECEIVED -> "application_received";
            case GUILD_CHANGE_KIND_APPLICATION_REJECTED -> "application_rejected";
            case GUILD_CHANGE_KIND_FUNDS_CHANGED -> "funds_changed";
            case GUILD_CHANGE_KIND_LEVEL_UP -> "level_up";
            case GUILD_CHANGE_KIND_ANNOUNCEMENT_CHANGED -> "announcement_changed";
            case GUILD_CHANGE_KIND_ACTIVITY_CHANGED -> "activity_changed";
            case GUILD_CHANGE_KIND_DELIVERY_DONE -> "delivery_done";
            default -> OTHER_KIND;
        };
    }
}
