package com.game.robot.flow;

import com.game.proto.AccountSimplePlayer;
import com.game.proto.ClientRequest;
import com.game.proto.EnterSceneS2C;
import com.game.proto.RedirectToGateNotify;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.CreatePlayerResponse;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.robot.client.FakeGate;
import com.game.robot.client.MessageIds;
import com.game.table.LoginErrorTip;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录三步（48 / 14 / 26）的假服务端脚本，挂在 {@link FakeGate} 上：账号 → 角色列表可预置，26 之后发生什么可换
 * （进场推 79、推 124、业务拒绝、什么都不推、79 赶在应答之前）。几台假 gate 可以共用同一个实例（同一份账号与角色）。
 * 只按客户端契约回帧，不是 login 行为的证明。
 */
final class FakeLoginWorld implements FakeGate.Script {

    static final int IN_PROGRESS = LoginErrorTip.login_error.kLoginInProgress_VALUE;
    static final int SCENE_CONFIG = 1;
    static final long SCENE_ID = 1001;

    /** 26 之后发生什么。 */
    enum OnEnter {
        /** 应答成功，随后推 79。 */
        SCENE,
        /** 先推 79，再回应答（79 可以早于 26 的应答到达）。 */
        SCENE_BEFORE_REPLY,
        /** 应答成功，随后推 124（登录期重定向）。 */
        REDIRECT,
        /** 应答成功，之后什么都不推。 */
        SILENT,
        /** 应答带业务拒绝码 {@link #rejectCode}。 */
        REJECT
    }

    private final MessageIds ids;
    private final int redirectId;
    private final Map<String, List<AccountSimplePlayer>> roles = new ConcurrentHashMap<>();
    /** 角色号取 uint64 的上半区（按有符号看是负数）：哪里按有符号处理都会露馅。 */
    private long nextPlayerId = 0x8000_0000_0000_0101L;

    volatile OnEnter onEnter = OnEnter.SCENE;
    volatile int rejectCode;
    /** 前几次 26 回 2005（归属还没让出）。 */
    volatile int inProgressFirst;
    /** {@link OnEnter#REDIRECT} 推的 124。 */
    volatile RedirectToGateNotify redirect = RedirectToGateNotify.getDefaultInstance();
    /** 建角时写进角色的归属区（真服务端取会话 zone）。 */
    volatile int createZone = 1;
    /** 48 的业务拒绝码；0 = 照常回角色列表。 */
    volatile int loginError;

    /** 至今收到的 26 条数。 */
    final AtomicInteger enterAttempts = new AtomicInteger();
    /** 最近一条 26 请求进的角色。 */
    volatile long lastEnterPlayer;

    FakeLoginWorld(MessageIds ids, int redirectId) {
        this.ids = ids;
        this.redirectId = redirectId;
    }

    /** 给账号预置一个角色；返回角色号。 */
    synchronized long seed(String account, int homeZone) {
        long playerId = nextPlayerId++;
        roles.computeIfAbsent(account, k -> new CopyOnWriteArrayList<>())
                .add(AccountSimplePlayer.newBuilder().setPlayerId(playerId).setZoneId(homeZone).build());
        return playerId;
    }

    @Override
    public void onRequest(FakeGate.Session session, ClientRequest request) throws Exception {
        int id = request.getMessageId();
        if (id == ids.login()) {
            String account = LoginRequest.parseFrom(request.getBody()).getAccount();
            session.attachment = account;
            LoginResponse.Builder response = LoginResponse.newBuilder();
            if (loginError != 0) {
                response.setErrorMessage(TipInfoMessage.newBuilder().setId(loginError));
            } else {
                roles.getOrDefault(account, List.of()).forEach(role -> response.addPlayers(wrap(role)));
            }
            session.reply(request, response.build());
        } else if (id == ids.createPlayer()) {
            long playerId = seed((String) session.attachment, createZone);
            session.reply(request, CreatePlayerResponse.newBuilder()
                    .addPlayers(wrap(AccountSimplePlayer.newBuilder().setPlayerId(playerId).setZoneId(createZone).build())).build());
        } else if (id == ids.enterGame()) {
            long playerId = EnterGameRequest.parseFrom(request.getBody()).getPlayerId();
            lastEnterPlayer = playerId;
            if (enterAttempts.incrementAndGet() <= inProgressFirst) {
                session.reply(request, EnterGameResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(IN_PROGRESS)).build());
                return;
            }
            EnterGameResponse ok = EnterGameResponse.newBuilder().setPlayerId(playerId).build();
            switch (onEnter) {
                case SCENE -> {
                    session.reply(request, ok);
                    pushScene(session);
                }
                case SCENE_BEFORE_REPLY -> {
                    pushScene(session);
                    session.reply(request, ok);
                }
                case REDIRECT -> {
                    session.reply(request, ok);
                    session.push(redirectId, redirect);
                }
                case SILENT -> session.reply(request, ok);
                case REJECT -> session.reply(request,
                        EnterGameResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(rejectCode)).build());
            }
        }
        // 其余消息号不回包
    }

    private void pushScene(FakeGate.Session session) {
        session.push(ids.notifyEnterScene(), EnterSceneS2C.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(SCENE_CONFIG).setSceneId(SCENE_ID)).build());
    }

    private static AccountSimplePlayerWrapper wrap(AccountSimplePlayer role) {
        return AccountSimplePlayerWrapper.newBuilder().setPlayer(role).build();
    }
}
