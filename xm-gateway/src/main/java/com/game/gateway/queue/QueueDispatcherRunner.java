package com.game.gateway.queue;

import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 放行循环的线程与选主锁：单个调度线程每 {@code interval} 调一次 {@link QueueDispatcher#tick()}。选主锁是 Redisson 的
 * {@link RLock}（看门狗续期，进程死掉后约 30 s 过期，别的副本接手）；RLock 归属于拿锁的线程，所以拿锁 / 查锁 / 放锁全在这个线程上。
 * 关闭时先在这个线程上放锁，再停线程。
 */
public final class QueueDispatcherRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QueueDispatcherRunner.class);

    private final ScheduledExecutorService scheduler;
    private final QueueDispatcher dispatcher;

    /** 排队关闭时：不起线程、不碰锁。 */
    public static QueueDispatcherRunner idle() {
        return new QueueDispatcherRunner();
    }

    private QueueDispatcherRunner() {
        this.scheduler = null;
        this.dispatcher = null;
    }

    /** @param dispatcherFactory 用选主实现造出放行循环 */
    public QueueDispatcherRunner(RedissonClient redis, Duration interval,
                                 Function<QueueDispatcher.Leadership, QueueDispatcher> dispatcherFactory) {
        RLock lock = redis.getLock(RedisKeys.loginQueueDispatcherLock());
        this.dispatcher = dispatcherFactory.apply(new QueueDispatcher.Leadership() {
            @Override
            public boolean holdOrAcquire() {
                return lock.isHeldByCurrentThread() || lock.tryLock();
            }

            @Override
            public void release() {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("gateway-queue-dispatcher").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(dispatcher::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.submit(dispatcher::stop).get(3, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("停服时释放排队放行选主锁失败（约 30 s 后自然过期）: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            scheduler.shutdownNow();
        }
    }
}
