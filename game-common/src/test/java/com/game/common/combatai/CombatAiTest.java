package com.game.common.combatai;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.combatai.BehaviorTree.Builder;
import com.game.common.combatai.BehaviorTree.Status;
import com.game.common.combatai.BehaviorTree.TreeDef;
import com.game.common.combatai.Fsm.EventDesc;
import com.game.common.combatai.Hfsm.EventOutcome;
import com.game.common.combatai.Hfsm.StateConfig;
import com.game.common.combatai.MonsterUtilityAi.ActionCandidate;
import com.game.common.combatai.MonsterUtilityAi.PairwiseSample;
import com.game.common.combatai.MonsterUtilityAi.Selection;
import com.game.common.combatai.MonsterUtilityAi.TrainConfig;
import com.game.common.combatai.MonsterUtilityAi.TrainResult;
import com.game.common.combatai.StateMutexMatrix.RefCountedStateBitset;
import com.game.common.combatai.StateMutexMatrix.StateExitHookRegistry;
import com.game.common.combatai.StateMutexMatrix.StateExitReason;
import com.game.common.combatai.StateMutexMatrix.StateMode;
import com.game.common.combatai.StateMutexMatrix.StateRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 纯 JDK 21 战斗 AI 与状态机原语自检（对齐 C++ combat_ai_test 与 Go combatai_test）。 */
class CombatAiTest {

    @Test
    void 状态互斥矩阵_两阶段零副作用与引用计数位图及退出钩子() {
        int combat = 0;
        int teamFollow = 1;
        int mounted = 2;
        int silence = 3;
        int stateCount = 4;

        int useSkill = 0;
        int mount = 2;

        int tipSuccess = 1000;
        int tipSilenced = 1001;
        int tipInCombat = 1002;
        int tipUnknownAction = 9001;
        int tipInvalidState = 9002;

        StateMutexMatrix matrix = new StateMutexMatrix();
        int invalid = matrix.loadRules(List.of(
                new StateRule(useSkill, combat, StateMode.ALLOW, 0),
                new StateRule(useSkill, teamFollow, StateMode.INTERRUPT, 0),
                new StateRule(useSkill, mounted, StateMode.INTERRUPT, 0),
                new StateRule(useSkill, silence, StateMode.BLOCK, tipSilenced),
                new StateRule(mount, combat, StateMode.BLOCK, tipInCombat)
        ));
        assertThat(invalid).isZero();

        RefCountedStateBitset states = new RefCountedStateBitset();
        assertThat(states.acquire(teamFollow)).isTrue();
        assertThat(states.acquire(mounted)).isTrue();
        assertThat(states.acquire(silence)).isTrue();
        assertThat(states.acquire(silence)).isFalse();
        assertThat(states.refCount(silence)).isEqualTo(2);

        List<Integer> exited = new ArrayList<>();
        StateExitHookRegistry<Long> hooks = new StateExitHookRegistry<>();
        hooks.register(teamFollow, (actor, reason) -> {
            assertThat(reason).isEqualTo(StateExitReason.INTERRUPTED);
            assertThat(states.has(teamFollow)).isFalse();
            exited.add(teamFollow);
        });
        hooks.register(mounted, (actor, reason) -> {
            assertThat(reason).isEqualTo(StateExitReason.INTERRUPTED);
            assertThat(states.has(mounted)).isFalse();
            exited.add(mounted);
        });

        // 被沉默时两阶段门禁直接拒绝，不得提前打断跟随与骑乘。
        int blocked = matrix.tryPerform(
                states.mask(), useSkill, combat, stateCount,
                tipSuccess, tipUnknownAction, tipInvalidState,
                s -> {
                    if (states.clearState(s)) {
                        hooks.notifyExited(100L, s, StateExitReason.INTERRUPTED);
                    }
                },
                states::acquire);
        assertThat(blocked).isEqualTo(tipSilenced);
        assertThat(states.has(teamFollow)).isTrue();
        assertThat(states.has(mounted)).isTrue();
        assertThat(states.has(combat)).isFalse();
        assertThat(exited).isEmpty();

        // 释放两层沉默后施法：先打断 TeamFollow 与 Mounted 并派发退出钩子，再入 Combat。
        assertThat(states.release(silence)).isFalse();
        assertThat(states.has(silence)).isTrue();
        assertThat(states.release(silence)).isTrue();
        assertThat(states.has(silence)).isFalse();

        int ok = matrix.tryPerform(
                states.mask(), useSkill, combat, stateCount,
                tipSuccess, tipUnknownAction, tipInvalidState,
                s -> {
                    if (states.clearState(s)) {
                        hooks.notifyExited(100L, s, StateExitReason.INTERRUPTED);
                    }
                },
                states::acquire);
        assertThat(ok).isEqualTo(tipSuccess);
        assertThat(states.has(teamFollow)).isFalse();
        assertThat(states.has(mounted)).isFalse();
        assertThat(states.has(combat)).isTrue();
        assertThat(exited).containsExactly(teamFollow, mounted);

        assertThat(matrix.canPerform(states.mask(), mount, tipSuccess, tipUnknownAction)).isEqualTo(tipInCombat);
        assertThat(matrix.canPerform(states.mask(), 999, tipSuccess, tipUnknownAction)).isEqualTo(tipUnknownAction);
        assertThat(matrix.tryPerform(
                states.mask(), useSkill, stateCount, stateCount,
                tipSuccess, tipUnknownAction, tipInvalidState,
                s -> { throw new AssertionError("不应打断"); },
                s -> { throw new AssertionError("不应入态"); })).isEqualTo(tipInvalidState);
    }

