package com.game.battle.edge;

import com.game.battle.BattleIdentity;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.metrics.BattleMetrics.HandshakeResult;
import com.game.battle.metrics.BattleMetrics.RequestResult;
import com.game.battle.protocol.BattleFrames;
import com.game.battle.protocol.BattleMessageIds.Upstream;
import com.game.battle.room.BattleRoomService;
import com.game.common.token.BattleTickets;
import com.game.net.client.ClientFrameException;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.ClientRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.table.CommonErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一条客户端直连的 handler（每连接一个实例，持有一个 {@link DirectSession}；基线 {@code BattleClientEdge} 的连接回调与分发，
 * {@code edge.cpp:141-528}；battle-node-spec §3、§7.4）。全部回调都在连接所属的 EventLoop 上——它就是 battle 逻辑线程（§7.3、Q16），
 * 所以这里直接调 {@link BattleRoomService}，不跨线程。
 *
 * <p><b>握手之前的闸</b>（都不回包）：G1 并发上限（{@link #channelActive}）、G3 握手期限、G4 握手前发 {@code ClientRequest} 立即关且不计非法包；
 * G4′ 合法但不收的类型名由解码器立即关（§11 N9）。Java 没有 G2：密钥任何模式必填，启动门禁已挡（§11 N5）。
 *
 * <p><b>握手判定</b>（§3.4，任一步失败回 {@code {success = false, error}} → FIN → 0.1 s 强关，R4）：
 * 0 已验证 → 回旧 battle_id（B1，不看新票）；2 验签（原字节）；3 解析 payload；4 字段判定（最便宜的先拒）；5 房间挂接。
 * 成功：置为已验证 → 写应答 → 只对观众推 161（{@code onDirectVerified}，R1 / O2）。
 *
 * <p><b>已验证连接的上行闸</b>（§3.5，顺序就是语义）：体积 1010 → 限频 1008 → 白名单 1005 → 体解析 1005，各计非法包一次，达阈值强关；
 * 过闸后交给房间，处理器<b>返回之后</b>才写这次请求的应答（R2）——处理器里推出的 139 / 150 / 166 先于应答，房间在处理器里发起的优雅关闭
 * 排在应答之后（R3）。身份只来自票据：player_id 用会话里绑定的，请求体里没有、也不信任。
 */
final class BattleEdgeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BattleEdgeHandler.class);

    /** 单条 {@code ClientRequest} 整条序列化后的上限（基线 {@code kMaxClientRequestBytes}，{@code edge.h:53}；与 gate 同口径）。 */
    static final int MAX_CLIENT_REQUEST_BYTES = 1024;
    /** 握手被拒后的强关兜底（基线 {@code forceCloseWithDelay(0.1)}，{@code edge.cpp:273}）。 */
    static final long HANDSHAKE_REJECT_FORCE_CLOSE_MS = 100;

    /** 信封错误：白名单外的号、请求体解析失败（基线 {@code kInvalidParameter}）。 */
    static final int TIP_INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    /** 信封错误：按消息号限频（基线 {@code MessageLimiter::CanSend} 的 {@code kRateLimitExceeded}）。 */
    static final int TIP_RATE_LIMIT_EXCEEDED = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;
    /** 信封错误：整条超过 1024 B（基线 {@code kMessageSizeExceeded}）。 */
    static final int TIP_MESSAGE_SIZE_EXCEEDED = CommonErrorTip.common_error.kMessageSizeExceeded_VALUE;

    private final BattleEdgeServer edge;
    private final BattleMetrics metrics;
    private final BattleRoomService rooms;
    /** null = 还没建连，或被并发上限拒掉（从未分配会话）。 */
    private DirectSession session;

    BattleEdgeHandler(BattleEdgeServer edge) {
        this.edge = edge;
        this.metrics = edge.dependencies().metrics();
        this.rooms = edge.dependencies().rooms();
    }

    /**
     * 入站字节是否还该交给解码器、解码器是否还解下一帧（{@link EdgeInboundGuard}、xm-net {@code ClientFrameDecoder} 的
     * keepDecoding）：会话存在且不在关闭中。
     * 房间发起、还没生效的优雅关闭不算关闭中（同一次读里后面的帧照常分发，见 {@link DirectSession} 类注释）。
     */
    boolean acceptsInbound() {
        DirectSession s = session;
        return s != null && s.acceptsFrames();
    }

    /** 一次读到的字节已经解码、分发完（{@link EdgeInboundGuard}）：房间在这次读里发起的优雅关闭从这里生效，下一次读到的字节作废。 */
    void afterInboundRead() {
        DirectSession s = session;
        if (s != null) {
            s.applyRequestedClose();
        }
    }

    /** 本连接的会话（测试用；被并发上限拒掉的连接为 null）。 */
    DirectSession session() {
        return session;
    }

    // ---------------------------------------------------------------- 连接生命周期

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        // 闸 G1：并发上限。必须是第一道：在分配会话之前拒掉，否则限流本身先付出它想省的内存（edge.cpp:157-166）
        if (!edge.tryAdmit()) {
            edge.rejectLog().record(EdgeRejectReason.AT_CAPACITY, DirectSession.describe(ch.remoteAddress()));
            metrics.disconnect(Disconnect.AT_CAPACITY);
            ctx.close();
            return;
        }
        DirectSession s = new DirectSession(ch, metrics);
        session = s;
        edge.register(s);
        // 闸 G3：握手期限。到点时会话仍是 PENDING 才关；已验证 / 已关闭的什么也不做（edge.cpp:187-200）
        long timeoutNanos = edge.dependencies().properties().handshakeTimeout().toNanos();
        s.armHandshakeTimer(ch.eventLoop().schedule(() -> onHandshakeTimeout(s), timeoutNanos, TimeUnit.NANOSECONDS));
        edge.sampleAccepted(s);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        DirectSession s = session;
        if (s != null) {
            try {
                if (s.wasVerified()) {
                    // 只摘「仍指向本连接」的直连槽：旧连接迟到的断开不能把重连后的新连接摘掉（R7，房间按引用比较）
                    rooms.detachDirect(s.battleId(), s.playerId(), s);
                    log.info("battle 直连断开 battle_id={} player_id={} role={} peer={} reason={} remaining={}",
                            Long.toUnsignedString(s.battleId()), Long.toUnsignedString(s.playerId()), s.role(), s.peer(),
                            s.disconnectReason() == null ? "peer" : s.disconnectReason().name().toLowerCase(Locale.ROOT),
                            Math.max(0, edge.connectionCount() - 1));
                }
            } catch (RuntimeException e) {
                log.error("battle 直连断开时摘除直连槽失败 battle_id={} player_id={}",
                        Long.toUnsignedString(s.battleId()), Long.toUnsignedString(s.playerId()), e);
            } finally {
                s.onInactive();
                edge.unregister(s);
            }
        }
        ctx.fireChannelInactive();
    }

    /** 输出缓冲越过高水位（2 MiB）= 客户端收不动：强关，客户端凭票重连后用 140 补拉全量（基线 {@code edge.cpp:20-30}）。 */
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        if (!ch.isWritable() && ch.isOpen()) {
            DirectSession s = session;
            edge.rejectLog().record(EdgeRejectReason.WRITE_BUFFER_FULL, s == null ? DirectSession.describe(ch.remoteAddress()) : s.peer());
            if (s != null) {
                s.forceClose(Disconnect.WRITE_BUFFER_FULL);
            } else {
                ctx.close();
            }
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        DirectSession s = session;
        String peer = s == null ? DirectSession.describe(ctx.channel().remoteAddress()) : s.peer();
        if (cause instanceof IOException) {
            log.debug("battle 直连 I/O 异常，断开 peer={}", peer, cause);
        } else {
            // 房间处理器抛异常是程序缺陷：fail-closed 断开这条连接（客户端凭票重连后用 140 补拉），不回包
            log.error("battle 直连处理异常，断开 peer={} battle_id={} player_id={}", peer,
                    s == null ? "-" : Long.toUnsignedString(s.battleId()), s == null ? "-" : Long.toUnsignedString(s.playerId()), cause);
        }
        if (s != null) {
            s.forceClose(null);
        } else {
            ctx.close();
        }
    }

    /** 解码器拒绝了一帧（长度 / 校验和 / 类型名 / 体解析）；解码器随后关闭连接，不回包。 */
    void onInvalidFrame(Channel ch, ClientFrameException e) {
        metrics.invalidFrame(e.reason());
        DirectSession s = session;
        String peer = s == null ? DirectSession.describe(ch.remoteAddress()) : s.peer();
        edge.rejectLog().record(e.reason() == ClientFrameException.Reason.UNKNOWN_TYPE
                ? EdgeRejectReason.UNKNOWN_FRAME : EdgeRejectReason.INVALID_FRAME, peer);
        if (s != null) {
            s.beginClosing(Disconnect.INVALID_FRAME);
        } else {
            metrics.disconnect(Disconnect.INVALID_FRAME);
        }
    }

    private void onHandshakeTimeout(DirectSession s) {
        Channel ch = s.channelOrNull();
        if (s.state() != DirectSession.State.PENDING || ch == null || !ch.isActive()) {
            return;
        }
        edge.rejectLog().record(EdgeRejectReason.HANDSHAKE_TIMEOUT, s.peer());
        s.forceClose(Disconnect.HANDSHAKE_TIMEOUT);
    }

    // ---------------------------------------------------------------- 上行分发

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            DirectSession s = session;
            // 关闭中 / 已断：不再分发任何帧（基线 codec.cpp:158-162、:180-185：同步 shutdown / forceClose 之后同一批 pipeline 的帧全部作废）。
            // 房间发起的优雅关闭还没生效时照常分发（基线只 queueInLoop，本次读里仍是 connected，见 DirectSession 类注释）
            if (s == null || !s.acceptsFrames() || !ctx.channel().isActive()) {
                return;
            }
            if (msg instanceof BattleTokenVerifyRequest verify) {
                onVerify(s, verify);
            } else if (msg instanceof ClientRequest request) {
                onRequest(s, request);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    // ---------------------------------------------------------------- 握手（§3.4）

    private void onVerify(DirectSession s, BattleTokenVerifyRequest request) {
        if (s.state() == DirectSession.State.VERIFIED) {
            // 第 0 步：重复握手幂等（B1）：回 success 与已绑定的 battle_id，新票一个字节都不看、不重新绑定
            metrics.handshake(HandshakeResult.REPEAT);
            s.writeDirect(BattleFrames.verifyAccepted(s.battleId()));
            return;
        }
        EdgeDependencies deps = edge.dependencies();
        // 第 2 步：用收到的原始 payload 字节验签（不能「解析后重新序列化」），通过之后才解析
        ByteString payloadBytes = request.getPayload();
        if (!deps.tickets().signatureMatches(payloadBytes, request.getSignature())) {
            rejectHandshake(s, EdgeRejectReason.TICKET_HMAC_MISMATCH, HandshakeResult.TICKET_HMAC_MISMATCH,
                    BattleFrames.REJECT_INVALID_SIGNATURE);
            return;
        }
        // 第 3 步
        BattleTicketPayload payload;
        try {
            payload = BattleTicketPayload.parseFrom(payloadBytes);
        } catch (InvalidProtocolBufferException e) {
            rejectHandshake(s, EdgeRejectReason.TICKET_PAYLOAD_PARSE_FAILED, HandshakeResult.TICKET_PAYLOAD_PARSE_FAILED,
                    BattleFrames.REJECT_MALFORMED_PAYLOAD);
            return;
        }
        // 第 4 步：字段判定（身份 → 节点 → 实例 → 期限 → 角色）
        BattleIdentity identity = deps.identity();
        BattleTickets.Verdict verdict = BattleTickets.classify(payload, identity.nodeId(), identity.instanceId(), deps.clock().epochMillis());
        if (verdict != BattleTickets.Verdict.OK) {
            rejectHandshake(s, EdgeRejectReason.of(verdict), HandshakeResult.of(verdict), verdict.clientError());
            return;
        }
        // 第 5 步：房间存在、且该玩家按票上的角色在对应名单上；重连时房间顶替旧连接（R7）。挂接期间会话仍是 PENDING，房间写不出帧（R1）
        long battleId = payload.getBattleId();
        long playerId = payload.getPlayerId();
        OptionalInt gateSession = rooms.attachDirect(battleId, playerId, payload.getRole(), s);
        if (gateSession.isEmpty()) {
            rejectHandshake(s, EdgeRejectReason.TICKET_NOT_IN_ROSTER, HandshakeResult.TICKET_NOT_IN_ROSTER,
                    BattleFrames.REJECT_NOT_IN_ROSTER);
            return;
        }
        s.markVerified(battleId, playerId, payload.getRole(), Integer.toUnsignedLong(gateSession.getAsInt()));
        metrics.handshake(HandshakeResult.OK);
        s.writeDirect(BattleFrames.verifyAccepted(battleId));
        log.info("battle 直连握手成功 battle_id={} player_id={} role={} gate_session={} peer={}",
                Long.toUnsignedString(battleId), Long.toUnsignedString(playerId), payload.getRole(),
                Integer.toUnsignedString(gateSession.getAsInt()), s.peer());
        // 必须在握手应答之后：客户端握手读到的第一个包必须是应答；只对观众推 161 首帧（edge.cpp:358-361）
        rooms.onDirectVerified(battleId, playerId, payload.getRole());
    }

    /** 握手被拒（R4）：回 {@code {success = false, error}}（battle_id 不填）→ 输出排空后 FIN → 0.1 s 强关兜底。 */
    private void rejectHandshake(DirectSession s, EdgeRejectReason reason, HandshakeResult result, String clientError) {
        edge.rejectLog().record(reason, s.peer());
        metrics.handshake(result);
        s.writeDirect(BattleFrames.verifyRejected(clientError));
        s.gracefulClose(Disconnect.HANDSHAKE_REJECTED, HANDSHAKE_REJECT_FORCE_CLOSE_MS);
    }

    // ---------------------------------------------------------------- 业务请求（§3.5）

    private void onRequest(DirectSession s, ClientRequest request) {
        if (s.state() != DirectSession.State.VERIFIED) {
            // 闸 G4：握手前一切业务消息都是不可信来源，直接关（不回应答，别给探测者放大器；不计非法包，edge.cpp:402-408）
            edge.rejectLog().record(EdgeRejectReason.REQUEST_BEFORE_VERIFY, s.peer());
            s.forceClose(Disconnect.REQUEST_BEFORE_VERIFY);
            return;
        }
        EdgeDependencies deps = edge.dependencies();
        int messageId = request.getMessageId();
        Upstream upstream = deps.messageIds().upstream(messageId).orElse(null);
        // ① 体积（edge.cpp:410-416）
        if (request.getSerializedSize() > MAX_CLIENT_REQUEST_BYTES) {
            rejectRequest(s, request, upstream, TIP_MESSAGE_SIZE_EXCEEDED, RequestResult.OVERSIZED, EdgeRejectReason.OVERSIZED);
            return;
        }
        // ② 按消息号限频：白名单外的号也计（B4），被拒的不占额度（edge.cpp:418-425）
        if (!s.rateLimiter().tryAcquire(messageId, deps.messageLimits().limitOf(messageId), edge.nanoTime())) {
            rejectRequest(s, request, upstream, TIP_RATE_LIMIT_EXCEEDED, RequestResult.RATE_LIMITED, EdgeRejectReason.RATE_LIMITED);
            return;
        }
        // ③ 白名单 = BattleClientPlayer 的四条客户端 RPC（edge.cpp:455-510）
        if (upstream == null) {
            rejectRequest(s, request, null, TIP_INVALID_PARAMETER, RequestResult.NOT_ALLOWED, EdgeRejectReason.MESSAGE_ID_NOT_ALLOWED);
            return;
        }
        // ④ 请求体解析 → 交给房间 → 处理器返回之后写应答
        long playerId = s.playerId();
        ByteString body = request.getBody();
        MessageLite response;
        boolean businessError;
        try {
            switch (upstream) {
                case SUBMIT_BATTLE_ACTION -> {
                    SubmitBattleActionRequest req = SubmitBattleActionRequest.parseFrom(body);
                    SubmitBattleActionResponse resp = rooms.submit(playerId, req);
                    response = resp;
                    businessError = resp.hasErrorMessage();
                }
                case GET_BATTLE_STATE -> {
                    GetBattleStateRequest req = GetBattleStateRequest.parseFrom(body);
                    BattleStateS2C resp = rooms.getState(playerId, req);
                    response = resp;
                    businessError = false;
                }
                case SET_AUTO_BATTLE -> {
                    SetAutoBattleRequest req = SetAutoBattleRequest.parseFrom(body);
                    SetAutoBattleResponse resp = rooms.setAuto(playerId, req);
                    response = resp;
                    businessError = resp.hasErrorMessage();
                }
                case STOP_WATCH_BATTLE -> {
                    StopWatchBattleRequest req = StopWatchBattleRequest.parseFrom(body);
                    StopWatchBattleResponse resp = rooms.stopWatch(playerId, req);
                    response = resp;
                    businessError = resp.hasErrorMessage();
                }
                default -> throw new IllegalStateException("未处理的上行: " + upstream);
            }
        } catch (InvalidProtocolBufferException e) {
            rejectRequest(s, request, upstream, TIP_INVALID_PARAMETER, RequestResult.BAD_BODY, EdgeRejectReason.BODY_PARSE_FAILED);
            return;
        }
        metrics.clientRequest(upstream, businessError ? RequestResult.BUSINESS_ERROR : RequestResult.OK);
        // R2：处理器里推出的帧已经先写；房间若在处理器里优雅关闭了这条连接，FIN 排在这条应答之后（R3）
        s.writeDirect(BattleFrames.reply(request, response));
    }

    /** 信封错误（{@code id} / {@code message_id} 回显 + {@code error_message{tip}}）+ 非法包 +1，达阈值强关。 */
    private void rejectRequest(DirectSession s, ClientRequest request, Upstream upstream, int tipId, RequestResult result,
                               EdgeRejectReason reason) {
        s.writeDirect(BattleFrames.envelopeError(request, tipId));
        metrics.clientRequest(upstream, result);
        edge.rejectLog().record(reason, s.peer());
        registerIllegal(s, reason);
    }

    /**
     * 非法包 +1；阈值（缺省 50，0 = 只计不断）达到即强关（基线 {@code IllegalPacketCounter::RegisterAndShouldKill}，{@code counter >= threshold}）。
     * 这时刚写的信封错误可能来不及发出，不保证送达（B5）。
     */
    private void registerIllegal(DirectSession s, EdgeRejectReason why) {
        int count = s.registerIllegal();
        int threshold = edge.dependencies().properties().illegalPacketThreshold();
        if (threshold > 0 && count >= threshold) {
            log.warn("battle 直连非法包达阈值，断连 reason={} battle_id={} player_id={} count={} peer={}", why.wireName(),
                    Long.toUnsignedString(s.battleId()), Long.toUnsignedString(s.playerId()), count, s.peer());
            edge.rejectLog().record(EdgeRejectReason.ILLEGAL_THRESHOLD, s.peer());
            s.forceClose(Disconnect.ILLEGAL_PACKETS);
        }
    }
}
