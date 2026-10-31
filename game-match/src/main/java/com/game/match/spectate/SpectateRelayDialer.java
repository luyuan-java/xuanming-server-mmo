package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementDialer.Dial;
import com.game.match.proto.BattlePlacement;
import com.game.match.support.MatchTips;
import com.game.proto.AttachRelayGateRequest;
import com.game.proto.AttachRelayGateResponse;
import com.game.proto.PromoteSpectateBroadcastRequest;
import com.game.proto.PromoteSpectateBroadcastResponse;
import com.game.proto.TipInfoMessage;
import java.time.Duration;
import java.util.Objects;

/**
 * 按固定落点直拨 battle，打开这一场的观战中继开关（mmorpg battle-spectate-tiered-fanout.md §15.4、§15.7）。Java 版没有逐 gate 挂接，也没有全服配额：
 * {@code AttachRelayGate} 与 {@code PromoteSpectateBroadcast} 都只是幂等地打开同一个开关，所以两个请求都不带 gate 身份。
 * 阻塞至多到 {@code hardStop}，不抛异常、不重试（打开开关幂等，下一个观众还会再试）；在虚拟线程上调。
 */
public final class SpectateRelayDialer {

    /** 一次调用的结局。 */
    public sealed interface Outcome {

        /** 开关已打开：实例与期限取自 battle 的回包。 */
        record Enabled(String battleInstanceId, long deadlineMs) implements Outcome {
        }

        /** battle 明确拒绝（1004 房间不存在 / 1005 实例不符或不是公开局 / 1003 停机中）。 */
        record Rejected(int tipId) implements Outcome {
        }

        /** 没调通（含超时）：开关可能开了也可能没开，调用方不能当成升档成功。 */
        record Unavailable(String detail) implements Outcome {
        }
    }

    private final PlacementDialer dialer;

    public SpectateRelayDialer(PlacementDialer dialer) {
        this.dialer = Objects.requireNonNull(dialer, "dialer");
    }

    /** L0 满员转中继。 */
    public Outcome attach(BattlePlacement placement, Duration timeout, Deadline hardStop) {
        AttachRelayGateRequest request = AttachRelayGateRequest.newBuilder().setBattleId(placement.getBattleId())
                .setTargetInstanceId(placement.getBattleInstanceId()).build();
        Dial<AttachRelayGateResponse> dial = dialer.dial(placement, timeout, hardStop, node -> node.attachRelayGate(request));
        return switch (dial) {
            case Dial.Replied<AttachRelayGateResponse> replied ->
                    outcome(replied.reply().getErrorMessage(), replied.reply().getBattleInstanceId(), replied.reply().getDeadlineMs());
            case Dial.RoomGone<AttachRelayGateResponse> gone -> new Outcome.Rejected(MatchTips.BATTLE_ROOM_NOT_FOUND);
            case Dial.Unavailable<AttachRelayGateResponse> unavailable -> new Outcome.Unavailable(unavailable.kind() + ": " + unavailable.detail());
        };
    }

    /** 运营标记赛事局时立刻打开开关。 */
    public Outcome promote(BattlePlacement placement, Duration timeout, Deadline hardStop) {
        PromoteSpectateBroadcastRequest request = PromoteSpectateBroadcastRequest.newBuilder().setBattleId(placement.getBattleId())
                .setTargetInstanceId(placement.getBattleInstanceId()).build();
        Dial<PromoteSpectateBroadcastResponse> dial = dialer.dial(placement, timeout, hardStop, node -> node.promoteSpectateBroadcast(request));
        return switch (dial) {
            case Dial.Replied<PromoteSpectateBroadcastResponse> replied ->
                    outcome(replied.reply().getErrorMessage(), replied.reply().getBattleInstanceId(), replied.reply().getDeadlineMs());
            case Dial.RoomGone<PromoteSpectateBroadcastResponse> gone -> new Outcome.Rejected(MatchTips.BATTLE_ROOM_NOT_FOUND);
            case Dial.Unavailable<PromoteSpectateBroadcastResponse> unavailable -> new Outcome.Unavailable(unavailable.kind() + ": " + unavailable.detail());
        };
    }

    private static Outcome outcome(TipInfoMessage error, String instance, long deadlineMs) {
        if (error.getId() != 0) {
            return new Outcome.Rejected(error.getId());
        }
        // 成功却没有实例：对端不是这个版本（缺省方法回的默认值），不能签一张收不到帧的凭证
        return instance.isEmpty() ? new Outcome.Unavailable("battle 回包缺实例") : new Outcome.Enabled(instance, deadlineMs);
    }
}