    @Test
    void 有限状态机_完整looplab特性_五阶段钩子_取消_异步提交与Mermaid导出() {
        final class DoorCtx {
            boolean cancelBefore;
            boolean asyncLeave;
            final List<String> steps = new ArrayList<>();
        }

        Fsm<String, String, DoorCtx> door = new Fsm<>("Closed");
        door.addEvents(
                EventDesc.of("open", List.of("Closed"), "Open"),
                EventDesc.of("close", List.of("Open"), "Closed"),
                EventDesc.of("lock", List.of("Closed", "Open"), "Locked")
        );
        door.onBeforeEvent("open", (ev, c) -> {
            c.steps.add("before:open");
            if (c.cancelBefore) {
                ev.cancel(7004);
            }
        });
        door.onBeforeAnyEvent((ev, c) -> c.steps.add("before:any"));
        door.onLeaveState("Closed", (ev, c) -> {
            c.steps.add("leave:closed");
            if (c.asyncLeave) {
                ev.async();
            }
        });
        door.onEnterState("Open", (ev, c) -> c.steps.add("enter:open"));
        door.onAfterEvent("open", (ev, c) -> c.steps.add("after:open"));
        door.onAfterAnyEvent((ev, c) -> c.steps.add("after:any"));

        DoorCtx ctx = new DoorCtx();
        assertThat(door.availableTransitions(ctx)).containsExactly("open", "lock");

        ctx.cancelBefore = true;
        Fsm.Result canceled = door.triggerEx("open", ctx);
        assertThat(canceled.status()).isEqualTo(Fsm.Status.CANCELED);
        assertThat(canceled.cancelError()).isEqualTo(7004);
        assertThat(door.is("Closed")).isTrue();
        assertThat(ctx.steps).containsExactly("before:open");

        ctx.cancelBefore = false;
        ctx.asyncLeave = true;
        ctx.steps.clear();
        Fsm.Result asyncRes = door.triggerEx("open", ctx);
        assertThat(asyncRes.pendingAsync()).isTrue();
        assertThat(door.hasPendingTransition()).isTrue();
        assertThat(door.is("Closed")).isTrue();
        assertThat(door.triggerEx("lock", ctx).status()).isEqualTo(Fsm.Status.IN_PROGRESS);

        Fsm.Result committed = door.commitPendingTransition(ctx);
        assertThat(committed.ok()).isTrue();
        assertThat(door.hasPendingTransition()).isFalse();
        assertThat(door.is("Open")).isTrue();
        assertThat(ctx.steps).containsExactly(
                "before:open", "before:any", "leave:closed", "enter:open", "after:open", "after:any");

        String mermaid = door.exportMermaid(s -> s, e -> e);
        assertThat(mermaid)
                .contains("stateDiagram-v2")
                .contains("Closed --> Open : open")
                .contains("Open --> Locked : lock");
    }

