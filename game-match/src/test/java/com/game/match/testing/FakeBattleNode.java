package com.game.match.testing;

import com.game.api.BattleNodeService;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.Empty;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.TipInfoMessage;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一个假的 battle 节点控制面（{@link BattleNodeService}）：按 6.2 的语义应答建房 / 销毁 / 补签，记下每次调用，并维护一张「哪些房间存在」的表——
 * 开局管线的测试靠它断言「明确拒绝时不发 destroy」「结局不明先 destroy」「换节点只 destroy 最后尝试的节点」。配 {@link FakeNodeCalls} 使用。
 *
 * <pre>
 * FakeBattleNode battle = new FakeBattleNode("a", events);    // 名字进事件序列：battle[a].create:77
 * // 缺省：建房成功（房间入表）、销毁成功（房间出表）、补签按名单签（不在名单或房间不在 → 1005）
 * battle.nextCreate(FakeBattleNode.notAllocatable("closed"));                  // 下一次建房：节点级拒绝（脚本先进先出，用完回到缺省）
 * battle.nextCreate(FakeBattleNode.rejected(1006));                            // 已受理但明确拒绝（零副作用）
 * battle.nextCreate(FakeBattleNode.unspecified());                             // 准入字段缺失
 * battle.nextCreateFails(() -> new TimeoutException("慢"), true);               // 传输失败；true = 房间其实建成了
 * battle.nextCreateHangs();                                                    // 永不应答
 * battle.nextDestroyFails(() -> new IllegalStateException("断连"));             // 下一次销毁失败（房间还在）
 * battle.nextIssue(response);                                                  // 下一次补签的应答原样给定
 * assertThat(battle.creates).singleElement().satisfies(req -> assertThat(req.getMatchMode()).isEqualTo(3));
 * assertThat(battle.destroys).isEmpty();
 * assertThat(battle.rooms()).containsKey(77L);
 * </pre>
 * 线程安全。
 */
public final class FakeBattleNode implements BattleNodeService {

    /** 全部建房请求，按到达顺序。 */
    public final List<CreateBattleRequest> creates = new CopyOnWriteArrayList<>();
    /** 全部销毁请求。 */
    public final List<DestroyBattleRequest> destroys = new CopyOnWriteArrayList<>();
    /** 全部补签请求。 */
    public final List<IssueBattleTicketRequest> issues = new CopyOnWriteArrayList<>();
    /** 全部登记 / 清退观众的请求（6.5 用）。 */
    public final List<AddObserverRequest> addObservers = new CopyOnWriteArrayList<>();
    public final List<RemoveObserverRequest> removeObservers = new CopyOnWriteArrayList<>();
    /** 事件序列：{@code "battle[<name>].create:<id>"} / {@code ".destroy:<id>"} / {@code ".issue:<id>:<pid>"}。 */
    public final List<String> events;
    private final String name;
    private final Map<Long, CreateBattleRequest> rooms = new ConcurrentHashMap<>();
    private final Deque<Function<CreateBattleRequest, CompletableFuture<CreateBattleResult>>> createScripts = new ArrayDeque<>();
    private final Deque<Supplier<? extends Throwable>> destroyFailures = new ArrayDeque<>();
    private final Deque<Supplier<CompletableFuture<IssueBattleTicketResponse>>> issueScripts = new ArrayDeque<>();

    public FakeBattleNode() {
        this("battle", new CopyOnWriteArrayList<>());
    }

    public FakeBattleNode(String name, List<String> events) {
        this.name = name;
        this.events = events;
    }

    // ---------------------------------------------------------------- 建房应答的工厂

    /** 已受理且建成：{@code CreateBattleResponse{battle_id}}。 */
    public static CreateBattleResult admitted(long battleId) {
        return CreateBattleResult.newBuilder().setAdmission(BattleAdmission.BATTLE_ADMISSION_ADMITTED)
                .setResponse(CreateBattleResponse.newBuilder().setBattleId(battleId).build().toByteString()).build();
    }

