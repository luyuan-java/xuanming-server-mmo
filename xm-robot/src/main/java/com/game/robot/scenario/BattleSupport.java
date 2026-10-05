package com.game.robot.scenario;

import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleStartS2C;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.TipInfoMessage;
import com.game.robot.client.BattleDirectConnection;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * battle / battle-edge 两个场景共用的件：大厅上的机器人（等 177 / 143 / 150）、带节拍的直连（直连面按消息号每秒 3 条限频，battle-node-spec §3.5）、
 * 帧序列的判读。只在场景线程上使用。
 */
final class BattleSupport {

    /** 直连面的限频：表里没有 140 / 149 / 162 / 165，四条战斗 RPC 都取缺省「1 s 窗口 3 条」（§3.5 ②）。robot 侧留 100 ms 余量。 */
    static final Duration RATE_WINDOW = Duration.ofMillis(1100);
    static final int RATE_MAX_IN_WINDOW = 3;

    private BattleSupport() {
    }

    static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    /** 已进场的一个机器人（大厅连接）。 */
    static final class LobbyBot {

        final String name;
        final EnteredPlayer player;
        private final BattleIds ids;
        private final Duration timeout;

        LobbyBot(String name, EnteredPlayer player, BattleIds ids, Duration timeout) {
            this.name = name;
            this.player = player;
            this.ids = ids;
            this.timeout = timeout;
        }

        long id() {
            return player.playerId();
        }

        GameConnection connection() {
            return player.connection();
        }

        int mark() {
            return connection().inbox().size();
        }

        /** 从 {@code from} 起等这间房的 177（推送形状）。 */
        Received awaitAssigned(int from, long battleId) throws RobotException {
            return connection().await(from, r -> isLobbyPush(r, ids.battleAssigned(), battleId), timeout)
                    .orElseThrow(() -> new RobotException(name + "：" + timeout.toMillis() + " ms 内大厅没有收到 battle_id=" + uid(battleId)
                            + " 的 177（xm-battle 经 gate 回落的大厅公告；看 xm-battle 的 xm_battle_lobby_push_outcomes_total 与 gate 日志）"
                            + connection().describeSince(from)));
        }

        /** 从 {@code from} 起等这间房的 143。 */
        Received awaitStart(int from, long battleId) throws RobotException {
            return connection().await(from, r -> isLobbyPush(r, ids.battleStart(), battleId), timeout)
                    .orElseThrow(() -> new RobotException(name + "：" + timeout.toMillis() + " ms 内大厅没有收到 battle_id=" + uid(battleId)
                            + " 的 143" + connection().describeSince(from)));
        }

        /** 从 {@code from} 起这间房的某个大厅推送（177 / 143 / 150）在 {@code window} 内是否出现。 */
        Optional<Received> findWithin(int from, int messageId, long battleId, Duration window) throws RobotException {
            return connection().await(from, r -> isLobbyPush(r, messageId, battleId), window);
        }

        /** 这间房的大厅推送（177 / 143 / 150）：推送形状（id = 0）且体里的 battle_id 对上。 */
        boolean isLobbyPush(Received r, int messageId, long battleId) {
            if (r.messageId() != messageId || r.requestId() != 0) {
                return false;
            }
            Long got = battleIdOf(r, ids);
            return got != null && got == battleId;
        }
    }

    /** 大厅推送体里的 battle_id；不认识的号或解析失败为 null。 */
    static Long battleIdOf(Received r, BattleIds ids) {
        if (r.messageId() == ids.battleAssigned()) {
            BattleAssignedS2C m = r.parseOrNull(BattleAssignedS2C.parser());
            return m == null ? null : m.getBattleId();
        }
        if (r.messageId() == ids.battleStart()) {
            BattleStartS2C m = r.parseOrNull(BattleStartS2C.parser());
            return m == null ? null : m.getBattleId();
        }
        if (r.messageId() == ids.battleEnd()) {
            BattleEndS2C m = r.parseOrNull(BattleEndS2C.parser());
            return m == null ? null : m.getBattleId();
        }
        return null;
    }

    /** 大厅上 23 tip 的 id（推送形状）；不是 23 或解析失败为 -1。 */
    static int lobbyTip(Received r, int sendTip) {
        if (r.messageId() != sendTip || r.requestId() != 0) {
            return -1;
        }
        TipInfoMessage tip = r.parseOrNull(TipInfoMessage.parser());
        return tip == null ? -1 : tip.getId();
    }

    /**
     * 一条 battle 直连，发请求前过节拍（同号任意 {@link #RATE_WINDOW} 内至多 {@value #RATE_MAX_IN_WINDOW} 条），免得正常流程撞上直连面的
     * 1008。负面用例（故意撞限频）直接用 {@link #raw()}。
     */
    static final class Direct implements AutoCloseable {

        final String name;
        private final BattleDirectConnection connection;
        private final BattleIds ids;
        private final GuildScenario.Pacer pacer = new GuildScenario.Pacer(Duration.ZERO, RATE_WINDOW, RATE_MAX_IN_WINDOW);

        private Direct(String name, BattleDirectConnection connection, BattleIds ids) {
            this.name = name;
            this.connection = connection;
            this.ids = ids;
        }

