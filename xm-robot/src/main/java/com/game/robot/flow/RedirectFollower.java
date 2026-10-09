package com.game.robot.flow;

import com.game.proto.ClientTokenVerifyResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.GateEndpoint;
import com.game.robot.client.RedirectTarget;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 跟随 124 RedirectToGate 的客户端半边（批次 5.4，zone-travel-spec §11.11；基线 {@code robot/pkg/redirect.go} 的 {@code FollowRedirect}）：
 * 本地校验目标 → 环路熔断 → 连目标 gate → 票据<b>原字节</b>握手 → 关旧连接。它只把 TCP 搬过去：票据只认证那条连接、不转移登录会话，
 * 握手之后调用方还要在新连接上严格重登（{@link PlayerFlow#reenter}：48 → 26 → 79，绝不建角）。
 *
 * <p><b>一个实例对应一个登录会话</b>（一名玩家的一段连续在线）：跳数记在实例上，至多 {@value #MAX_HOPS} 跳，再多就拒绝——
 * 两个区的归属映射互相指的时候没有这道熔断就是不停重连的死循环（基线 {@code MaxRedirectHops}）。
 *
 * <p><b>何时关旧连接</b>有两种次序（{@link CloseOld}），服务端两种都要支持，走的路径不同（旧会话是在新连接登录之前还是之后收场）：
 * <ul>
 *   <li>{@link CloseOld#AFTER_VERIFY}（缺省）：握手<b>成功之后</b>才关。握手失败（连不上、被拒、超时）时旧连接原样保留。可以带一个延迟：
 *       {@code follow} 在握手成功后立即返回，旧连接留到延迟到点（或调用方 {@link Followed#closeOldNow()}）才关——这段时间里两条连接
 *       同时开着，旧连接已被服务端置为「已重定向」，发上去的请求不该有回包；</li>
 *   <li>{@link CloseOld#BEFORE_VERIFY}：连上新 gate 之后、<b>发握手之前</b>就关旧连接。真实客户端走的是这一种（Unity 在打开新连接后、
 *       验票前就重置旧连接；Go robot 的 {@code SwapConn} 同）。握手失败时旧连接已经没了，这次会话就此作废。</li>
 * </ul>
 * 与基线的差别：Java 连不上会在建连超时内直接抛，不需要基线那一步 TCP 探测；目标连不上时两种次序下旧连接都还在。
 *
 * <p>线程：场景线程调用；{@link #follow} 互斥。延迟关旧连接用一条守护线程，不占 Netty 的 I/O 线程。
 */
public final class RedirectFollower {

    /** 一个登录会话允许跟随的重定向次数上限（同基线）。 */
    public static final int MAX_HOPS = 3;

    /** 关旧连接的时机。 */
    public enum CloseOld {
        /** 握手成功之后关（可带延迟）；握手失败时旧连接保留。 */
        AFTER_VERIFY,
        /** 连上新 gate 之后、发握手之前关（真实客户端的次序）。 */
        BEFORE_VERIFY
    }

    private final RobotClient client;
    private final int maxHops;
    private final LongSupplier epochSeconds;
    private int hops;

    /** @param client 建连用（I/O 线程、建连与握手超时、消息号）；连哪个区的 gate 由 124 的地址决定，与它的区号无关 */
    public RedirectFollower(RobotClient client) {
        this(client, MAX_HOPS, () -> System.currentTimeMillis() / 1000);
    }

    /** 单测：可调跳数上限、可拨时钟（判票据到期用的 Unix 秒）。 */
    RedirectFollower(RobotClient client, int maxHops, LongSupplier epochSeconds) {
        this.client = client;
        this.maxHops = maxHops;
        this.epochSeconds = epochSeconds;
    }

    /** 已经跟随的跳数（成功与否都算：连过去就算一跳；本地校验或熔断拒掉的不算）。 */
    public synchronized int hops() {
        return hops;
    }

    /** 按缺省次序跟随：握手成功之后立即关旧连接。 */
    public Followed follow(GameConnection old, RedirectTarget target) throws RobotException {
        return follow(old, target, CloseOld.AFTER_VERIFY, Duration.ZERO);
    }

    /**
     * 跟随一条 124。
     *
     * @param old        收到 124 的那条连接
     * @param target     124 的内容（{@link RedirectTarget#parse}）
     * @param closeOld   关旧连接的时机
     * @param closeDelay 只对 {@link CloseOld#AFTER_VERIFY} 有意义：握手成功之后再过多久关旧连接；0 = 握手成功即关（返回时已关）。
     *                   大于 0 时本方法不等它，见 {@link Followed#awaitOldClosed} / {@link Followed#closeOldNow}
     * @return 新连接（已握手、未登录）与这一跳的记录
     * @throws RobotException 目标本地校验不过、跳数到上限（这两种不碰网络，旧连接不动）；连不上目标 gate（旧连接不动）；
     *                        握手被拒 / 超时 / 连接被关（新连接已关；旧连接 AFTER_VERIFY 下保留、BEFORE_VERIFY 下已关）
     */
    public synchronized Followed follow(GameConnection old, RedirectTarget target, CloseOld closeOld, Duration closeDelay)
            throws RobotException {
        if (closeDelay.isNegative()) {
            throw new IllegalArgumentException("关旧连接的延迟不能为负：" + closeDelay);
        }
        if (closeOld == CloseOld.BEFORE_VERIFY && !closeDelay.isZero()) {
            throw new IllegalArgumentException("BEFORE_VERIFY 在握手之前就关旧连接，不带延迟：" + closeDelay);
        }
        Optional<String> problem = target.problem(epochSeconds.getAsLong());
        if (problem.isPresent()) {
            throw new RobotException("124 RedirectToGate 的内容不可用，不跟随：" + problem.get() + "（" + target + "）");
        }
        if (hops >= maxHops) {
            throw new RobotException("这个登录会话已经跟随了 " + hops + " 次重定向（上限 " + maxHops + "），拒绝再跟随到 "
                    + target.endpoint() + "：多半是两个区的归属映射互相指");
        }
        hops++;
        int hop = hops;
        GateEndpoint gate = target.endpoint();

        // 连不上直接抛（建连超时内）：这时旧连接还没动
        GameConnection fresh;
        try {
            fresh = client.openRaw(target.host(), target.port());
        } catch (RobotException e) {
            throw new RobotException("跟随 124（第 " + hop + " 跳）：目标 gate " + gate + " 连不上——" + e.getMessage(), e);
        }
        OldConnection oldConnection = new OldConnection(old);
        try {
            if (closeOld == CloseOld.BEFORE_VERIFY) {
                oldConnection.closeNow();
            }
            ClientTokenVerifyResponse response;
            try {
                response = fresh.tryVerifyToken(target.tokenPayload(), target.tokenSignature(), client.handshakeTimeout());
            } catch (RobotException e) {
                throw new RobotException("跟随 124（第 " + hop + " 跳）：在目标 gate " + gate + " 上握手没有结果——" + e.getMessage()
                        + oldState(closeOld), e);
            }
            if (!response.getSuccess()) {
                throw new RobotException("跟随 124（第 " + hop + " 跳）：目标 gate " + gate + " 拒绝了重定向票据：success=false error="
                        + response.getError() + oldState(closeOld));
            }
        } catch (RobotException | RuntimeException e) {
            fresh.close();
            throw e;
        }
        long verifiedNanos = System.nanoTime();
        if (closeOld == CloseOld.AFTER_VERIFY) {
            if (closeDelay.isZero()) {
                oldConnection.closeNow();
            } else {
                oldConnection.closeAfter(closeDelay);
            }
        }
        return new Followed(fresh, target, hop, closeOld, verifiedNanos, oldConnection);
    }

    private static String oldState(CloseOld closeOld) {
        return closeOld == CloseOld.AFTER_VERIFY ? "；旧连接保持打开" : "；旧连接已按 BEFORE_VERIFY 关闭，这次会话作废";
    }

    /** 旧连接的收场：只关一次，记下关完的时刻。 */
    private static final class OldConnection {

        private final GameConnection connection;
        private final CountDownLatch closed = new CountDownLatch(1);
        /** 提前叫醒延迟线程。 */
        private final CountDownLatch hurry = new CountDownLatch(1);
        private boolean done;
        private volatile long closedNanos;

        OldConnection(GameConnection connection) {
            this.connection = connection;
        }

        /** 幂等；返回时旧连接已关。两个线程同时调时后到的等先到的关完。 */
        synchronized void closeNow() {
            if (!done) {
                done = true;
                connection.close();
                closedNanos = System.nanoTime();
                closed.countDown();
            }
            hurry.countDown();
        }

        void closeAfter(Duration delay) {
            Thread timer = new Thread(() -> {
                try {
                    hurry.await(delay.toNanos(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                closeNow();
            }, "xm-robot-close-old");
            timer.setDaemon(true);
            timer.start();
        }
    }

    /**
     * 跟随一跳的结果。先后关系用到达 / 完成时刻（{@link System#nanoTime()}，与 {@code Received.receivedNanos} 同一把钟）比较，
     * 不要按「隔了多少毫秒」判。
     */
    public static final class Followed {

        private final GameConnection connection;
        private final RedirectTarget target;
        private final int hop;
        private final CloseOld closeOld;
        private final long verifiedNanos;
        private final OldConnection old;

        private Followed(GameConnection connection, RedirectTarget target, int hop, CloseOld closeOld, long verifiedNanos,
                         OldConnection old) {
            this.connection = connection;
            this.target = target;
            this.hop = hop;
            this.closeOld = closeOld;
            this.verifiedNanos = verifiedNanos;
            this.old = old;
        }

        /** 目标 gate 上的新连接：已握手、未登录。调用方负责关闭。 */
        public GameConnection connection() {
            return connection;
        }

        /** 这一跳跟随的 124。 */
        public RedirectTarget target() {
            return target;
        }

        /** 新连接连的 gate 端点（= 124 的目标）。 */
        public GateEndpoint gate() {
            return connection.endpoint();
        }

        /** 这是本登录会话的第几跳（从 1 起）。 */
        public int hop() {
            return hop;
        }

        /** 这一跳用的关旧连接次序。 */
        public CloseOld closeOld() {
            return closeOld;
        }

        /** 本端看到握手成功的时刻。 */
        public long verifiedNanos() {
            return verifiedNanos;
        }

        /** 旧连接是否已由本端关闭。带延迟的 AFTER_VERIFY 在到点之前是 false。 */
        public boolean oldClosed() {
            return old.closed.getCount() == 0;
        }

        /**
         * 本端关完旧连接的时刻；只在 {@link #oldClosed()} 为 true 时有意义。AFTER_VERIFY 下它晚于 {@link #verifiedNanos()}，
         * BEFORE_VERIFY 下早于。
         */
        public long oldClosedNanos() {
            return old.closedNanos;
        }

        /** 等旧连接关完（带延迟的 AFTER_VERIFY 用）。 */
        public boolean awaitOldClosed(Duration timeout) throws RobotException {
            try {
                return old.closed.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RobotException("等旧连接关闭被中断", e);
            }
        }

        /** 不等延迟到点，现在就关旧连接。幂等；返回时已关。 */
        public void closeOldNow() {
            old.closeNow();
        }
    }
}
