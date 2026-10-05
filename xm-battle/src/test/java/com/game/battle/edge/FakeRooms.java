package com.game.battle.edge;

import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.protocol.BattleFrames;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.room.BattleRoomService;
import com.game.battle.room.DirectLink;
import com.game.battle.room.RoomOrigin;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.SpectateStateS2C;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.eBattleTicketRole;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 直连面测试用的假房间服务：只实现直连面会调的部分（挂接 / 摘除 / 首帧 / 四条上行），控制面方法一律抛异常。
 * 名单与直连槽按 player_id 记（测试里一个玩家只在一局里）；挂接成功时按 R7 顶替旧直连（{@code closeNow(REPLACED)}，不发帧）。
 * 四条上行的处理器可替换，用来模拟「处理器里推帧、优雅关闭」等时序。并发容器：回环测试里逻辑线程写、测试线程读。
 */
final class FakeRooms implements BattleRoomService {

    record Member(long battleId, long playerId, eBattleTicketRole role) {
    }

    /** 挂接时回的大厅会话号（高位为 1：只进日志，按无符号打印）。 */
    static final int GATE_SESSION = (int) 4_000_000_001L;

    final Set<Member> roster = ConcurrentHashMap.newKeySet();
    final Map<Long, DirectLink> slots = new ConcurrentHashMap<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final List<DirectLink> detached = new CopyOnWriteArrayList<>();
    private final BattleMessageIds ids;

    volatile BiFunction<Long, SubmitBattleActionRequest, SubmitBattleActionResponse> onSubmit =
            (pid, req) -> SubmitBattleActionResponse.getDefaultInstance();
    volatile BiFunction<Long, GetBattleStateRequest, BattleStateS2C> onGetState =
            (pid, req) -> BattleStateS2C.newBuilder().setBattleId(req.getBattleId()).setRoundIndex(1).build();
    volatile BiFunction<Long, SetAutoBattleRequest, SetAutoBattleResponse> onSetAuto =
            (pid, req) -> SetAutoBattleResponse.getDefaultInstance();
    volatile BiFunction<Long, StopWatchBattleRequest, StopWatchBattleResponse> onStopWatch =
            (pid, req) -> StopWatchBattleResponse.getDefaultInstance();
    /** 挂接期间对新直连做的事（验证 R1：挂接时写帧必须被丢弃）。 */
    volatile Consumer<DirectLink> duringAttach = link -> {
    };

    FakeRooms(BattleMessageIds ids) {
        this.ids = ids;
    }

    void add(long battleId, long playerId, eBattleTicketRole role) {
        roster.add(new Member(battleId, playerId, role));
    }

    DirectLink link(long playerId) {
        return slots.get(playerId);
    }

    /** 模拟房间收尾 / 退出观战：先摘槽，再优雅关闭（room.cpp:1632-1634）。 */
    void closeSlot(long playerId, Disconnect reason) {
        DirectLink link = slots.remove(playerId);
        if (link != null) {
            link.closeGracefully(reason);
        }
    }

    int id(Notify notify) {
        return ids.id(notify);
    }

    // ---------------------------------------------------------------- 直连面会调的

    @Override
    public OptionalInt attachDirect(long battleId, long playerId, eBattleTicketRole role, DirectLink link) {
        if (!roster.contains(new Member(battleId, playerId, role))) {
            events.add("attach-miss:" + playerId);
            return OptionalInt.empty();
        }
        if (link.isLive()) {
            throw new AssertionError("挂接时直连还不应是活的（R1）");
        }
        duringAttach.accept(link);
        DirectLink old = slots.put(playerId, link);
        if (old != null && old != link) {
            old.closeNow(Disconnect.REPLACED);
        }
        events.add("attach:" + playerId);
        return OptionalInt.of(GATE_SESSION);
    }

    @Override
    public void onDirectVerified(long battleId, long playerId, eBattleTicketRole role) {
        events.add("verified:" + playerId + ":" + role.getNumber());
        if (role == eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER) {
            link(playerId).send(BattleFrames.push(ids.id(Notify.SPECTATE_STATE),
                    SpectateStateS2C.newBuilder().setObserverCount(1)
                            .setState(BattleStateS2C.newBuilder().setBattleId(battleId)).build()));
        }
    }

    @Override
    public void detachDirect(long battleId, long playerId, DirectLink link) {
        boolean removed = slots.remove(playerId, link);
        detached.add(link);
        events.add("detach:" + playerId + ":" + removed);
    }

    @Override
    public SubmitBattleActionResponse submit(long playerId, SubmitBattleActionRequest request) {
        events.add("submit:" + playerId + ":" + request.getBattleId());
        return onSubmit.apply(playerId, request);
    }

    @Override
    public BattleStateS2C getState(long playerId, GetBattleStateRequest request) {
        events.add("getState:" + playerId + ":" + request.getBattleId());
        return onGetState.apply(playerId, request);
    }

    @Override
    public SetAutoBattleResponse setAuto(long playerId, SetAutoBattleRequest request) {
        events.add("setAuto:" + playerId + ":" + request.getEnabled());
        return onSetAuto.apply(playerId, request);
    }

    @Override
    public StopWatchBattleResponse stopWatch(long playerId, StopWatchBattleRequest request) {
        events.add("stopWatch:" + playerId);
        return onStopWatch.apply(playerId, request);
    }

    @Override
    public int roomCount() {
        return 0;
    }

    // ---------------------------------------------------------------- 控制面：直连面不调

    @Override
    public CreateBattleResponse createBattle(CreateBattleRequest request, RoomOrigin origin) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroyBattle(DestroyBattleRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void abortAll(String reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IssueBattleTicketResponse issueBattleTicket(IssueBattleTicketRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AddObserverResponse addObserver(AddObserverRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeObserver(RemoveObserverRequest request) {
        throw new UnsupportedOperationException();
    }
}
