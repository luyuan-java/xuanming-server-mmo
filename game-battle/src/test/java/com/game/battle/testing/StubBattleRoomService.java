package com.game.battle.testing;

import com.game.battle.room.BattleRoomService;
import com.game.battle.room.BattleScheduler;
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
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.eBattleTicketRole;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 控制面 / 节点生命周期测试用的房间服务桩：只记录调用（带调用线程名与房间来源），按可调的规则回应答，不建真房间。
 * 设了 {@link #loop} 时每次调用先断言在逻辑线程上（核对「Dubbo / Tomcat 线程不碰房间」）；{@link #beforeCall} 可在调用里阻塞或抛异常。
 * 线程安全（字段都是并发容器或 volatile）。
 */
public final class StubBattleRoomService implements BattleRoomService {

    /** 调用记录（方法名，按发生顺序）。 */
    public final List<String> calls = new CopyOnWriteArrayList<>();
    /** 每次调用所在的线程名。 */
    public final List<String> threads = new CopyOnWriteArrayList<>();
    /** createBattle 收到的房间来源。 */
    public final List<RoomOrigin> origins = new CopyOnWriteArrayList<>();
    /** createBattle 收到的请求。 */
    public final List<CreateBattleRequest> creates = new CopyOnWriteArrayList<>();
    /** {@link #roomCount()} 的读数；createBattle 成功（不带 tip）时加一，abortAll 时清零。 */
    public final AtomicInteger rooms = new AtomicInteger();

    /** 非 null 时：每次调用先断言在它的逻辑线程上。 */
    public volatile BattleScheduler loop;
    /** 非 null 时：每次调用先执行它（参数是方法名），可阻塞或抛异常。 */
    public volatile Consumer<String> beforeCall;
    /** createBattle 回的 tip（0 = 成功建房）。 */
    public volatile int createTip;
    /** issueBattleTicket / addObserver 回的 tip（0 = 成功）。 */
    public volatile int ticketTip;

    private void enter(String method) {
        BattleScheduler l = loop;
        if (l != null) {
            l.assertInLoop();
        }
        calls.add(method);
        threads.add(Thread.currentThread().getName());
        Consumer<String> hook = beforeCall;
        if (hook != null) {
            hook.accept(method);
        }
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }

    @Override
    public CreateBattleResponse createBattle(CreateBattleRequest request, RoomOrigin origin) {
        enter("createBattle");
        origins.add(origin);
        creates.add(request);
        CreateBattleResponse.Builder response = CreateBattleResponse.newBuilder().setBattleId(request.getBattleId());
        int t = createTip;
        if (t != 0) {
            response.setErrorMessage(tip(t));
        } else {
            rooms.incrementAndGet();
        }
        return response.build();
    }

    @Override
    public void destroyBattle(DestroyBattleRequest request) {
        enter("destroyBattle");
    }

    @Override
    public void abortAll(String reason) {
        enter("abortAll:" + reason);
        rooms.set(0);
    }

    @Override
    public IssueBattleTicketResponse issueBattleTicket(IssueBattleTicketRequest request) {
        enter("issueBattleTicket");
        int t = ticketTip;
        return t == 0 ? IssueBattleTicketResponse.getDefaultInstance()
                : IssueBattleTicketResponse.newBuilder().setErrorMessage(tip(t)).build();
    }

    @Override
    public AddObserverResponse addObserver(AddObserverRequest request) {
        enter("addObserver");
        int t = ticketTip;
        return t == 0 ? AddObserverResponse.getDefaultInstance() : AddObserverResponse.newBuilder().setErrorMessage(tip(t)).build();
    }

    @Override
    public void removeObserver(RemoveObserverRequest request) {
        enter("removeObserver");
    }

    @Override
    public OptionalInt attachDirect(long battleId, long playerId, eBattleTicketRole role, DirectLink link) {
        enter("attachDirect");
        return OptionalInt.empty();
    }

    @Override
    public void onDirectVerified(long battleId, long playerId, eBattleTicketRole role) {
        enter("onDirectVerified");
    }

    @Override
    public void detachDirect(long battleId, long playerId, DirectLink link) {
        enter("detachDirect");
    }

    @Override
    public SubmitBattleActionResponse submit(long playerId, SubmitBattleActionRequest request) {
        enter("submit");
        return SubmitBattleActionResponse.getDefaultInstance();
    }

    @Override
    public BattleStateS2C getState(long playerId, GetBattleStateRequest request) {
        enter("getState");
        return BattleStateS2C.getDefaultInstance();
    }

    @Override
    public SetAutoBattleResponse setAuto(long playerId, SetAutoBattleRequest request) {
        enter("setAuto");
        return SetAutoBattleResponse.getDefaultInstance();
    }

    @Override
    public StopWatchBattleResponse stopWatch(long playerId, StopWatchBattleRequest request) {
        enter("stopWatch");
        return StopWatchBattleResponse.getDefaultInstance();
    }

    @Override
    public int roomCount() {
        return rooms.get();
    }
}
