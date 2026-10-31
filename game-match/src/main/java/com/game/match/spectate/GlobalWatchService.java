package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.battle.BattleRoutings;
import com.game.match.global.GlobalWatchAuthority;
import com.game.match.metrics.MatchMetrics.WatchOutcome;
import com.game.match.port.PlayerStatusReader;
import com.game.match.proto.BattlePlacement;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.AddObserverRequest;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.game.proto.match.eSpectateDelivery;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * 全球模式的163提交协议：永久原UUID→旧操作取消证明→原Add持久化→RPC→永久回复。
 *
 * <p>每个出口给出 {@link Verdict}（应答 + 指标 outcome，由 {@code WatchBattleService} 恰好计一次）。业务拒绝回明确 tip 并经
 * {@code respond} 持久化（即关闭这次意图），同 request_id 重放读回首答，outcome 按回执的 tip 反查、与首答一致；
 * 只有未确认的 SQL / 归属异常回 16004「服务器繁忙」且不持久化，原 UUID 重试可复查。
 *
 * <p>可见性闸（mmorpg battle-spectate-tiered-fanout.md §10 第 6 条）：按 id 观战在互斥检查之后、清退旧观战之前读成局，私密局与不在的成局
 * 回同一个「不存在或已结束」（指标都记 {@code not_found}：回执只存 tip，重放无从区分）；随机选场由 SQL 跳过私密局，读回落点后再复核，
 * 原 Add 提交点（{@code GlobalWatchAuthority.prepare}）按同一份建房原件最后核一次。
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
    /** 大厅档准入（缺省关闭：观众只走直连席，满了回 16021）。装配时设一次。 */
    private SpectateRelay relay=SpectateRelay.disabled();
    public GlobalWatchService withRelay(SpectateRelay relay){this.relay=java.util.Objects.requireNonNull(relay,"relay");return this;}
    public GlobalWatchService(SqlSpectateStore store,PlayerStatusReader players,ObserverDialer observers,BiFunction<Long,Deadline,String> publicNames){
        this.store=store;authority=store.authority();this.players=players;this.observers=observers;this.publicNames=publicNames;
    }
    public Verdict watch(SessionContext actor,WatchBattleRequest request,Deadline deadline){
        // 第 1 行：会话没有绑定玩家，入口直接拒绝、不落 SQL（否则 SQL 主体校验抛错后会被误报成「服务器繁忙」）
        if(actor.getPlayerId()==0)return verdict(MatchTips.watchRejected(MatchTip.NO_IDENTITY));
        try {
            var history=authority.begin(actor,request,deadline);
            if(!history.original().getResponsePayload().isEmpty()){
                var stored=WatchBattleResponse.parseFrom(history.original().getResponsePayload());
                return stored.getDelivery()==eSpectateDelivery.SPECTATE_DELIVERY_LOBBY?replayLobby(actor,stored,history.latest(),deadline):verdict(stored);
            }
            // 与 mmorpg 同口径：被同一玩家更新的观战请求顶替 → 16016「已在观战」；原请求已被退出 / 取消关闭 → 16018。
            // 两者都是确定的终局业务结论，不是故障，不能回 16004「请稍后重试」（同 request_id 重试永远拿到同一结论）
            if(!history.latest())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_ALREADY),deadline);
            if(history.original().getCancelled())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE),deadline);
            var entry=authority.entry(actor,actor.getPlayerId(),deadline);
            if(entry.hasTicket())return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_QUEUED),deadline);
            if(players.inBattle(actor.getPlayerId(),deadline))return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_IN_BATTLE),deadline);
            // 可见性闸：按 id 观战（或重放已固定的选场）在互斥检查之后、清退旧观战 / 保留 / 发 Add 之前判定。成局不在与私密局回同一个
            // 「不存在或已结束」且都不清退旧观战——否则「旧观战有没有被拆」会成为探测私密局是否存在的旁路；回执持久化，重放同一结论
            long chosen=history.original().getBattleId()!=0?history.original().getBattleId():request.getBattleId();
            if(chosen!=0&&!watchable(store.read(chosen,deadline)))return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND),deadline);
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
            // 随机挑中的场与指定场在这里按同一判定复核（纵深防御；SQL 选场本已跳过私密局，原 Add 提交点还会再核一次）
            if(!watchable(snapshot))return answer(actor,request,MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND),deadline);
            var found=(SpectateStore.Record.Found)snapshot.record();
            // 大厅档（mmorpg battle-spectate-tiered-fanout.md §15.6）：已是中继档的场不写占用行、不锁成局、不发 Add，意图直接以 LOBBY 结案。
            // 同一 UUID 已经写过占用行（上一次走到一半）：照直连路径把原 Add 走完，否则原件成了没人收尾的直连席
            boolean ownReservation=entry.mark().isPresent()&&entry.mark().get().endsWith(":"+request.getRequestId());
            if(!ownReservation&&relay.relayed(battle,deadline))return settle(actor,request,relay.lobby(found.placement(),actor.getPlayerId(),snapshot.redisNowMs(),deadline),deadline);
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
            // 直连席满了且中继可用：这次以 LOBBY 结案，不算满员。结论与「原 Add 转待取消」同一笔事务落库（恢复循环凭原 Remove 的正证明关闭原件），之后再打开中继、签凭证，
            // 成败只影响这一次应答；同 UUID 重试、battle 对同 op 回首次满员码，都走到同一个 LOBBY 回执
            if(relay.enabled()&&outcome instanceof ObserverDialer.Outcome.Replied full&&full.tipId()==MatchTips.SPECTATE_FULL){
                var stored=authority.observedFull(actor,actor.getPlayerId(),mark,outcome,MatchTips.watchLobby(battle,com.google.protobuf.ByteString.EMPTY),deadline);
                return stored.getDelivery()==eSpectateDelivery.SPECTATE_DELIVERY_LOBBY&&stored.getBattleId()==battle
                        ?admitLobby(found.placement(),actor.getPlayerId(),snapshot.redisNowMs(),deadline):verdict(stored);
            }
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
    /**
     * 大厅档准入的结局 → 持久回执。凭证有 TTL，不进回执：回执只存「以 LOBBY 结案」（battle_id + delivery），本次应答才带凭证。并发先落的首答不是 LOBBY 时以首答为准。
     * 没调通不是结论，不持久化，留给同 UUID 重试。
     */
    private Verdict settle(SessionContext actor,WatchBattleRequest request,SpectateRelay.Admission admission,Deadline deadline){
        return switch(admission){
            case SpectateRelay.Admission.Lobby lobby -> {
                var stored=authority.respond(actor,request.getRequestId(),lobby.response().toBuilder().clearSpectateTicket().build(),deadline);
                yield verdict(stored.getDelivery()==eSpectateDelivery.SPECTATE_DELIVERY_LOBBY&&stored.getBattleId()==lobby.response().getBattleId()?lobby.response():stored);
            }
            case SpectateRelay.Admission.Rejected refused -> answer(actor,request,MatchTips.watchRejected(refused.tip()),deadline);
            case SpectateRelay.Admission.Unavailable unavailable -> verdict(MatchTips.watchRejected(MatchTip.BUSY));
        };
    }
    /**
     * LOBBY 回执已落库之后的准入（首答与重放同路）：幂等地打开 battle 的中继开关并签新凭证，结论只回这一次，不改持久回执。
     * 打开开关用的是这场固定的落点；调不通回 16004，同 UUID 重试还会再来一遍。
     */
    private Verdict admitLobby(BattlePlacement placement,long player,long nowMs,Deadline deadline){
        return switch(relay.escalate(placement,player,nowMs,deadline)){
            case SpectateRelay.Admission.Lobby lobby -> verdict(lobby.response());
            case SpectateRelay.Admission.Rejected refused -> verdict(MatchTips.watchRejected(refused.tip()));
            case SpectateRelay.Admission.Unavailable unavailable -> verdict(MatchTips.watchRejected(MatchTip.BUSY));
        };
    }
    /**
     * 重放以 LOBBY 结案的意图：重新做幂等的准入并签新凭证（§15.6；旧凭证可能已过期，不能原样回放）。重放也走一遍只读互斥：否则旧请求能在已开新观战、已排队或已开战之后拿到新凭证。
     * 这些情况与「这场已经看不了、开关已关」一样，只回本次结论，不改持久回执。
     */
    private Verdict replayLobby(SessionContext actor,WatchBattleResponse stored,boolean latest,Deadline deadline){
        if(!relay.enabled())return verdict(MatchTips.watchRejected(MatchTip.WATCH_FULL));
        if(!latest)return verdict(MatchTips.watchRejected(MatchTip.WATCH_ALREADY));
        if(authority.entry(actor,actor.getPlayerId(),deadline).hasTicket())return verdict(MatchTips.watchRejected(MatchTip.WATCH_QUEUED));
        if(players.inBattle(actor.getPlayerId(),deadline))return verdict(MatchTips.watchRejected(MatchTip.WATCH_IN_BATTLE));
        var snapshot=store.read(stored.getBattleId(),deadline);
        if(!watchable(snapshot))return verdict(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        return admitLobby(((SpectateStore.Record.Found)snapshot.record()).placement(),actor.getPlayerId(),snapshot.redisNowMs(),deadline);
    }
    /** 选中的场能不能看：成局已建房（读到好记录）且建房原件里冻结的可见性是 PUBLIC（{@link SpectateVisibilityPolicy}）。 */
    private static boolean watchable(SpectateStore.Snapshot snapshot){
        return snapshot.record() instanceof SpectateStore.Record.Found found&&SpectateVisibilityPolicy.isPublic(found.placement());
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
        if(response.getErrorMessage().getId()==0)return response.getDelivery()==eSpectateDelivery.SPECTATE_DELIVERY_LOBBY?WatchOutcome.LOBBY:WatchOutcome.OK;
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
