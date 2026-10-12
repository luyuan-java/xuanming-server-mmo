package com.game.battle.testing;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.proto.BattleConfirmedEvent;
import com.game.proto.BattleSettlementEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 记录型的 battle → scene 传输（生产 = {@code NodeRpcClients<SceneBattleService>}；scene-battle-spec §13.5「记录型传输，带全局序号」）：
 * 记下每次调用的全局序号、方法、目标地址与完整的 {@link SceneBattleCall}。结算与确认两个端口形状相同，分别用方法引用
 * {@code transport::applySettlement} / {@code transport::confirm} 接上。
 *
 * <p>应答的「自然结局」由 {@link #respond} 给出（缺省：结算回 HANDLED + APPLIED，确认回 HANDLED）；什么时候、以什么方式交回由 {@link #replies}
 * 编排（悬着、传输失败、同步抛出……见 {@link Scripted}）。调用进 {@link CallJournal}：{@code deliver:<pid>/<bid>@<实例>#<attempt>}、
 * {@code confirm:<pid>/<bid>@<实例>}。线程安全。
 */
public final class RecordingSceneTransport {

    /** 一次调用。 */
    public record Sent(int seq, String method, SceneAssetEndpoint endpoint, SceneBattleCall call) {
    }

    public static final String APPLY_SETTLEMENT = "applySettlement";
    public static final String CONFIRM_BATTLE = "confirmBattle";

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public final Scripted<SceneBattleReply> replies = new Scripted<>();

    private final CallJournal journal;
    private volatile Function<Sent, SceneBattleReply> responder =
            s -> APPLY_SETTLEMENT.equals(s.method()) ? settlement(SettlementDisposition.SETTLEMENT_APPLIED) : status(SceneBattleStatus.SCENE_BATTLE_HANDLED);

    public RecordingSceneTransport(CallJournal journal) {
        this.journal = journal;
    }

    /** 之后的调用按它给应答（自然结局）。 */
    public RecordingSceneTransport respond(Function<Sent, SceneBattleReply> responder) {
        this.responder = responder;
        return this;
    }

    /** 之后的调用一律回这个应答。 */
    public RecordingSceneTransport respond(SceneBattleReply reply) {
        return respond(s -> reply);
    }

    /** 结算投递端口本体（{@code SettlementOutbox.Transport}）。 */
    public CompletableFuture<SceneBattleReply> applySettlement(SceneAssetEndpoint endpoint, SceneBattleCall call) {
        String battle;
        try {
            battle = Long.toUnsignedString(BattleSettlementEvent.parseFrom(call.getBody()).getSettlement().getBattleId());
        } catch (InvalidProtocolBufferException e) {
            battle = "?";
        }
        return record(APPLY_SETTLEMENT, endpoint, call, "deliver:" + Long.toUnsignedString(call.getPlayerId()) + "/" + battle + "@"
                + call.getTargetInstanceId() + "#" + call.getAttempt());
    }

    /** 确认端口本体（{@code DubboSceneBattleEvents.Transport}）。 */
    public CompletableFuture<SceneBattleReply> confirm(SceneAssetEndpoint endpoint, SceneBattleCall call) {
        String battle;
        try {
            battle = Long.toUnsignedString(BattleConfirmedEvent.parseFrom(call.getBody()).getBattleId());
        } catch (InvalidProtocolBufferException e) {
            battle = "?";
        }
        return record(CONFIRM_BATTLE, endpoint, call, "confirm:" + Long.toUnsignedString(call.getPlayerId()) + "/" + battle + "@"
                + call.getTargetInstanceId());
    }

    private CompletableFuture<SceneBattleReply> record(String method, SceneAssetEndpoint endpoint, SceneBattleCall call, String label) {
        Sent s = new Sent(journal.add(label), method, endpoint, call);
        sent.add(s);
        Function<Sent, SceneBattleReply> respond = responder;
        return replies.invoke(label, () -> respond.apply(s));
    }

    /** 最近一次调用。 */
    public Sent last() {
        return sent.get(sent.size() - 1);
    }

    // ------------------------------------------------------------------ 应答

    public static SceneBattleReply status(SceneBattleStatus status) {
        return SceneBattleReply.newBuilder().setStatus(status).build();
    }

    /** HANDLED + 结算处置。 */
    public static SceneBattleReply settlement(SettlementDisposition disposition) {
        return SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).setSettlement(disposition).build();
    }
}
