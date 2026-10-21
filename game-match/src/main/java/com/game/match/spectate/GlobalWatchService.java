package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.battle.BattleRoutings;
import com.game.match.global.GlobalWatchAuthority;
import com.game.match.port.PlayerStatusReader;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.AddObserverRequest;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/** 全球模式的163提交协议：永久原UUID→旧操作取消证明→原Add持久化→RPC→永久回复。 */
public final class GlobalWatchService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GlobalWatchService.class);
    private final SqlSpectateStore store;
    private final GlobalWatchAuthority authority;
    private final PlayerStatusReader players;
    private final ObserverDialer observers;
    private final BiFunction<Long,Deadline,String> publicNames;
    public GlobalWatchService(SqlSpectateStore store,PlayerStatusReader players,ObserverDialer observers,BiFunction<Long,Deadline,String> publicNames){
        this.store=store;authority=store.authority();this.players=players;this.observers=observers;this.publicNames=publicNames;
    }
    public WatchBattleResponse watch(SessionContext actor,WatchBattleRequest request,Deadline deadline){
        try {
            var history=authority.begin(actor,request,deadline);
            if(!history.original().getResponsePayload().isEmpty())return WatchBattleResponse.parseFrom(history.original().getResponsePayload());
            if(history.original().getCancelled()||!history.latest())return answer(actor,request,MatchTips.watchRejected(MatchTip.BUSY),deadline);
            var entry=authority.entry(actor,actor.getPlayerId(),deadline);
            if(entry.hasTicket())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_QUEUED),deadline);
            if(players.inBattle(actor.getPlayerId(),deadline))return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_IN_BATTLE),deadline);
            var presence=players.presence(actor.getPlayerId(),deadline);
            if(presence.isEmpty())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_OFFLINE),deadline);
            if(actor.getSessionId()==0||actor.getGateInstanceId().isEmpty()||presence.get().getSessionId()!=actor.getSessionId()
                    ||!presence.get().getGateInstanceId().equals(actor.getGateInstanceId()))throw new SecurityException("原观战会话已经被替代");
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
            return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE),deadline);
        }catch(Exception failure){
            // 未知提交保留history/current，不用新UUID或新归属补权。原UUID重试可复查。
            log.warn("全球观战原操作未确认，保留持久原件，类别={}",failure.getClass().getSimpleName());
            return MatchTips.watchRejected(MatchTip.BUSY);
        }
    }
    private WatchBattleResponse answer(SessionContext actor,WatchBattleRequest request,WatchBattleResponse response,Deadline deadline){return authority.respond(actor,request.getRequestId(),response,deadline);}
}
