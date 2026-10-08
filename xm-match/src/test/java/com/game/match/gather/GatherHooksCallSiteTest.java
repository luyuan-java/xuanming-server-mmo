package com.game.match.gather;

import static com.game.match.gather.GatherFixture.ONE_V_ONE;
import static com.game.match.gather.GatherFixture.TARGET_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.game.api.BattleNodeService;
import com.game.api.proto.CreateBattleResult;
import com.game.common.deadline.Deadline;
import com.game.match.placement.PlacementStore;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.spectate.SpectateGatherHooks;
import com.game.match.spectate.SpectateRules;
import com.game.match.spectate.SpectateStore;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.FakeObserverDialer.Call;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.TicketState;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleActivityContext;
import com.game.proto.CreateBattleRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.Empty;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.eBattleActivityKind;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 观战的<b>真钩子</b>（{@link SpectateGatherHooks}）接在开局管线上之后的行为（spectate-spec §4.6、§10.3「{@code GatherPipelineTest} 追加」一段）：
 * 五个入口都在第一次备战之前清退观众；失败路径不公开、已清退的观众不恢复；第 2.5 步之前就失败则根本不清退；
 * 落点在建房期间被懒剔除后，开局成功的补写 + 公开能把它恢复；公开失败、清退失败都不影响开局。
 * 对照基线 {@code spectate_test.go:481}（GatherEntryEvictsSpectatorAndRegistersBattle）、{@code :867}（GatherFailureLeavesNoSpectateIndexAndKeepsObserverEvicted），
 * {@code gather_spectate_index_test.go:440}（PublishRestoresRecordEvictedDuringCreate）、{@code :463}（SucceedsWhenActiveZaddFails）。
 *
 * <p>与 {@code GatherPipelineTest} 的分工：那边用只记账的钩子钉<b>调用点</b>本身（{@code beforePrepare} 的次序、钩子抛异常不影响开局、
 * 补写失败钩子照调、各失败出口不调 {@code onStarted}）；这里换上真钩子，钉的是调用点带来的<b>观战侧效果</b>
 * （观战标记、发给 battle 的清退、可观战索引）。台子同一个 {@link GatherFixture}：观战存储与落点、票据、手拨的 Redis 时间共享状态，
 * 清退与公开都记进同一条事件序列。
 */
class GatherHooksCallSiteTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final long C = 1003;
    private static final long OLD_BATTLE = 880_100;
    private static final long T0 = ManualRedisClock.DEFAULT_START_MS;
    private static final String EVICTIONS = "xm.match.spectate.evictions";
    private static final String ANOMALIES = "xm.match.watchable.anomalies";

    /** 一条完整的管线，钩子是真的；观战存储与管线的落点 / 票据 / 时钟是同一组内存替身。 */
    private static final class Rig {

        final GatherFixture f = new GatherFixture();
        final InMemorySpectateStore spectate = new InMemorySpectateStore(f.clock, f.tickets, f.placements, f.events);
        final FakeObserverDialer observers = new FakeObserverDialer(f.events);

        Rig() {
            f.hooksPort = new SpectateGatherHooks(spectate, observers, f.metrics);
        }

        /** 一场 10 秒前开打、已公开的旧战斗，{@code playerId} 正在看它。返回他的观战标记。 */
        String watchingOldBattle(long playerId) {
            BattlePlacement old = BattlePlacement.newBuilder().setBattleId(OLD_BATTLE).setBattleNodeId(9).setBattleInstanceId("inst-old")
                    .setRpcHost("10.0.0.9").setRpcPort(21299).setAttempt(1).setMode(ONE_V_ONE).addPlayerNames("旧甲").addPlayerNames("旧乙")
                    .setCreatedAtMs(T0 - 10_000).setDeadlineMs(T0 + 290_000).build();
            f.placements.put(old);
            spectate.putWatchable(OLD_BATTLE, T0 - 10_000);
            String mark = SpectateRules.encodeMark(OLD_BATTLE, "0123456789abcdef");
            spectate.putMark(playerId, mark);
            return mark;
        }

        GatherResult run(GatherPlan plan) {
            return f.pipeline().run(plan);
        }

        List<String> spectateWrites() {
            return f.events.stream().filter(event -> event.startsWith("spectate.publish:") || event.startsWith("spectate.release:")
                    || event.startsWith("spectate.evict:")).toList();
        }
    }

    private static String id(long battleId) {
        return Long.toUnsignedString(battleId);
    }

    // ================================================================ 五个入口

    @Test
    void 五个入口的成员若正在观战_都在第一次备战之前被清退_开局成功后新的一局登记进索引() {
        BattleActivityContext activity = BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                .setGuildId(555).setActivityId(3).setPeriodKey(20261006).setInitiatorPlayerId(A).setGuildPeriodKey(20261006).build();
        Map<String, Function<GatherFixture, GatherPlan>> entries = Map.of(
                "凑单", f -> f.popped(ONE_V_ONE, 0, B, A),
                "PVE_SOLO", f -> f.solo(A),
                "切磋", f -> f.challenge(B, A),
                "整队", f -> f.team(B, A, C),
                "活动", f -> f.activity(8_888_888L, activity, A, B));

        entries.forEach((entry, planOf) -> {
            Rig rig = new Rig();
            String mark = rig.watchingOldBattle(A);
            GatherPlan plan = planOf.apply(rig.f);

            GatherResult result = rig.run(plan);

            assertThat(result.ok()).as(entry).isTrue();
            String battle = id(result.battleId());
            List<String> events = rig.f.events;
            // 清退的三步是整次 gather 最早的三个事件：读旧场落点 → RemoveObserver → 删标记；之后才是第一次备战
            assertThat(events.subList(0, 4)).as("%s：清退排在任何备战之前", entry).containsExactly("spectate.read:" + id(OLD_BATTLE),
                    "observer.remove:" + id(OLD_BATTLE) + ":1001:enter_gather", "spectate.release:1001",
                    "scene.prepare:" + id(plan.members().get(0)));
            assertThat(rig.spectate.calls.get(0)).as("%s：一次读出整个名单的标记，顺序就是 gather 的成员顺序", entry)
                    .isEqualTo("marksOf(" + plan.members().stream().map(Long::toUnsignedString).toList() + ")");
            assertThat(rig.observers.calls).as(entry).singleElement().satisfies(call -> {
                assertThat(call.kind()).isEqualTo(FakeObserverDialer.Kind.REMOVE);
                assertThat(call.battleId()).isEqualTo(OLD_BATTLE);
                assertThat(call.placement().getRpcHost()).as("直拨旧场落点记录里的地址").isEqualTo("10.0.0.9");
                assertThat(call.observerId()).isEqualTo(A);
                assertThat(call.reason()).isEqualTo("enter_gather");
            });
            assertThat(rig.spectate.calls).as("%s：删的是读到的那个值", entry).contains("release(1001," + mark + ")");
            assertThat(rig.spectate.markCount()).as(entry).isZero();
            // 开局成功：补写落点之后公开，是整次 gather 的最后一步
            assertThat(events.subList(events.size() - 2, events.size())).as("%s：先有最终落点、再进索引", entry)
                    .containsExactly("placement.write:" + battle + "#1", "spectate.publish:" + battle);
            assertThat(rig.spectate.watchable()).as("%s：旧场 + 新场，新场的分数是它的 created_at_ms", entry).containsExactly(battle, id(OLD_BATTLE));
            assertThat(rig.spectate.scoreOf(battle)).hasValue(rig.f.placements.stored(result.battleId()).orElseThrow().getCreatedAtMs());
            assertThat(rig.f.count(EVICTIONS, "reason", "enter_gather", "result", "removed")).as(entry).isEqualTo(1.0);
            assertThat(rig.f.count(ANOMALIES, "reason", "publish_failed")).as(entry).isZero();
        });
    }

    @Test
    void 凑单的成员正在观战旧场_入口清退他_开局成功后新战斗的落点可读并进了可观战索引() {
        Rig rig = new Rig();
        rig.watchingOldBattle(A);
        rig.f.scene.name(A, "甲").name(B, "乙");
        GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = rig.run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(rig.observers.removes()).extracting(Call::battleId, Call::observerId, Call::reason)
                .containsExactly(tuple(OLD_BATTLE, A, "enter_gather"));
        assertThat(rig.observers.adds()).as("清退不会把谁登记成观众").isEmpty();
        assertThat(rig.spectate.markOf(A)).isEmpty();
        assertThat(rig.spectate.calls.stream().filter(call -> call.startsWith("read(")).toList()).as("B 没在观战：不为他读任何落点")
                .containsExactly("read(" + id(OLD_BATTLE) + ")");

        BattlePlacement started = rig.f.placements.stored(result.battleId()).orElseThrow();
        assertThat(started.getBattleNodeId()).isEqualTo(1);
        assertThat(started.getMode()).isEqualTo(ONE_V_ONE);
        assertThat(started.getPlayerNamesList()).containsExactly("甲", "乙");
        assertThat(rig.spectate.watchable()).as("旧场 + 新场").containsExactly(id(result.battleId()), id(OLD_BATTLE));
        assertThat(rig.spectate.calls).contains("publish(" + id(result.battleId()) + "#1)");
        assertThat(rig.f.placements.stored(OLD_BATTLE)).as("清退不动旧场的落点").isPresent();
        assertThat(rig.f.ticket(A).state()).isEqualTo(TicketState.READY);
    }

    // ================================================================ 失败路径

    @Test
    void 开局失败并回滚_不登记新的可观战战斗_入口已清退的观众不因回滚而恢复() {
        Map<String, Consumer<GatherFixture>> failures = Map.of(
                "prepare_failed", f -> f.scene.prepareTip(B, 1006),
                "create_rejected", f -> f.battleA.nextCreate(FakeBattleNode.rejected(1003)),
                "create_failed", f -> f.battleA.nextCreateFails(() -> new IllegalStateException("battle 节点开局失败"), false),
                "not_allocatable", f -> f.battleA.nextCreate(FakeBattleNode.notAllocatable("closed")),
                "index_failed", f -> f.placements.failWrites = 1);

        failures.forEach((outcome, inject) -> {
            Rig rig = new Rig();
            rig.watchingOldBattle(A);
            GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);
            inject.accept(rig.f);

            GatherResult result = rig.run(plan);

            assertThat(result.ok()).as(outcome).isFalse();
            assertThat(result.outcome().label()).isEqualTo(outcome);
            assertThat(rig.spectate.watchable()).as("%s：开局失败不得新登记可观战战斗（索引里只剩旧场）", outcome).containsExactly(id(OLD_BATTLE));
            assertThat(rig.spectate.calls).as("%s：失败路径不调 onStarted", outcome).noneMatch(call -> call.startsWith("publish("));
            assertThat(rig.f.placements.stored(result.battleId())).as("%s：没建成的房间不留落点", outcome).isEmpty();
            assertThat(rig.spectate.markOf(A)).as("%s：已清退的观众不因回滚而恢复", outcome).isEmpty();
            assertThat(rig.observers.removes()).as(outcome).singleElement().satisfies(call -> assertThat(call.reason()).isEqualTo("enter_gather"));
            assertThat(rig.observers.adds()).as("%s：回滚不会把他重新挂回旧场", outcome).isEmpty();
            assertThat(rig.spectateWrites()).as("%s：观战侧只发生过一次删标记", outcome).containsExactly("spectate.release:1001");
            assertThat(rig.f.scene.frozen()).as("%s：回滚解冻了参战者", outcome).isEmpty();
        });
    }

    @Test
    void 建房结局不明且销毁也失败_房间可能活着_落点保留但不公开_清退照样不恢复() {
        Rig rig = new Rig();
        rig.watchingOldBattle(A);
        GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);
        rig.f.battleA.nextCreateFails(() -> new TimeoutException("DEADLINE_EXCEEDED"), true);
        rig.f.battleA.nextDestroyFails(() -> new IllegalStateException("断连"));

        GatherResult result = rig.run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_FAILED_ROOM_ALIVE);
        assertThat(rig.f.placements.stored(result.battleId())).as("补签靠它回到这一局").isPresent();
        assertThat(rig.spectate.isWatchable(result.battleId())).as("没有走到开局成功：不进列表与随机选场").isFalse();
        assertThat(rig.spectate.watchable()).containsExactly(id(OLD_BATTLE));
        assertThat(rig.spectate.markOf(A)).isEmpty();
    }

    @Test
    void 第2_5步之前就失败_发号失败或没有可分配的battle节点_钩子没被调_观众没被清退() {
        Map<String, Consumer<GatherFixture>> failures = Map.of(
                "internal", f -> f.leaseValid = false,
                "no_battle_node", f -> f.battleNodes.set(GatherFixture.NODE_A.toBuilder().setAccepting(false).build()));

        failures.forEach((outcome, inject) -> {
            Rig rig = new Rig();
            String mark = rig.watchingOldBattle(A);
            GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);
            inject.accept(rig.f);

            GatherResult result = rig.run(plan);

            assertThat(result.outcome().label()).isEqualTo(outcome);
            assertThat(rig.spectate.calls).as("%s：清退在选好节点、定好期限之后才做；这里还没走到", outcome).isEmpty();
            assertThat(rig.observers.calls).as(outcome).isEmpty();
            assertThat(rig.spectate.markOf(A)).as("%s：他还在看旧场（票已回队首，下次成组时再清退）", outcome).contains(mark);
            assertThat(rig.f.ticket(A).state()).isEqualTo(TicketState.QUEUED);
        });
    }

    @Test
    void 拿不到在途许可的过载收尾_没进管线_不清退也不公开() {
        Rig rig = new Rig();
        String mark = rig.watchingOldBattle(A);
        GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = rig.f.pipeline().overloaded(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.OVERLOADED);
        assertThat(rig.spectate.calls).as("过载时没冻结任何人、也不会开局：没有理由打断他的观战").isEmpty();
        assertThat(rig.observers.calls).isEmpty();
        assertThat(rig.spectate.markOf(A)).contains(mark);
        assertThat(rig.spectate.watchable()).containsExactly(id(OLD_BATTLE));
    }

    // ================================================================ 公开

    @Test
    void 落点在建房期间被懒剔除_开局成功后的补写把它找回来_并照常登记进索引() {
        Rig rig = new Rig();
        GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);
        // 建房窗口里有人按 battle_id 观战、battle 回房间不存在、163 把落点与成员一起剔除了（attempt 守护命中的那种剔除）
        rig.f.battleCalls.register(TARGET_A, new DuringCreate(rig.f.battleA, request -> {
            long battleId = request.getBattleId();
            assertThat(rig.f.placements.stored(battleId)).as("落点必须先于建房写入").isPresent();
            assertThat(rig.spectate.evict(new SpectateStore.Eviction.Dead(battleId, 1), GatherFixture.d())).isTrue();
            assertThat(rig.f.placements.stored(battleId)).isEmpty();
        }));

        GatherResult result = rig.run(plan);

        assertThat(result.ok()).isTrue();
        String battle = id(result.battleId());
        BattlePlacement restored = rig.f.placements.stored(result.battleId()).orElseThrow();
        assertThat(restored.getBattleNodeId()).as("补签定位靠的落点被补回，指向实际建房的节点").isEqualTo(1);
        assertThat(restored.getAttempt()).isEqualTo(1);
        assertThat(rig.spectate.watchable()).as("补写之后才公开：attempt 对得上，照常进索引").containsExactly(battle);
        assertThat(rig.f.events).containsSubsequence("placement.write:" + battle + "#1", "battle[a].create:" + battle, "placement.evict:" + battle,
                "placement.write:" + battle + "#1", "spectate.publish:" + battle);
    }

    @Test
    void 换节点重试成功_公开的是改写到新节点之后的那条落点() {
        Rig rig = new Rig();
        rig.f.addBattleB();
        GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);
        rig.f.battleA.nextCreate(FakeBattleNode.notAllocatable("closed"));

        GatherResult result = rig.run(plan);

        assertThat(result.ok()).isTrue();
        String battle = id(result.battleId());
        assertThat(rig.spectate.calls).as("按 attempt = 2 登记：与库里最终那条落点一致").contains("publish(" + battle + "#2)");
        assertThat(rig.spectate.watchable()).containsExactly(battle);
        assertThat(rig.f.placements.stored(result.battleId()).orElseThrow().getBattleNodeId()).isEqualTo(2);
        List<String> events = rig.f.events;
        assertThat(events.subList(events.size() - 3, events.size())).containsExactly("battle[b].create:" + battle, "placement.write:" + battle + "#2",
                "spectate.publish:" + battle);
    }

    @Test
    void 登记索引失败_仍是成功开局_票据ready_落点在_只是不进列表() {
        Rig rig = new Rig();
        GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);
        rig.spectate.faults.failNext("publish");

        GatherResult result = rig.run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(result.outcome()).isEqualTo(GatherOutcome.SUCCESS);
        assertThat(rig.f.battleA.creates).hasSize(1);
        assertThat(rig.f.battleA.destroys).isEmpty();
        assertThat(rig.f.scene.frozen()).as("不解冻").containsOnlyKeys(A, B);
        assertThat(rig.f.placements.stored(result.battleId())).as("补签与按战斗号观战靠的落点还在").isPresent();
        assertThat(rig.spectate.watchable()).isEmpty();
        assertThat(rig.f.ticket(A).state()).isEqualTo(TicketState.READY);
        assertThat(rig.f.ticket(B).state()).isEqualTo(TicketState.READY);
        assertThat(rig.f.count(ANOMALIES, "reason", "publish_failed")).isEqualTo(1.0);
    }

    @Test
    void 成功后的补写没写进去_建房前写的那条还在_照常公开() {
        Rig rig = new Rig();
        AtomicInteger writes = new AtomicInteger();
        rig.f.placementPort = new PlacementStore() {
            @Override
            public boolean write(BattlePlacement placement) {
                return writes.incrementAndGet() != 2 && rig.f.placements.write(placement); // 第二次（补写）没发出去就失败了
            }

            @Override
            public void delete(long battleId) {
                rig.f.placements.delete(battleId);
            }

            @Override
            public Read read(long battleId, Deadline d) {
                return rig.f.placements.read(battleId, d);
            }
        };
        GatherPlan plan = rig.f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = rig.run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(writes.get()).isEqualTo(2);
        assertThat(rig.spectate.watchable()).as("公开只看库里的落点是不是这一次的 attempt：预写的那条就是").containsExactly(id(result.battleId()));
        assertThat(rig.f.count(ANOMALIES, "reason", "publish_failed")).isZero();
    }

    // ================================================================ 清退失败不阻断开局

    @Test
    void 清退的RemoveObserver没调通_读标记失败_直拨器抛异常_开局都照常() {
        // 1) RPC 结局不明：标记照删，开局照常
        Rig unknown = new Rig();
        unknown.watchingOldBattle(A);
        unknown.observers.nextRemove(new Outcome.Unknown("超时"));
        assertThat(unknown.run(unknown.f.popped(ONE_V_ONE, 0, A, B)).ok()).isTrue();
        assertThat(unknown.spectate.markOf(A)).isEmpty();
        assertThat(unknown.f.count(EVICTIONS, "reason", "enter_gather", "result", "rpc_failed")).isEqualTo(1.0);

        // 2) 读全员标记失败：不清退、标记保留，开局照常
        Rig unreadable = new Rig();
        String mark = unreadable.watchingOldBattle(A);
        unreadable.spectate.faults.failNext("marksOf");
        assertThat(unreadable.run(unreadable.f.popped(ONE_V_ONE, 0, A, B)).ok()).isTrue();
        assertThat(unreadable.spectate.markOf(A)).contains(mark);
        assertThat(unreadable.observers.calls).isEmpty();
        assertThat(unreadable.f.count(ANOMALIES, "reason", "mark_read_failed")).isEqualTo(1.0);
        assertThat(unreadable.spectate.watchable()).as("公开不受清退失败影响").hasSize(2);

        // 3) 直拨器违约抛异常：钩子自己吞掉，开局照常
        Rig broken = new Rig();
        broken.watchingOldBattle(A);
        broken.observers.beforeRemove = call -> {
            throw new IllegalStateException("直拨器的 bug");
        };
        GatherResult result = broken.run(broken.f.popped(ONE_V_ONE, 0, A, B));
        assertThat(result.ok()).isTrue();
        assertThat(broken.f.scene.frozen()).containsOnlyKeys(A, B);
        assertThat(broken.spectate.isWatchable(result.battleId())).isTrue();
        assertThat(broken.spectate.markOf(A)).as("按没调通收场：标记照删").isEmpty();
        assertThat(broken.f.count(EVICTIONS, "reason", "enter_gather", "result", "rpc_failed")).isEqualTo(1.0);
    }

    /** 把调用原样转给假 battle 节点；建房在假节点上生效之后、应答交回管线之前，先跑一段测试代码（模拟「建房在途期间世界变了」）。 */
    private static final class DuringCreate implements BattleNodeService {

        private final BattleNodeService delegate;
        private final Consumer<CreateBattleRequest> duringCreate;

        DuringCreate(BattleNodeService delegate, Consumer<CreateBattleRequest> duringCreate) {
            this.delegate = delegate;
            this.duringCreate = duringCreate;
        }

        @Override
        public CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request) {
            CompletableFuture<CreateBattleResult> created = delegate.createBattle(request);
            duringCreate.accept(request);
            return created;
        }

        @Override
        public CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request) {
            return delegate.destroyBattle(request);
        }

        @Override
        public CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request) {
            return delegate.issueBattleTicket(request);
        }

        @Override
        public CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request) {
            return delegate.addObserver(request);
        }

        @Override
        public CompletableFuture<Empty> removeObserver(RemoveObserverRequest request) {
            return delegate.removeObserver(request);
        }
    }
}
