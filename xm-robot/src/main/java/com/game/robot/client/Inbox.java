package com.game.robot.client;

import com.game.proto.MessageContent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 一条连接收到的全部下行，按到达顺序记账，并支持「从某个序号起等到满足条件的一条」。
 *
 * <p>应答与推送不分开存：应答靠 {@code (message_id, id)} 在同一本账里对上（{@link GameConnection#call}），
 * 推送按 message_id 过滤。全量保留是为了事后断言「某类消息从没出现过」（例如 134/132/131 不回包）。
 *
 * <p>线程模型：Netty I/O 线程调 {@link #add} / {@link #markClosed}，场景线程调 {@link #await} / {@link #snapshot}；
 * 全部方法以本对象为锁。等待条件在锁内执行，必须是纯计算（不做 I/O）。
 */
public final class Inbox {

    private final List<Received> log = new ArrayList<>();
    private String closedReason;

    /** I/O 线程：记下一条下行并唤醒等待者。 */
    public synchronized Received add(MessageContent content, long receivedNanos) {
        Received received = new Received(log.size(), receivedNanos, content);
        log.add(received);
        notifyAll();
        return received;
    }

    /** 连接已不可用（对端关闭、非法帧、I/O 异常）。只记第一个原因；之后的等待立即返回。 */
    public synchronized void markClosed(String reason) {
        if (closedReason == null) {
            closedReason = reason;
        }
        notifyAll();
    }

    /** 连接关闭的原因；仍然打开时为 null。 */
    public synchronized String closedReason() {
        return closedReason;
    }

    /** 已收到的条数，也是下一条的序号：先取它再发请求，等待时从它起找，就不会漏掉极快到达的应答。 */
    public synchronized int size() {
        return log.size();
    }

    /** 从 {@code fromIndex} 起的全部记录（副本）。 */
    public synchronized List<Received> snapshot(int fromIndex) {
        return List.copyOf(log.subList(Math.min(fromIndex, log.size()), log.size()));
    }

    /**
     * 从 {@code fromIndex} 起找第一条满足 {@code match} 的记录；还没有就等，直到超时或连接关闭。
     *
     * @return 找到的记录；超时或连接已关闭且没有匹配时为空
     */
    public synchronized Optional<Received> await(int fromIndex, Predicate<Received> match, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int scanned = Math.max(0, fromIndex);
        while (true) {
            for (; scanned < log.size(); scanned++) {
                Received candidate = log.get(scanned);
                if (match.test(candidate)) {
                    return Optional.of(candidate);
                }
            }
            if (closedReason != null) {
                return Optional.empty();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Optional.empty();
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }
}