    /** 已受理但明确拒绝（应答带错误码，保证零副作用）：1005 参数 / 1006 指纹不符 / 1002 引擎拒绝 / 1003 签不出票。 */
    public static CreateBattleResult rejected(int tipId) {
        return CreateBattleResult.newBuilder().setAdmission(BattleAdmission.BATTLE_ADMISSION_ADMITTED)
                .setResponse(CreateBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(tipId)).build().toByteString())
                .build();
    }

    /** 节点级拒绝（保证没建房）：{@code reason} = not_started / closed / closed_in_loop / overloaded。 */
    public static CreateBattleResult notAllocatable(String reason) {
        return CreateBattleResult.newBuilder().setAdmission(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE).setReason(reason).build();
    }

    /** 准入字段缺失（旧版本 / 损坏）：读方按「可能已建房」处理。 */
    public static CreateBattleResult unspecified() {
        return CreateBattleResult.getDefaultInstance();
    }

    // ---------------------------------------------------------------- 脚本

    /** 下一次建房的应答（先进先出）。应答是「已受理且建成」时房间入表，其余不入表。 */
    public synchronized FakeBattleNode nextCreate(CreateBattleResult result) {
        createScripts.add(request -> {
            if (result.getAdmission() == BattleAdmission.BATTLE_ADMISSION_ADMITTED && isSuccess(result)) {
                rooms.put(request.getBattleId(), request);
            }
            return CompletableFuture.completedFuture(result);
        });
        return this;
    }

    /**
     * 下一次建房传输失败。
     *
     * @param built true = 房间其实已经建成（调用方看到的是结局不明；之后的 destroy 才会把它拆掉）
     */
    public synchronized FakeBattleNode nextCreateFails(Supplier<? extends Throwable> error, boolean built) {
        createScripts.add(request -> {
            if (built) {
                rooms.put(request.getBattleId(), request);
            }
            return CompletableFuture.failedFuture(error.get());
        });
        return this;
    }

    /** 下一次建房永不应答（房间已建成）。 */
    public synchronized FakeBattleNode nextCreateHangs() {
        createScripts.add(request -> {
            rooms.put(request.getBattleId(), request);
            return new CompletableFuture<>();
        });
        return this;
    }

    /** 下一次销毁传输失败（房间还在）。 */
    public synchronized FakeBattleNode nextDestroyFails(Supplier<? extends Throwable> error) {
        destroyFailures.add(error);
        return this;
    }

    /** 下一次补签的应答原样给定。 */
    public synchronized FakeBattleNode nextIssue(IssueBattleTicketResponse response) {
        issueScripts.add(() -> CompletableFuture.completedFuture(response));
        return this;
    }

    /** 下一次补签传输失败。 */
    public synchronized FakeBattleNode nextIssueFails(Supplier<? extends Throwable> error) {
        issueScripts.add(() -> CompletableFuture.failedFuture(error.get()));
        return this;
    }

    /** 直接摆一间房（给补签 / 观战的测试当前置状态）。 */
    public FakeBattleNode room(CreateBattleRequest request) {
        rooms.put(request.getBattleId(), request);
        return this;
    }

    /** 此刻存在的房间：battle_id → 建房请求。 */
    public Map<Long, CreateBattleRequest> rooms() {
        return Map.copyOf(rooms);
    }

    // ---------------------------------------------------------------- BattleNodeService

    @Override
    public CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request) {
        creates.add(request);
        events.add("battle[" + name + "].create:" + Long.toUnsignedString(request.getBattleId()));
        Function<CreateBattleRequest, CompletableFuture<CreateBattleResult>> script;
        synchronized (this) {
            script = createScripts.poll();
        }
        if (script != null) {
            return script.apply(request);
        }
        // 缺省：建成；同 battle_id 的房间已存在时幂等命中（不比较请求内容）
        rooms.putIfAbsent(request.getBattleId(), request);
        return CompletableFuture.completedFuture(admitted(request.getBattleId()));
    }

    @Override
    public CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request) {
        destroys.add(request);
        events.add("battle[" + name + "].destroy:" + Long.toUnsignedString(request.getBattleId()));
        Supplier<? extends Throwable> failure;
        synchronized (this) {
            failure = destroyFailures.poll();
        }
        if (failure != null) {
            return CompletableFuture.failedFuture(failure.get());
        }
        rooms.remove(request.getBattleId());
        return CompletableFuture.completedFuture(Empty.getDefaultInstance());
    }

    @Override
    public CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request) {
        issues.add(request);
        events.add("battle[" + name + "].issue:" + Long.toUnsignedString(request.getBattleId()) + ":" + Long.toUnsignedString(request.getPlayerId()));
        Supplier<CompletableFuture<IssueBattleTicketResponse>> script;
        synchronized (this) {
            script = issueScripts.poll();
        }
        if (script != null) {
            return script.get();
        }
        CreateBattleRequest room = rooms.get(request.getBattleId());
        boolean member = room != null && room.getPlayersList().stream().map(BattlePlayerSnapshot::getPlayerId).anyMatch(id -> id == request.getPlayerId());
        if (!member) {
            // 房间不在 / 不是成员：1005，不带 parameters（battle 的裁决由 match 原样透传）
            return CompletableFuture.completedFuture(IssueBattleTicketResponse.newBuilder()
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(1005)).build());
        }
        return CompletableFuture.completedFuture(IssueBattleTicketResponse.newBuilder().setAssignment(BattleAssignedS2C.newBuilder()
                .setBattleId(request.getBattleId()).setHost("127.0.0.1").setPort(12000)
                .setTokenPayload(ByteString.copyFromUtf8("payload:" + request.getBattleId() + ":" + request.getPlayerId()))
                .setTokenSignature(ByteString.copyFromUtf8("signature"))
                .setExpireAtMs(room.getDeadlineMs()).setRole(eBattleTicketRole.forNumber(1))).build());
    }

    @Override
    public CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request) {
        addObservers.add(request);
        return CompletableFuture.completedFuture(rooms.containsKey(request.getBattleId()) ? AddObserverResponse.getDefaultInstance()
                : AddObserverResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(1004)).build());
    }

    @Override
    public CompletableFuture<Empty> removeObserver(RemoveObserverRequest request) {
        removeObservers.add(request);
        return CompletableFuture.completedFuture(Empty.getDefaultInstance());
    }

    private static boolean isSuccess(CreateBattleResult result) {
        try {
            return CreateBattleResponse.parseFrom(result.getResponse()).getErrorMessage().getId() == 0;
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            return false;
        }
    }
}