        /** 按分配包里的地址建 TCP 连接（不握手）。 */
        static Direct open(RobotClient client, String name, BattleAssignedS2C assigned, BattleIds ids) throws RobotException {
            return open(client, name, assigned.getHost(), assigned.getPort(), ids);
        }

        static Direct open(RobotClient client, String name, String host, int port, BattleIds ids) throws RobotException {
            return new Direct(name, client.connectBattle(host, port), ids);
        }

        BattleDirectConnection raw() {
            return connection;
        }

        int mark() {
            return connection.inbox().size();
        }

        /** 用票握手（参战票成功后自动补拉一条 140，计入节拍）。 */
        BattleDirectConnection.Handshake handshake(BattleAssignedS2C assigned) throws RobotException {
            BattleDirectConnection.Handshake hs = connection.handshake(assigned, ids.getBattleState(),
                    BattleDirectConnection.CONNECT_AND_HANDSHAKE_BUDGET);
            if (hs.stateRequestId() != 0) {
                pacer.record(ids.getBattleState(), System.nanoTime());
            }
            return hs;
        }

        /** 只握手（不补拉）。 */
        BattleTokenVerifyResponse verify(ByteString payload, ByteString signature) throws RobotException {
            return connection.verify(payload, signature, BattleDirectConnection.CONNECT_AND_HANDSHAKE_BUDGET);
        }

        /** 发请求（过节拍），不等应答。 */
        long request(int messageId, Message body) throws RobotException {
            pace(messageId);
            return connection.request(messageId, body);
        }

        /** 等某条请求的应答（含信封错误）。 */
        BattleFrame awaitReply(int from, int messageId, long requestId, Duration timeout) throws RobotException {
            return connection.await(from, f -> f.content() != null && f.messageId() == messageId && f.requestId() == requestId, timeout)
                    .orElseThrow(() -> new RobotException(name + "：" + timeout.toMillis() + " ms 内直连上没有 message_id=" + messageId + " id="
                            + requestId + " 的应答；此后收到 " + connection.inbox().labelsSince(from)));
        }

        /** 发请求、等应答（过节拍）；信封错误照常返回，由调用方判定。 */
        BattleFrame call(int messageId, Message body, Duration timeout) throws RobotException {
            int from = mark();
            long id = request(messageId, body);
            return awaitReply(from, messageId, id, timeout);
        }

        /** 发请求、等应答并解析应答体；信封错误即失败。 */
        <T extends Message> T callParsed(int messageId, Message body, Parser<T> parser, Duration timeout) throws RobotException {
            BattleFrame reply = call(messageId, body, timeout);
            if (reply.isEnvelopeError()) {
                throw new RobotException(name + "：message_id=" + messageId + " 的应答是信封错误 tip=" + reply.envelopeTipId());
            }
            return reply.parse(parser);
        }

        /** 从 {@code from} 起等第一条满足条件的记录。 */
        Optional<BattleFrame> await(int from, Predicate<BattleFrame> match, Duration timeout) throws RobotException {
            return connection.await(from, match, timeout);
        }

        /** 从 {@code from} 起等某号推送。 */
        BattleFrame awaitPush(int from, int messageId, Duration timeout) throws RobotException {
            return connection.await(from, f -> f.isPush(messageId), timeout)
                    .orElseThrow(() -> new RobotException(name + "：" + timeout.toMillis() + " ms 内直连上没有 " + messageId + " 推送；此后收到 "
                            + connection.inbox().labelsSince(from)));
        }

        /** 等连接被关，返回从 {@code from} 起直到关闭标记（含）的全部记录。 */
        List<BattleFrame> untilClosed(int from, Duration timeout) throws RobotException {
            try {
                return connection.inbox().awaitClosed(from, timeout)
                        .orElseThrow(() -> new RobotException(name + "：" + timeout.toMillis() + " ms 内直连没有被服务端关闭；此后收到 "
                                + connection.inbox().labelsSince(from)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RobotException("等待直连关闭被中断", e);
            }
        }

        String labelsSince(int from) {
            return connection.inbox().labelsSince(from);
        }

        List<BattleFrame> since(int from) {
            return connection.inbox().snapshot(from);
        }

        @Override
        public void close() {
            connection.close();
        }

        private void pace(int messageId) throws RobotException {
            long waitNanos = pacer.delayNanos(messageId, System.nanoTime());
            if (waitNanos > 0) {
                sleep(Duration.ofNanos(waitNanos));
            }
            pacer.record(messageId, System.nanoTime());
        }
    }

    /** 帧标签（{@link BattleFrame#label()}）序列。 */
    static List<String> labels(List<BattleFrame> frames) {
        return frames.stream().map(BattleFrame::label).collect(Collectors.toList());
    }

    /** 只有一个关闭标记（没收到任何帧）。 */
    static boolean onlyClosed(List<BattleFrame> frames) {
        return frames.size() == 1 && frames.get(0).isClosed();
    }

    /** 两帧之间的毫秒数。 */
    static long millisBetween(BattleFrame earlier, BattleFrame later) {
        return TimeUnit.NANOSECONDS.toMillis(later.atNanos() - earlier.atNanos());
    }

    static void sleep(Duration duration) throws RobotException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
