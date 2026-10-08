package com.game.battle.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/**
 * 截取对局结果兜底日志 {@value #LOGGER}（match-spec §5.4）并按「回灌」的方式解析每一行：键值对里的 {@code payload} 是完整字节的 Base64，
 * 解出来必须还是原来的 {@code BattleResultEvent}。logger 名在这里按规格写死（不引用被测代码的常量），改名即测试失败。
 * 线程安全；用完 {@link #close()}（摘掉 appender，不影响别的测试）。
 */
public final class ResultFallbackCapture implements AutoCloseable {

    /** 规格定下的兜底 logger 名。 */
    public static final String LOGGER = "xm.battle.result.fallback";

    /** 兜底日志的一行。 */
    public record Line(Level level, String message, Map<String, String> fields) {

        public String reason() {
            return fields.get("reason");
        }

        public String channel() {
            return fields.get("channel");
        }

        public String topic() {
            return fields.get("topic");
        }

        public String key() {
            return fields.get("key");
        }

        /** {@code bytes=} 字段：payload 解码后的字节数。 */
        public int bytes() {
            return Integer.parseInt(fields.get("bytes"));
        }

        /** {@code payload=} 解 Base64 得到的完整字节。 */
        public byte[] payload() {
            return Base64.getDecoder().decode(fields.get("payload"));
        }

        /** 按回灌的方式解回结果事件。 */
        public BattleResultEvent event() {
            try {
                return BattleResultEvent.parseFrom(payload());
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError("兜底行的 payload 解不回 BattleResultEvent: " + message, e);
            }
        }
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger(LOGGER);
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    };

    private ResultFallbackCapture() {
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    /** 开始截取（从这一刻起的兜底行）。 */
    public static ResultFallbackCapture start() {
        return new ResultFallbackCapture();
    }

    /** 到目前为止的兜底行（按写入次序）。 */
    public List<Line> lines() {
        return events.stream().map(ResultFallbackCapture::parse).toList();
    }

    /** 兜底行数。 */
    public int size() {
        return events.size();
    }

    private static Line parse(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        Map<String, String> fields = new LinkedHashMap<>();
        for (String token : message.split(" ")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                // 只按第一个等号切：Base64 的填充也是等号
                fields.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return new Line(event.getLevel(), message, fields);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
