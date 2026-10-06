package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.SceneBattleReply;
import com.game.common.RunMode;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleConfirmedEvent;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleReconnectS2C;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.MessageContent;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.eBattleOutcome;
import com.game.scene.asset.AssetOpService;
import com.game.scene.attribute.AttributeFeature;
import com.game.scene.attribute.AttributeService;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.currency.CurrencyFeature;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneBattleMetrics;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.mission.ActivityFeature;
import com.game.scene.mission.MissionFeature;
import com.game.scene.mission.MissionService;
import com.game.scene.mission.MissionTables;
import com.game.scene.pet.PetFeature;
import com.game.scene.pet.PetService;
import com.game.scene.pet.PetTables;
import com.game.scene.player.ItemGuids;
import com.game.scene.player.Wallet;
import com.game.scene.skill.SkillFeature;
import com.game.scene.skill.SkillService;
import com.game.scene.skill.SkillTables;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeBattleLocks;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.ManualExecutor;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingSink;
import com.game.scene.testing.RecordingTeamFollow;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.CrossNodeSwitch;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerLocations;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneClock;
import com.game.scene.world.SceneFeature;
import com.game.scene.world.SceneInstances;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.SwitchPhase;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ConfigTables;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 回合制战斗（批次 6.3）组件测试的共用装配：与 {@code SceneNode} 同形的一整套——真 {@link SceneWorld}（14 参构造，战斗钩子 = 真
 * {@link PlayerBattleService}）、真 {@link BattleSettlementService} 与全部玩法服务（货币 / 背包 / 属性 / 宝宝 / 任务 / 技能，<b>正式配表</b>）、
 * 全功能的 {@link ClientRequestHandler}（DEV 运行模式），外部依赖全是假的：
 * <ul>
 *   <li>{@link #locks}：内存版 Redis（{@link FakeBattleLocks}，按真值表执行；可挂起、乱序完成、注入失败与重放）；</li>
 *   <li>{@link #logic}：手动驱动的逻辑线程（{@link ManualExecutor}）——Redis 结局的回调只入队，{@link #drain()} 才跑；</li>
 *   <li>{@link #repo}：假仓库（加载、在线存盘、交出都挂起，测试决定结局；durable 由 {@code repo.takeProgress().complete(SAVED)} 控制）；</li>
 *   <li>{@link #sink}：记录全部出站；{@link #clock}：手动时钟（Redis 的 TTL 也按它走）；{@link #targets}：假的跨节点选目标（开了 5.2）；</li>
 *   <li>{@link #follows}：记录型组队跟随（要换成别的实现用带工厂的构造）。</li>
 * </ul>
 * 两张主世界地图各一个场景（{@link #scene1}、{@link #scene2}）。全部调用都在测试线程上（它就是逻辑线程）。
 *
 * <p><b>典型写法</b>：
 * <pre>{@code
 * BattleFixture f = new BattleFixture();
 * ScenePlayer p = f.enter(11, 1001);                       // 新号进场，恢复读已处理（recovery = READY），出站记录已清
 * var reply = f.prepare(1001, 7);                          // 发备战；写锁已在假 Redis 上执行，回调还在队列里
 * f.drain();                                               // 逻辑线程处理写锁结局
 * assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isZero();   // 不要直接 reply.join()：见 done()
 * f.locks.hold(Op.CONFIRM);                                // 之后的 CONFIRM 挂起，由测试决定何时、怎样完成
 * f.confirm(1001, 7, f.deadline());
 * f.locks.take(Op.CONFIRM).fail(new RuntimeException("超时"));
 * f.drain();
 * }</pre>
 * 包内可见的运行态（{@code lockPending} / {@code lockExtended} / {@code rescuing} / {@code cancelRequested} / 恢复状态）本包的测试直接用；
 * 别的包（{@code world}、{@code team}）的测试用本类末尾的静态访问器。
 */
public final class BattleFixture {

    public static final long LINK = 1;
    /** 本节点号（跨节点换图判「选中的是不是自己」）。 */
    public static final int LOCAL_NODE = 3;
    public static final int TARGET_NODE = 4;
    /** 别的节点上的场景号（本节点没有）。 */
    public static final long REMOTE_SCENE = 900_001;
    public static final int ZONE = 1;
    public static final int GATE_NODE = 7;
    public static final String GATE_INSTANCE = "gate-instance-test";
    public static final String SCENE_INSTANCE = "scene-instance-test";
    /** 备战请求里的 battle 节点号（写进冻结与锁的 n）。 */
    public static final int BATTLE_NODE = 21;
    /** 缺省的战斗时长与备战时长（{@link #deadline()} / {@link #prepareDeadline()}）。 */
    public static final long BATTLE_MILLIS = 300_000;
    public static final long PREPARE_MILLIS = 60_000;
    public static final int TIP = 23;

    /** 正式配表（与 mmorpg 逐字节一致），全部测试共用一份。 */
    public static final ConfigTables CONFIG = loadConfig();

    /** 给组队跟随工厂的装配件（{@code new TeamFollowService(reads, probe, w.logic(), w.metrics())}）。 */
    public record Wiring(Executor logic, SceneMetrics metrics, SceneClock clock) {
    }

    public final ManualClock clock = new ManualClock();
    public final ManualExecutor logic = new ManualExecutor();
    public final RecordingSink sink = new RecordingSink();
    public final FakePlayerRepository repo = new FakePlayerRepository();
    public final FakeSwitchTargets targets = new FakeSwitchTargets();
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final RecordingAssetAudit audit = new RecordingAssetAudit();
    public final FakeBattleLocks locks = new FakeBattleLocks(clock::epochMillis);
    /** 缺省的记录型组队跟随；用带工厂的构造换掉时为 null。 */
    public final RecordingTeamFollow follows;
    public final TeamFollow teamFollow;
    public final SceneMetrics sceneMetrics = new SceneMetrics(meters);
    public final SceneBattleMetrics battleMetrics = new SceneBattleMetrics(meters);
    public final CurrencyService currency;
    public final BagService bags;
    public final AttributeService attributes;
    public final PetService pets;
    public final MissionService missions;
    public final SkillService skills;
    public final SceneBattleTables tables = SceneBattleTables.from(CONFIG);
    public final BattleSettlementService settlements;
    public final PlayerBattleService battle;
    public final SceneWorld world;
    /** 注册进 {@link #handler} 的全部玩法功能（与 {@code SceneNode} 的那份清单同样的七个；在途闸矩阵据此核对「主代码里的功能类都在夹具里」）。 */
    public final List<SceneFeature> features;
    public final ClientRequestHandler handler;
    public final Scene scene1;
    public final Scene scene2;
    /** 144 / 150 / 184 的消息号。 */
    public final int reconnectHintId = Contracts.REGISTRY.requireId("BattleClientPlayer", "NotifyBattleReconnect");
    public final int battleEndId = Contracts.REGISTRY.requireId("BattleClientPlayer", "NotifyBattleEnd");
    public final int petListChangedId = Contracts.REGISTRY.requireId("ScenePetClientPlayer", "NotifyPetListChanged");
    /** false = 快照路由取不到（会话链路不在登记表里，备战回 1011）。 */
    public boolean routingAvailable = true;
    /**
     * 每次铸物品 / 宝宝 guid 之前跑一遍（null = 不跑）。背包入包在「规划完成、还没改状态」的那一刻铸号，所以它是结算应用 i 步（掉落）中途的一个注入点：
     * 让它抛异常 = 「某一步抛异常」；在里面再调一次 {@code settlements.apply} = 同一玩家的重入。
     */
    public Runnable beforeItemMint;

    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    private long requestIds = 1000;

    public BattleFixture() {
        this(null);
    }

    /** @param teamFollowFactory 用别的组队跟随实现（如真的 {@code TeamFollowService}）；null = 记录型 {@link #follows} */
    public BattleFixture(Function<Wiring, TeamFollow> teamFollowFactory) {
        ItemGuids guids = count -> {
            Runnable hook = beforeItemMint;
            if (hook != null) {
                hook.run();
            }
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = guidSeq.incrementAndGet();
            }
            return out;
        };
        if (teamFollowFactory == null) {
            follows = new RecordingTeamFollow();
            teamFollow = follows;
        } else {
            follows = null;
            teamFollow = teamFollowFactory.apply(new Wiring(logic, sceneMetrics, clock));
        }
        currency = new CurrencyService(audit, GainAnomalyDetector.off(), sceneMetrics, clock);
        bags = new BagService(BagTables.from(CONFIG), guids, audit, GainAnomalyDetector.off(), sceneMetrics);
        attributes = new AttributeService(AttributeTables.from(CONFIG), clock, currency, sceneMetrics);
        missions = new MissionService(MissionTables.from(CONFIG), bags, clock);
        skills = new SkillService(SkillTables.from(CONFIG), clock, sceneMetrics, Contracts.IDS);
        pets = new PetService(PetTables.from(CONFIG), currency, guids, clock, new SplittableRandom(7), sceneMetrics);
        PetFeature petFeature = new PetFeature(pets, Contracts.REGISTRY);
        settlements = new BattleSettlementService(currency, bags, pets, missions, tables, battleMetrics, clock, petListChangedId);
        battle = new PlayerBattleService(locks, settlements, tables, pets, this::routing, teamFollow, battleMetrics, clock, logic,
                reconnectHintId, battleEndId);
        // 进场规整同 SceneNode：背包 → 属性 → 宝宝 → 任务 → 两本账本的加载检查
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet, clock,
                sceneMetrics, player -> {
                    bags.initializeOnLoad(player);
                    attributes.initializeOnLoad(player);
                    pets.initializeOnLoad(player);
                    missions.initializeOnLoad(player);
                    AssetOpService.checkLedgerOnLoad(player);
                    PlayerBattleService.checkLedgerOnLoad(player);
                }, PlayerSnapshots.NONE, PlayerLocations.NONE, teamFollow,
                new CrossNodeSwitch(LOCAL_NODE, targets, owned -> { }, Duration.ofSeconds(4), Duration.ofSeconds(30)),
                SceneInstances.DISABLED, battle);
        battle.attach(world);
        features = List.of(
                new CurrencyFeature(currency),
                new AttributeFeature(attributes, Contracts.REGISTRY, call -> {
                    petFeature.onOwnerLevelChanged(call);
                    missions.onLevelChanged(call.player());
                }),
                new BagFeature(bags), new MissionFeature(missions), new ActivityFeature(missions), new SkillFeature(skills),
                petFeature);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, features);
        scene1 = world.createScene(1);
        scene2 = world.createScene(2);
    }

    private static ConfigTables loadConfig() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        return ConfigTables.load(dir);
    }

    private BattleRouting routing(ScenePlayer player) {
        if (!routingAvailable) {
            return null;
        }
        return BattleRouting.newBuilder()
                .setSessionId(player.session().sessionId())
                .setGateNodeId(GATE_NODE)
                .setGateInstanceId(GATE_INSTANCE)
                .setSceneNodeId(LOCAL_NODE)
                .setSceneInstanceId(SCENE_INSTANCE)
                .setZoneId(ZONE)
                .build();
    }

    // ================================================================== 驱动：逻辑线程、时间

    /** 让逻辑线程把队列里的任务（Redis 结局的回调等）全部跑完；返回跑了几个。 */
    public int drain() {
        return logic.runAll();
    }

    /** 跑一轮 reaper 并处理它当场完成的 Redis 结局（挂起的脚本要测试自己完成后再 {@link #drain()}）。 */
    public void reap() {
        battle.reap();
        drain();
    }

    /** 时钟前进（Redis 的 TTL 跟着走）。 */
    public void advance(long millis) {
        clock.advanceMillis(millis);
    }

    /** 缺省的战斗期限：现在 + {@value #BATTLE_MILLIS} ms。 */
    public long deadline() {
        return clock.epochMillis() + BATTLE_MILLIS;
    }

    /** 缺省的备战期限：现在 + {@value #PREPARE_MILLIS} ms。 */
    public long prepareDeadline() {
        return clock.epochMillis() + PREPARE_MILLIS;
    }

    // ================================================================== 玩家进场

    /**
     * 新号（等级 1、epoch 1、没有玩法数据）进 {@link #scene1}，并把进场恢复读处理完（{@code recovery = READY}），
     * 最后清掉出站记录与已回复的 Redis 调用记录——返回的是一名「干净在线」的玩家。{@code ENTER_READ} 被挂起 / 注入了失败时恢复不会就绪
     * （PENDING / RETRY），挂起的那次调用仍留在 {@code locks} 的记录里。
     */
    public ScenePlayer enter(int sessionId, long playerId) {
        return enter(sessionId, playerId, 1, scene1, PlayerState.getDefaultInstance());
    }

    /** 带存档进场（账本、货币等放在 {@code state} 里；库里这份就是落库快照，其中的账本条目天然 durable），其余同 {@link #enter(int, long)}。 */
    public ScenePlayer enter(int sessionId, long playerId, PlayerState state) {
        return enter(sessionId, playerId, 1, scene1, state);
    }

    public ScenePlayer enter(int sessionId, long playerId, long epoch, Scene scene, PlayerState state) {
        ScenePlayer player = load(sessionId, playerId, epoch, scene, state);
        drain();
        sink.clear();
        locks.clearCalls();
        if (follows != null) {
            follows.clear();
        }
        return player;
    }

    /**
     * 进场但<b>不</b>驱动逻辑线程、不清记录：加载已完成、玩家已在场景里，进场恢复读已经发出（自动模式下已在假 Redis 上执行，回调在队列里；
     * {@code ENTER_READ} 被挂起时还没执行）。用来测进场恢复本身与「恢复读在途」期间的行为。先写库里那一行再进场。
     */
    public ScenePlayer load(int sessionId, long playerId, long epoch, Scene scene, PlayerState state) {
        repo.put(new PlayerData(playerId, epoch, 3, 1, "look-" + playerId, 1, scene.configId(), new Vec3(5, 5, 0), state,
                "玩家" + playerId));
        return reenter(sessionId, playerId, epoch, scene);
    }

    /** 用库里现有的那一行再进一次场（重连 / 顶号 / 同 epoch 重复进场；不驱动逻辑线程、不清记录）。 */
    public ScenePlayer reenter(int sessionId, long playerId, long epoch, Scene scene) {
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder().setSessionId(sessionId).setPlayerId(playerId)
                .setSceneId(scene.sceneId()).setOwnerEpoch(epoch).build());
        repo.completeAll();
        ScenePlayer player = world.playerById(playerId);
        assertThat(player).as("玩家 %s 应已进场", playerId).isNotNull();
        return player;
    }

    /** 把挂起的在线存盘全部按同一个结局完成（SAVED → 落库快照更新、触发快路径销账），并处理随之完成的 Redis 结局；返回完成了几笔。 */
    public int completeSaves(ProgressResult result) {
        int n = 0;
        while (repo.pendingProgress() > 0) {
            repo.takeProgress().complete(result);
            n++;
        }
        drain();
        return n;
    }

    // ================================================================== 战斗入口（都不自动 drain，除非写明）

    public PrepareBattleRequest prepareRequest(long playerId, long battleId) {
        return prepareRequest(playerId, battleId, deadline(), prepareDeadline());
    }

    public PrepareBattleRequest prepareRequest(long playerId, long battleId, long deadlineMs, long prepareDeadlineMs) {
        return PrepareBattleRequest.newBuilder().setPlayerId(playerId).setBattleId(battleId).setBattleNodeId(BATTLE_NODE)
                .setDeadlineMs(deadlineMs).setPrepareDeadlineMs(prepareDeadlineMs).build();
    }

    /** 发备战（缺省期限）。写锁在自动模式下已经在假 Redis 上执行，但回调还在逻辑队列里：{@link #drain()} 之后应答才完成。 */
    public CompletableFuture<PrepareBattleResponse> prepare(long playerId, long battleId) {
        return battle.prepare(prepareRequest(playerId, battleId));
    }

    /** 备战并处理完写锁结局，断言成功（冻结 PREPARING、锁已写）；返回应答。 */
    public PrepareBattleResponse prepared(long playerId, long battleId) {
        CompletableFuture<PrepareBattleResponse> reply = prepare(playerId, battleId);
        drain();
        assertThat(reply).isCompleted();
        PrepareBattleResponse response = reply.join();
        assertThat(tipOf(response)).as("备战应成功").isZero();
        return response;
    }

    /** 备战成功并确认开战（冻结 FIGHTING、锁已标 F 并续到正式期限）。在本实例备战的冻结不推 144。 */
    public void fighting(long playerId, long battleId) {
        long deadline = deadline();
        battle.prepare(prepareRequest(playerId, battleId, deadline, prepareDeadline()));
        drain();
        confirm(playerId, battleId, deadline);
        drain();
        ScenePlayer player = world.playerById(playerId);
        assertThat(player.battle().freeze()).isNotNull();
        assertThat(player.battle().freeze().phase()).isEqualTo(BattleFreeze.Phase.FIGHTING);
    }

    /** 开局确认（不 drain）。 */
    public void confirm(long playerId, long battleId, long deadlineMs) {
        battle.confirm(BattleConfirmedEvent.newBuilder().setPlayerId(playerId).setBattleId(battleId).setDeadlineMs(deadlineMs)
                .build());
    }

    /** 取消备战（不 drain）。 */
    public CompletableFuture<Void> cancel(long playerId, long battleId) {
        return battle.cancel(CancelBattlePrepareRequest.newBuilder().setPlayerId(playerId).setBattleId(battleId).build());
    }

    /** 结算投递（信封 player_id 取结算里的；不 drain）。 */
    public CompletableFuture<SceneBattleReply> deliver(BattleSettlementData settlement) {
        return battle.deliver(settlement.getPlayerId(), settlement);
    }

    /**
     * 一份最朴素的结算：胜利、金币 {@code gold}、气血 / 法力给一个很大的值（应用时夹到上限 = 满血满蓝，不改 vitals）。
     * 要掉落、消耗、宝宝、击杀的在返回的 builder 上接着加。
     */
    public static BattleSettlementData.Builder settlement(long playerId, long battleId, long gold) {
        return BattleSettlementData.newBuilder().setPlayerId(playerId).setBattleId(battleId)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setHealth(Long.MAX_VALUE).setMana(Long.MAX_VALUE).setGoldGain(gold);
    }

    /** battle 把这份结算落库（写进假 Redis 的待结算记录；已销账的局回 -1、不写）。 */
    public long store(BattleSettlementData settlement) {
        return locks.storeSettlement(settlement);
    }

    // ================================================================== 读结果：推送、指标、资产

    /** 这名玩家的会话按顺序收到的全部下行的消息号。 */
    public List<Integer> messageIds(ScenePlayer player) {
        return sink.messageIdsTo(player.session().linkId(), player.session().sessionId());
    }

    /** 这名玩家收到的 144 重连提示里的 battle_id，按顺序。 */
    public List<Long> reconnectHints(ScenePlayer player) {
        List<Long> out = new ArrayList<>();
        for (MessageContent m : pushes(player, reconnectHintId)) {
            out.add(parse(() -> BattleReconnectS2C.parseFrom(m.getSerializedMessage()).getBattleId()));
        }
        return out;
    }

    /** 这名玩家收到的 150 战斗结束，按顺序。 */
    public List<BattleEndS2C> battleEnds(ScenePlayer player) {
        List<BattleEndS2C> out = new ArrayList<>();
        for (MessageContent m : pushes(player, battleEndId)) {
            out.add(parse(() -> BattleEndS2C.parseFrom(m.getSerializedMessage())));
        }
        return out;
    }

    /** 这名玩家收到的 23 推送的 tip 序列。 */
    public List<Integer> pushedTips(ScenePlayer player) {
        List<Integer> out = new ArrayList<>();
        for (MessageContent m : pushes(player, TIP)) {
            out.add(parse(() -> TipInfoMessage.parseFrom(m.getSerializedMessage()).getId()));
        }
        return out;
    }

    /** 这名玩家收到的某个消息号的全部下行，按顺序。 */
    public List<MessageContent> pushes(ScenePlayer player, int messageId) {
        return sink.to(player.session().linkId(), player.session().sessionId()).stream()
                .filter(m -> m.getMessageId() == messageId).toList();
    }

    /**
     * 取一个<b>此刻应当已经有结局</b>的应答（异常完成的照常抛出）。不要对可能还没完成的 future 直接 {@code join()}：主代码回归把某条同步分支变成了
     * 「等 Redis」时，{@code join()} 会把整个测试进程挂死（{@code CompletableFuture.join} 不响应中断），而不是判这条用例失败。
     */
    public static <T> T done(CompletableFuture<T> future) {
        assertThat(future).as("应答此刻应当已经有结局（没有 = 还在等某个没被驱动的异步结果）").isDone();
        return future.join();
    }

    /** 备战应答的拒绝码；成功（不带 error_message）为 0。 */
    public static int tipOf(PrepareBattleResponse response) {
        return response.hasErrorMessage() ? response.getErrorMessage().getId() : 0;
    }

    /**
     * 读一个计数器：{@code count("xm.scene.battle.prepares", "result", "ok")}；标签成对给，取值用小写（同导出口径）。
     * 指标名见 {@code SceneBattleMetrics} / {@code SceneMetrics}（点分形式）。
     */
    public double count(String meter, String... tags) {
        Counter counter = meters.find(meter).tags(tags).counter();
        assertThat(counter).as("计数器 %s%s 应已预建", meter, List.of(tags)).isNotNull();
        return counter.count();
    }

    public double prepares(String result) {
        return count("xm.scene.battle.prepares", "result", lower(result));
    }

    public double cancels(String result) {
        return count("xm.scene.battle.cancels", "result", lower(result));
    }

    public double confirms(String result) {
        return count("xm.scene.battle.confirms", "result", lower(result));
    }

    public double rebuilds(String reason, String result) {
        return count("xm.scene.battle.rebuilds", "reason", lower(reason), "result", lower(result));
    }

    public double recoveries(String result) {
        return count("xm.scene.battle.recovery", "result", lower(result));
    }

    public double settlementsCounted(String path, String result) {
        return count("xm.scene.battle.settlements", "path", lower(path), "result", lower(result));
    }

    public double acks(String trigger, String result) {
        return count("xm.scene.battle.acks", "trigger", lower(trigger), "result", lower(result));
    }

    public double rescues(String result) {
        return count("xm.scene.battle.rescues", "result", lower(result));
    }

    public double hints(String trigger) {
        return count("xm.scene.battle.reconnect.hints", "trigger", lower(trigger));
    }

    /** 金币余额。 */
    public long gold(ScenePlayer player) {
        return player.wallet().balance(Wallet.GOLD);
    }

    // ================================================================== 客户端请求与跨节点换图（5.2）

    /** 以这名玩家的会话发一条客户端请求（经真的分发与各闸）；应答 / 推送去 {@link #sink} 里看。返回这次请求的 request_id。 */
    public long request(ScenePlayer player, String service, String method, Message body) {
        return request(player, Contracts.REGISTRY.requireId(service, method), body);
    }

    public long request(ScenePlayer player, int messageId, Message body) {
        long requestId = ++requestIds;
        handler.onClientForward(player.session().linkId(), ClientForward.newBuilder()
                .setSessionId(player.session().sessionId())
                .setPlayerId(player.playerId())
                .setMessageId(messageId)
                .setBody(body.toByteString())
                .setRequestId(requestId)
                .build());
        return requestId;
    }

    /** 63：指定场景号（本节点的就同步换，别的节点的进 RESOLVING）。 */
    public long enterScene(ScenePlayer player, long sceneId, int configId) {
        return request(player, Contracts.IDS.enterScene(), EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(configId).setSceneId(sceneId)).build());
    }

    /** 让玩家进 RESOLVING（63 指向别的节点上的场景；槽在 4 s 兜底超时 + 1 s 后过期），返回挂起的选目标请求。 */
    public FakeSwitchTargets.PendingSelect resolveRemote(ScenePlayer player) {
        enterScene(player, REMOTE_SCENE, 0);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        return targets.take();
    }

    /** 让玩家进 FREEZING（选目标回「别的节点」），返回挂起的交出。 */
    public PendingHandOff freezeForHandOff(ScenePlayer player) {
        resolveRemote(player).chosen(TARGET_NODE, REMOTE_SCENE, 2);
        assertThat(player.frozen()).isTrue();
        return repo.takeHandOff();
    }

    // ================================================================== 包内运行态的访问器（给别的包的测试）

    public static BattleFreeze freeze(ScenePlayer player) {
        return player.battle().freeze();
    }

    public static boolean lockPending(BattleFreeze freeze) {
        return freeze.lockPending();
    }

    public static boolean cancelRequested(BattleFreeze freeze) {
        return freeze.cancelRequested();
    }

    public static boolean lockExtended(BattleFreeze freeze) {
        return freeze.lockExtended();
    }

    public static boolean rescuing(BattleFreeze freeze) {
        return freeze.rescuing();
    }

    /** 直接摆一个冻结（不写锁；要锁自己用 {@code locks.putLock} 摆）。null = 摘掉。 */
    public static void setFreeze(ScenePlayer player, BattleFreeze freeze) {
        player.battle().setFreeze(freeze);
    }

    public static void setRecovery(ScenePlayer player, PlayerBattle.Recovery recovery) {
        player.battle().setRecovery(recovery);
    }

    /** 本实例是否已为这一局发出过销账（过期读的兜底集合）。 */
    public static boolean writtenOff(ScenePlayer player, long battleId) {
        return player.battle().writtenOff(battleId);
    }

    /** 此刻内存状态里要持久化的玩法数据（world 包内可见入口的转手；断言账本进没进写回内容用。落库快照看 {@code player.persistedState()}）。 */
    public static PlayerState persistentState(ScenePlayer player) {
        return WorldTestAccess.persistentState(player);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private interface Parse<T> {
        T get() throws InvalidProtocolBufferException;
    }

    private static <T> T parse(Parse<T> call) {
        try {
            return call.get();
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }
}
