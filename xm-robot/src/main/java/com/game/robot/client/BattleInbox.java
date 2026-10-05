package com.game.robot.client;

import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.MessageContent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 一条 battle 直连收到的全部下行，按到达顺序记账（含关闭标记，见 {@link BattleFrame}），支持「从某个序号起等到满足条件的一项」。
 *
 * <p>全量保留：线上顺序（O1–O8、R1–R3）要按序号断言，「某类帧从没出现过」也要事后查。关闭标记只记一次（第一个原因为准），之后不再记任何帧——
 * 与服务端「关闭中的连接不再分发」相对，客户端这边关闭之后的东西也没有意义。
 *
 * <p>线程模型：Netty I/O 线程调 {@code add*} / {@link #markClosed}，场景线程调 {@link #await} / {@link #snapshot}；全部方法以本对象为锁。
 * 等待条件在锁内执行，必须是纯计算。
 */
public final class BattleInbox {

    private final List<BattleFrame> log = new ArrayList<>();
    private String closedBy;

    /** I/O 线程：记一条握手应答。 */
    public synchronized BattleFrame addVerify(BattleTokenVerifyResponse verify, long atNanos) {
        return append(BattleFrame.ofVerify(log.size(), atNanos, verify));
    }

    /** I/O 线程：记一条信封。 */
    public synchronized BattleFrame addContent(MessageContent content, long atNanos) {
        return append(BattleFrame.ofContent(log.size(), atNanos, content));
    }

    /** 连接已不可用：记一个关闭标记（只记第一次），唤醒等待者。 */
    public synchronized void markClosed(String reason, long atNanos) {
        if (closedBy != null) {
            return;
        }
        closedBy = reason;
        log.add(BattleFrame.ofClosed(log.size(), atNanos, reason));
        notifyAll();
    }

    /** 关闭原因；仍打开时为 null。 */
    public synchronized String closedBy() {
        return closedBy;
    }

    /** 已记的条数（含关闭标记），也是下一条的序号：先取它再发请求，等待时从它起找。 */
    public synchronized int size() {
        return log.size();
    }

    /** 从 {@code fromIndex} 起的全部记录（副本）。 */
    public synchronized List<BattleFrame> snapshot(int fromIndex) {
        return List.copyOf(log.subList(Math.min(Math.max(0, fromIndex), log.size()), log.size()));
    }

    /** 从 {@code fromIndex} 起的标签（报告用）。 */
    public synchronized String labelsSince(int fromIndex) {
        return snapshot(fromIndex).stream().map(BattleFrame::label).collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * 从 {@code fromIndex} 起找第一项满足 {@code match} 的记录（关闭标记也参与匹配）；还没有就等，直到超时或连接已关闭。
     *
     * @return 找到的记录；超时、或已关闭且到关闭标记为止都没有匹配时为空
     */
    public synchronized Optional<BattleFrame> await(int fromIndex, Predicate<BattleFrame> match, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int scanned = Math.max(0, fromIndex);
        while (true) {
            for (; scanned < log.size(); scanned++) {
                BattleFrame candidate = log.get(scanned);
                if (match.test(candidate)) {
                    return Optional.of(candidate);
                }
            }
            if (closedBy != null) {
                return Optional.empty();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Optional.empty();
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    /** 等连接关闭，返回从 {@code fromIndex} 起直到关闭标记（含）的全部记录；超时为空。 */
    public Optional<List<BattleFrame>> awaitClosed(int fromIndex, Duration timeout) throws InterruptedException {
        Optional<BattleFrame> closed = await(fromIndex, BattleFrame::isClosed, timeout);
        if (closed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshot(fromIndex));
    }

    private BattleFrame append(BattleFrame frame) {
        if (closedBy != null) {
            return frame;
        }
        log.add(frame);
        notifyAll();
        return frame;
    }
}