    @Test
    void 有限状态机_回调内重入通过有界延迟队列顺次消化() {
        final class CastCtx {
            boolean hasMana;
            boolean skipRecovery;
            final List<String> trace = new ArrayList<>();
        }
        Fsm<String, String, CastCtx> fsm = new Fsm<>("Idle");
        fsm.addTransition("Idle", "start", "Windup", c -> c.hasMana, (f, t, e, c) -> c.trace.add("tx:start"));
        fsm.addTransition("Windup", "windupDone", "Recovery");
        fsm.addTransition("Recovery", "recoveryDone", "Idle");
        fsm.addAnyTransition("die", "Dead");
        fsm.onLeave("Idle", c -> c.trace.add("leave:idle"));
        fsm.onEnter("Windup", c -> c.trace.add("enter:windup"));
        fsm.onEnter("Recovery", c -> {
            c.trace.add("enter:recovery");
            if (c.skipRecovery) {
                assertThat(fsm.trigger("recoveryDone", c)).isTrue();
            }
        });

        CastCtx ctx = new CastCtx();
        assertThat(fsm.canTrigger("start", ctx)).isFalse();
        assertThat(fsm.trigger("start", ctx)).isFalse();
        assertThat(ctx.trace).isEmpty();

        ctx.hasMana = true;
        ctx.skipRecovery = true;
        assertThat(fsm.trigger("start", ctx)).isTrue();
        assertThat(ctx.trace).containsExactly("leave:idle", "tx:start", "enter:windup");
        assertThat(fsm.trigger("windupDone", ctx)).isTrue();
        assertThat(fsm.is("Idle")).isTrue();
        assertThat(fsm.trigger("die", ctx)).isTrue();
        assertThat(fsm.is("Dead")).isTrue();
        assertThat(fsm.trigger("die", ctx)).isFalse();
    }

    @Test
    void 分层状态机_LCA切换保留共享父态与事件向上冒泡() {
        int root = 0;
        int patrol = 1;
        int combat = 2;
        int phase1 = 3;
        int phase2Enrage = 4;
        int dead = 5;

        final class BossCtx {
            int hpPercent = 100;
            int combatEnterCount;
            int combatExitCount;
        }

        Hfsm<BossCtx, Integer> hfsm = new Hfsm<>();
        hfsm.addState(root, StateConfig.<BossCtx, Integer>create()
                .initialChild(patrol)
                .onEvent((ev, c) -> ev == 3 ? EventOutcome.transitionTo(dead) : EventOutcome.ignored()));
        hfsm.addState(patrol, StateConfig.<BossCtx, Integer>create()
                .parent(root)
                .onEvent((ev, c) -> ev == 1 ? EventOutcome.transitionTo(combat) : EventOutcome.ignored()));
        hfsm.addState(combat, StateConfig.<BossCtx, Integer>create()
                .parent(root)
                .initialChild(phase1)
                .onEnter(c -> c.combatEnterCount++)
                .onExit(c -> c.combatExitCount++));
        hfsm.addState(phase1, StateConfig.<BossCtx, Integer>create()
                .parent(combat)
                .onUpdate((c, dt) -> c.hpPercent <= 50 ? phase2Enrage : Hfsm.INVALID_STATE));
        hfsm.addState(phase2Enrage, StateConfig.<BossCtx, Integer>create().parent(combat));
        hfsm.addState(dead, StateConfig.<BossCtx, Integer>create().parent(root));

        BossCtx ctx = new BossCtx();
        assertThat(hfsm.start(root, ctx)).isTrue();
        assertThat(hfsm.currentLeaf()).isEqualTo(patrol);
        assertThat(hfsm.isInState(root)).isTrue();
        assertThat(hfsm.isInState(combat)).isFalse();

        assertThat(hfsm.dispatch(1, ctx)).isTrue();
        assertThat(hfsm.currentLeaf()).isEqualTo(phase1);
        assertThat(hfsm.isInState(combat)).isTrue();
        assertThat(ctx.combatEnterCount).isEqualTo(1);

        ctx.hpPercent = 40;
        hfsm.update(ctx, 50);
        assertThat(hfsm.currentLeaf()).isEqualTo(phase2Enrage);
        assertThat(hfsm.isInState(combat)).isTrue();
        assertThat(ctx.combatEnterCount).isEqualTo(1);
        assertThat(ctx.combatExitCount).isZero();

        assertThat(hfsm.dispatch(3, ctx)).isTrue();
        assertThat(hfsm.currentLeaf()).isEqualTo(dead);
        assertThat(hfsm.isInState(combat)).isFalse();
        assertThat(ctx.combatExitCount).isEqualTo(1);
    }

