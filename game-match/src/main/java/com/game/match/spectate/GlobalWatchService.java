package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.battle.BattleRoutings;
import com.game.match.global.GlobalWatchAuthority;
import com.game.match.metrics.MatchMetrics.WatchOutcome;
import com.game.match.port.PlayerStatusReader;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.AddObserverRequest;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * 全球模式的163提交协议：永久原UUID→旧操作取消证明→原Add持久化→RPC→永久回复。
 *
 * <p>每个出口给出 {@link Verdict}（应答 + 指标 outcome，由 {@code WatchBattleService} 恰好计一次）。业务拒绝回明确 tip 并经
 * {@code respond} 持久化（即关闭这次意图），同 request_id 重放读回首答，outcome 按回执的 tip 反查、与首答一致；
 * 只有未确认的 SQL / 归属异常回 16004「服务器繁忙」且不持久化，原 UUID 重试可复查。
 */
public final class GlobalWatchService {
    /** 一次 163 的结论：出口指标与应答。 */
    public record Verdict(WatchOutcome outcome, WatchBattleResponse response) {
    }

    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GlobalWatchService.class);
    private final SqlSpectateStore store;
    private final GlobalWatchAuthority authority;
    private final PlayerStatusReader players;
    private final ObserverDialer observers;
    private final BiFunction<Long,Deadline,String> publicNames;
    public GlobalWatchService(SqlSpectateStore store,PlayerStatusReader players,ObserverDialer observers,BiFunction<Long,Deadline,String> publicNames){
        this.store=store;authority=store.authority();this.players=players;this.observers=observers;this.publicNames=publicNames;
    }
    public Verdict watch(SessionContext actor,WatchBattleRequest request,Deadline deadline){
        // 第 1 行：会话没有绑定玩家，入口直接拒绝、不落 SQL（否则 SQL 主体校验抛错后会被误报成「服务器繁忙」）
        if(actor.getPlayerId()==0)return verdict(MatchTips.watchRejected(MatchTip.NO_IDENTITY));
        try {
            var history=authority.begin(actor,request,deadline);
            if(!history.original().getResponsePayload().isEmpty())return verdict(WatchBattleResponse.parseFrom(history.original().getResponsePayload()));
            // 与 mmorpg 同口径：被同一玩家更新的观战请求顶替 → 16016「已在观战」；原请求已被退出 / 取消关闭 → 16018。
            // 两者都是确定的终局业务结论，不是故障，不能回 16004「请稍后重试」（同 request_id 重试永远拿到同一结论）
            if(!history.latest())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_ALREADY),deadline);
            if(history.original().getCancelled())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE),deadline);
            var entry=authority.entry(actor,actor.getPlayerId(),deadline);
            if(entry.hasTicket())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_QUEUED),deadline);
            if(players.inBattle(actor.getPlayerId(),deadline))return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_IN_BATTLE),deadline);
            var presence=players.presence(actor.getPlayerId(),deadline);
            if(presence.isEmpty())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_OFFLINE),deadline);
            // 会话不符（原会话已被替代或缺会话）：同离线按业务拒绝回复并关闭意图，同 request_id 重放得到同一结论
            if(actor.getSessionId()==0||actor.getGateInstanceId().isEmpty()||presence.get().getSessionId()!=actor.getSessionId()
                    ||!presence.get().getGateInstanceId().equals(actor.getGateInstanceId()))return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_OFFLINE),deadline);
            if(entry.mark().isPresent()&&!entry.mark().get().endsWith(":"+request.getRequestId()))
                store.releaseForRequest(actor,request.getRequestId(),entry.mark().get(),deadline);
            long battle=history.original().getBattleId();
            if(battle==0){
                battle=request.getBattleId();
                if(battle==0){var picked=store.pickRandom(ThreadLocalRandom.current().nextDouble(),deadline);
                    if(picked instanceof SpectateStore.Pick.Member member)battle=Long.parseUnsignedLong(member.member());}
                if(battle==0)return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_NO_BATTLE),deadline);
                battle=authority.select(actor,request.getRequestId(),battle,deadline);
            }
            var snapshot=store.read(battle,deadline);
            if(!(snapshot.record() instanceof SpectateStore.Record.Found found))return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND),deadline);
            String mark=Long.toUnsignedString(battle)+":"+request.getRequestId();
            var acquired=authority.reserve(actor,actor.getPlayerId(),mark,deadline);
            if(acquired!=SpectateStore.Acquire.OK)return answer(actor,request,MatchTips.watchRejected(acquired==SpectateStore.Acquire.QUEUED?MatchTip.WATCH_QUEUED:MatchTip.WATCH_ALREADY),deadline);
            if(deadline.remainingMillis()<=MatchBudgets.WATCH_ADD_MIN_BUDGET_MS){store.releaseAsync(actor.getPlayerId(),mark);return answer(actor,request,MatchTips.watchRejected(MatchTip.BUSY),deadline);}
            String publicName="";try{publicName=publicNames.apply(actor.getPlayerId(),Deadline.after(Math.max(1,Math.min(1000,deadline.remainingMillis()))));}catch(RuntimeException ignored){/* 展示失败不退回私有账号。 */}
            var base=AddObserverRequest.newBuilder().setBattleId(battle).setObserverPlayerId(actor.getPlayerId()).setObserverName(publicName==null?"":publicName)
                .setRouting(BattleRoutings.gatePart(presence.get())).build();
            // 原Add截止与请求剩余预算一起固定；原UUID重放只能读取此字节，不得延长。
            var original=authority.prepare(actor,actor.getPlayerId(),mark,found.placement(),base,deadline);
            var outcome=observers.add(found.placement(),original,SpectateRules.hopTimeout(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS,deadline),deadline);
            authority.observed(actor,actor.getPlayerId(),mark,outcome,deadline);
            if(outcome instanceof ObserverDialer.Outcome.Replied reply&&reply.tipId()==0&&authority.active(actor,actor.getPlayerId(),mark,deadline))
                return answer(actor,request,MatchTips.watchAccepted(battle).toBuilder().setSpectatorOperationId(request.getRequestId()).build(),deadline);
            return answer(actor,request,MatchTips.watchRejected(rejectionOf(outcome)),deadline);
        }catch(Exception failure){
            // 未知提交保留history/current，不用新UUID或新归属补权。原UUID重试可复查。
            log.warn("全球观战原操作未确认，保留持久原件，类别={}",failure.getClass().getSimpleName());
            return verdict(MatchTips.watchRejected(MatchTip.BUSY));
        }
    }
    /** 已有回执时 respond 读回首答：outcome 按实际回出的应答反查，重放与首答记同一个出口。 */
    private Verdict answer(SessionContext actor,WatchBattleRequest request,WatchBattleResponse response,Deadline deadline){
        return verdict(authority.respond(actor,request.getRequestId(),response,deadline));
    }

    /**
     * battle 对原 Add 没有准入时回给客户端的 tip：满员专用码 →「观战人数已满」，1004 →「该战斗不存在或已结束」，其余明确拒绝与传输结局 →
     * 「该战斗当前无法观战」。全球路径不换场。标记的回滚已由 {@code observed} 把原件置为待取消，恢复循环凭原 Remove 的取消正证明关闭。
     */
    static MatchTip rejectionOf(ObserverDialer.Outcome outcome){
        if(outcome instanceof ObserverDialer.Outcome.Replied replied){
            if(replied.tipId()==MatchTips.SPECTATE_FULL)return MatchTip.WATCH_FULL;
            if(replied.tipId()==MatchTips.BATTLE_ROOM_NOT_FOUND)return MatchTip.WATCH_NOT_FOUND;
        }
        return MatchTip.WATCH_NOT_WATCHABLE;
    }

    private static Verdict verdict(WatchBattleResponse response){return new Verdict(outcomeOf(response),response);}

    /** 应答 → 指标 outcome（与本地路径 {@code WatchBattleService} 的口径相同）；认不出的回执按 internal 计。 */
    static WatchOutcome outcomeOf(WatchBattleResponse response){
        if(response.getErrorMessage().getId()==0)return WatchOutcome.OK;
        for(MatchTip tip:MatchTip.values()){
            if(tip.proto().equals(response.getErrorMessage()))return watchOutcome(tip);
        }
        return WatchOutcome.INTERNAL;
    }

    private static WatchOutcome watchOutcome(MatchTip tip){
        return switch(tip){
            case WATCH_QUEUED -> WatchOutcome.QUEUED;
            case WATCH_IN_BATTLE -> WatchOutcome.IN_BATTLE;
            case WATCH_ALREADY -> WatchOutcome.ALREADY_WATCHING;
            case WATCH_OFFLINE -> WatchOutcome.OFFLINE;
            case WATCH_NO_BATTLE -> WatchOutcome.NO_BATTLE;
            case WATCH_NOT_FOUND -> WatchOutcome.NOT_FOUND;
            case WATCH_NOT_WATCHABLE -> WatchOutcome.REJECTED;
            case WATCH_FULL -> WatchOutcome.FULL;
            default -> WatchOutcome.INTERNAL; // NO_IDENTITY / BUSY（16004）
        };
    }
}
