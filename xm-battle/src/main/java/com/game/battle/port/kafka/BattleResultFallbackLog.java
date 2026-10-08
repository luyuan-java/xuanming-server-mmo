package com.game.battle.port.kafka;

import com.game.audit.BattleResultTopics;
import com.game.battle.port.BattleResultSink.Channel;
import com.game.proto.contracts.kafka.BattleResultEvent;
import java.util.Base64;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对局结果的兜底日志 {@value #LOGGER}（match-spec §5.4）：没能由 Kafka 确认的每条结果事件在这里留下完整一行——键值对，
 * {@code payload} 是 {@code BattleResultEvent} <b>完整字节</b>的 Base64（标准字母表、不换行）。回灌 = 解出 {@code payload}，
 * 以 {@code key} 为消息 key 发到 {@code topic}；消费方按 battle_id 幂等，重复回灌无害。任意线程可调，不抛异常。
 *
 * <p>一行的字段：{@code reason}（为什么没发出去，见 {@link Reason}）、{@code channel}（plain / activity）、{@code topic}、{@code key}
 * （= battle_id 的无符号十进制）、几项便于人工筛查的摘要（模式、配置、胜负、回合数、结束时刻）、{@code bytes} 与 {@code payload}。
 */
final class BattleResultFallbackLog {

    static final String LOGGER = "xm.battle.result.fallback";

    private static final Logger log = LoggerFactory.getLogger(LOGGER);

    /** 一条结果事件没能由 Kafka 确认的原因（只进日志；指标只分 {@code not_verified} 与 {@code fallback}）。 */
    enum Reason {
        /** {@code battle-result-out} 的队列满了。 */
        QUEUE_FULL,
        /** topic 还没核对通过（Kafka 不可达、分区契约不符、生产者进入致命状态后尚未重建）。 */
        NOT_VERIFIED,
        /** {@code producer.send} 同步抛出（元数据 / 缓冲超过 {@code max.block.ms}、生产者已不可用……）。 */
        SEND_ERROR,
        /** 已交给生产者，但投递失败（重试用尽、超过 {@code delivery.timeout.ms}、生产者被关闭时仍未确认）。 */
        DELIVERY_FAILED,
        /** 停服：预算内没发完的，以及关闭之后才交进来的。 */
        SHUTDOWN_DROPPED;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    void lost(Reason reason, Channel channel, String topic, BattleResultEvent event) {
        String payload;
        int size;
        try {
            byte[] bytes = event.toByteArray();
            size = bytes.length;
            payload = Base64.getEncoder().encodeToString(bytes);
        } catch (RuntimeException e) {
            // 事件对象不可变、proto3 没有必填字段，序列化不会失败；万一失败也要留下这一行（摘要仍在）
            size = -1;
            payload = "<序列化失败: " + e + ">";
        }
        log.warn("result reason={} channel={} topic={} key={} match_mode={} config={} outcome={} winner_team={} rounds={} "
                        + "finished_at_ms={} bytes={} payload={}",
                reason.label(), channel == Channel.ACTIVITY ? "activity" : "plain", topic, BattleResultTopics.key(event.getBattleId()),
                Integer.toUnsignedString(event.getMatchMode()), Integer.toUnsignedString(event.getBattleConfigId()),
                event.getOutcomeValue(), Integer.toUnsignedString(event.getWinnerTeamIndex()),
                Integer.toUnsignedString(event.getTotalRounds()), Long.toUnsignedString(event.getFinishedAtMs()), size, payload);
    }
}