    @Test
    void 行为树共享拓扑_响应式抢占Halt与数据训练定点打分器() {
        List<PairwiseSample> samples = List.of(
                new PairwiseSample(new int[] {10000, 7000, 10000, 10000}, new int[] {0, 2000, 0, 0}, 1.0),
                new PairwiseSample(new int[] {10000, 6000, 10000, 0}, new int[] {0, 3000, 0, 0}, 1.0),
                new PairwiseSample(new int[] {0, 8500, 10000, 10000}, new int[] {0, 1500, 10000, 0}, 1.0),
                new PairwiseSample(new int[] {10000, 5000, 10000, 0}, new int[] {0, 5000, 0, 0}, 1.0),
                new PairwiseSample(new int[] {0, 9000, 0, 10000}, new int[] {0, 1000, 0, 0}, 1.0),
                new PairwiseSample(new int[] {10000, 4000, 10000, 0}, new int[] {0, 4000, 10000, 0}, 1.0)
        );
        TrainResult trained = MonsterUtilityAi.trainPairwiseLinearModel(4, samples, TrainConfig.defaults());
        assertThat(trained.metrics().finalLoss()).isLessThan(trained.metrics().initialLoss());
        assertThat(trained.metrics().accuracyPermyriad()).isEqualTo(10000);

        List<ActionCandidate> candidates = List.of(
                new ActionCandidate(101, 2001L, 0, new int[] {0, 1000, 0, 0}),
                new ActionCandidate(202, 2002L, 1, new int[] {10000, 7500, 10000, 10000})
        );
        Optional<Selection> pick = MonsterUtilityAi.selectBestCandidate(candidates, trained.model());
        assertThat(pick).isPresent();
        assertThat(pick.get().index()).isEqualTo(1);

        // 同分决胜按 formationSlot 升序
        List<ActionCandidate> tied = List.of(
                new ActionCandidate(101, 3002L, 2, new int[] {5000, 5000, 5000, 0}),
                new ActionCandidate(101, 3001L, 1, new int[] {5000, 5000, 5000, 0})
        );
        assertThat(MonsterUtilityAi.selectBestCandidate(tied, trained.model())).get()
                .extracting(Selection::index).isEqualTo(1);

        final int bbLastAction = 1;
        final int bbLastTarget = 2;
        final class MonsterCtx {
            boolean emergencyShieldNeeded;
            int channelTicksRemaining = 2;
            int haltCount;
            int shieldCasts;
        }

        Builder<MonsterCtx> b = new Builder<>();
        int shieldBranch = b.sequence(
                b.condition((m, bb) -> m.emergencyShieldNeeded),
                b.cooldown(3, b.action((m, bb) -> {
                    m.shieldCasts++;
                    bb.setLong(bbLastAction, 900L);
                    return Status.SUCCESS;
                }))
        );
        int channelBranch = b.sequence(
                b.action(
                        (m, bb) -> {
                            if (m.channelTicksRemaining > 1) {
                                m.channelTicksRemaining--;
                                return Status.RUNNING;
                            }
                            m.channelTicksRemaining = 2;
                            return Status.SUCCESS;
                        },
                        (m, bb) -> {
                            m.haltCount++;
                            m.channelTicksRemaining = 2;
                        }),
                b.action((m, bb) -> {
                    Optional<Selection> chosen = MonsterUtilityAi.selectBestCandidate(candidates, trained.model());
                    if (chosen.isEmpty()) {
                        return Status.FAILURE;
                    }
                    ActionCandidate best = candidates.get(chosen.get().index());
                    bb.setLong(bbLastAction, Integer.toUnsignedLong(best.actionId()));
                    bb.setLong(bbLastTarget, best.targetId());
                    return Status.SUCCESS;
                })
        );
        int root = b.reactiveSelector(shieldBranch, channelBranch);
        TreeDef<MonsterCtx> tree = b.build(root).orElseThrow();

        MonsterCtx mobA = new MonsterCtx();
        MonsterCtx mobB = new MonsterCtx();
        BehaviorTree.State stateA = tree.createState();
        BehaviorTree.State stateB = tree.createState();

        assertThat(tree.tick(mobA, stateA)).isEqualTo(Status.RUNNING);
        assertThat(tree.tick(mobB, stateB)).isEqualTo(Status.RUNNING);

        mobA.emergencyShieldNeeded = true;
        assertThat(tree.tick(mobA, stateA)).isEqualTo(Status.SUCCESS);
        assertThat(mobA.shieldCasts).isEqualTo(1);
        assertThat(mobA.haltCount).isEqualTo(1);
        assertThat(stateA.blackboard().getLongOr(bbLastAction, 0L)).isEqualTo(900L);

        assertThat(tree.tick(mobB, stateB)).isEqualTo(Status.SUCCESS);
        assertThat(mobB.haltCount).isZero();
        assertThat(stateB.blackboard().getLongOr(bbLastAction, 0L)).isEqualTo(202L);
        assertThat(stateB.blackboard().getLongOr(bbLastTarget, 0L)).isEqualTo(2002L);

        assertThat(tree.tick(mobA, stateA)).isEqualTo(Status.RUNNING);
        assertThat(mobA.shieldCasts).isEqualTo(1);
    }
}
