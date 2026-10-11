package com.game.scene.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * 收集一个类的日志（连 DEBUG 一起收）：用 try-with-resources 包住被测的那一段，关闭时摘掉收集器、恢复原级别。
 *
 * <p>用途：有些分支只体现在日志上。典型是投递回逻辑线程被拒时「丢弃并记日志」——那段代码跑在 {@code CompletableFuture.whenComplete}
 * 的回调里，回调里抛出的任何东西都被 CompletableFuture 吞成依赖 future 的异常完成，完成 future 的一方与发起调用的一方都看不到，
 * 只能从日志确认走的是丢弃分支（拒绝异常被接住）。级别临时调到 DEBUG：同一 JVM 里别的用例（Spring Boot 启动）可能已把根级别设成 INFO。
 *
 * <p>别的线程上记的日志也收得到；读之前先让那些线程跑完（join / 等线程池终止），这里只做同步拷贝。
 */
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final Level levelBefore;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public CapturedLogs(Class<?> owner) {
        this.logger = (Logger) LoggerFactory.getLogger(owner);
        this.levelBefore = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    /** 到目前为止收到的、格式化后含 {@code fragment} 的日志条数。 */
    public long count(String fragment) {
        // ListAppender 在自己的锁里追加（AppenderBase.doAppend 是 synchronized），拷贝时取同一把锁
        synchronized (appender) {
            return appender.list.stream().filter(event -> event.getFormattedMessage().contains(fragment)).count();
        }
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(levelBefore);
        appender.stop();
    }
}
