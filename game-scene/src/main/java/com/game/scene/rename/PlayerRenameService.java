package com.game.scene.rename;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.contract.MessageIdRegistry;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.RenameOutcome;
import com.game.player.store.PlayerStore.RenameStatus;
import com.game.proto.PlayerRenamedNotify;
import com.game.proto.RenamePlayerRequest;
import com.game.proto.RenamePlayerResponse;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.player.BagType;
import com.game.scene.player.SceneTextGuard;
import com.game.scene.world.SceneClock;
import com.game.scene.world.SceneMessageIds;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.CommonErrorTip;
import com.game.table.LoginErrorTip;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 角色改名服务（{@code SceneClientPlayerCommon.RenamePlayer} 428 / {@code NotifyPlayerRenamed} 429）。
 *
 * <p>流程对齐权威仓 {@code player_rename.cpp}：逻辑线程先做轻量前置检查（冻结、在途、格式、同名、冷却、改名卡充足），
 * 受理后立即回 {@code RenamePlayerResponse{error_message=0}}；再在非逻辑线程做内容安全送检（可选）与带
 * {@code owner_epoch} 围栏的 SQL 原子改名；提交成功回到逻辑线程扣卡、更新内存角色名与冷却时间戳、触发存盘，
 * 并推送 {@code NotifyPlayerRenamed}。
 */
public final class PlayerRenameService {

    private static final Logger log = LoggerFactory.getLogger(PlayerRenameService.class);

    static final int OK = 0;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int ROLE_NAME_INVALID = LoginErrorTip.login_error.kRoleNameInvalid_VALUE;
    static final int ROLE_NAME_TAKEN = LoginErrorTip.login_error.kRoleNameTaken_VALUE;
    static final int ROLE_NAME_SENSITIVE = LoginErrorTip.login_error.kRoleNameSensitive_VALUE;
    static final int ROLE_NAME_CHECK_UNAVAILABLE = LoginErrorTip.login_error.kRoleNameCheckUnavailable_VALUE;
    static final int ROLE_RENAME_COOLDOWN = LoginErrorTip.login_error.kRoleRenameCooldown_VALUE;
    static final int ROLE_RENAME_NO_CARD = LoginErrorTip.login_error.kRoleRenameNoCard_VALUE;
    static final int ROLE_RENAME_IN_PROGRESS = LoginErrorTip.login_error.kRoleRenameInProgress_VALUE;
    static final int ROLE_RENAME_FROZEN = LoginErrorTip.login_error.kRoleRenameFrozen_VALUE;
    static final int ROLE_RENAME_SAME_NAME = LoginErrorTip.login_error.kRoleRenameSameName_VALUE;

    private final PlayerRenameTables tables;
    private final BagService bags;
    private final SceneClock clock;
    private final int notifyPlayerRenamedId;
    private final SceneTextGuard textGuard;
    private final Executor ioExecutor;
    private final Executor logicExecutor;

    public PlayerRenameService(PlayerRenameTables tables, BagService bags, SceneClock clock,
                               MessageIdRegistry registry) {
        this(tables, bags, clock, registry, null, null, null);
    }

    public PlayerRenameService(PlayerRenameTables tables, BagService bags, SceneClock clock,
                               MessageIdRegistry registry, SceneTextGuard textGuard,
                               Executor ioExecutor, Executor logicExecutor) {
        this.tables = Objects.requireNonNull(tables, "tables");
        this.bags = Objects.requireNonNull(bags, "bags");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.notifyPlayerRenamedId = registry.requireId(PlayerRenameFeature.SERVICE, "NotifyPlayerRenamed");
        this.textGuard = textGuard;
        this.ioExecutor = ioExecutor;
        this.logicExecutor = logicExecutor;
    }

    /** 入口（场景逻辑线程）：同步前置校验并预扣改名卡，立即回受理或拒绝；任一步失败退还改名卡，SQL 成功推 429。 */
    public RenamePlayerResponse requestRename(SceneWorld world, ScenePlayer player, RenamePlayerRequest request) {
        String normalized = precheck(player, request == null ? "" : request.getName(), clock.epochMillis());
        int err = precheckError(player, request == null ? "" : request.getName(), normalized, clock.epochMillis());
        if (err != OK) {
            return RenamePlayerResponse.newBuilder().setErrorMessage(tip(err)).build();
        }
        // 受理即在逻辑线程原子预扣改名卡，记录绑定/可交易拆分以便失败时原样退还（绝不把绑定卡洗成可交易卡）
        PlayerRenameTables.Rule rule = tables.rule();
        long boundDeducted = 0L;
        long tradableDeducted = 0L;
        if (rule.renameItemCount() > 0) {
            var inventory = player.bags().bag(BagType.INVENTORY);
            long boundBefore = Math.max(0L, inventory.total(rule.renameItemId()) - inventory.totalTradable(rule.renameItemId()));
            Map<Integer, Long> removed = bags.removeClamped(player, BagType.INVENTORY,
                    Map.of(rule.renameItemId(), rule.renameItemCount()),
                    AssetAudit.Reason.ITEM_DESTROY, 0L, "player_rename");
            long deducted = removed.getOrDefault(rule.renameItemId(), 0L);
            // Bag.drainClamped 优先消耗 bound=true 堆叠，再消耗可交易堆叠
            boundDeducted = Math.min(deducted, boundBefore);
            tradableDeducted = deducted - boundDeducted;
            if (deducted < rule.renameItemCount()) {
                if (deducted > 0) {
                    refundRenameCard(player, rule.renameItemId(), boundDeducted, tradableDeducted);
                }
                return RenamePlayerResponse.newBuilder().setErrorMessage(tip(ROLE_RENAME_NO_CARD)).build();
            }
        }
        player.setRenameInFlight(true);
        long playerId = player.playerId();
        long ownerEpoch = player.ownerEpoch();
        final long finalBoundDeducted = boundDeducted;
        final long finalTradableDeducted = tradableDeducted;

        if (textGuard != null && ioExecutor != null && logicExecutor != null) {
            try {
                ioExecutor.execute(() -> {
                    CheckTextResponse resp = null;
                    try {
                        resp = textGuard.check(TextScene.TEXT_SCENE_ROLE_NAME, playerId, normalized);
                    } catch (RuntimeException e) {
                        log.warn("Role rename content guard failed player={}: {}",
                                Long.toUnsignedString(playerId), e.getMessage());
                    }
                    CheckTextResponse finalResp = resp;
                    try {
                        logicExecutor.execute(() -> onContentChecked(world, playerId, ownerEpoch, normalized,
                                finalBoundDeducted, finalTradableDeducted, finalResp));
                    } catch (RejectedExecutionException stopped) {
                        log.debug("Role rename callback dropped because scene logic loop stopped player={}",
                                Long.toUnsignedString(playerId));
                    }
                });
            } catch (RejectedExecutionException full) {
                player.setRenameInFlight(false);
                refundRenameCard(player, rule.renameItemId(), boundDeducted, tradableDeducted);
                log.warn("Role rename content guard executor full player={}", Long.toUnsignedString(playerId));
                return RenamePlayerResponse.newBuilder().setErrorMessage(tip(ROLE_NAME_CHECK_UNAVAILABLE)).build();
            }
        } else {
            dispatchStoreRename(world, player, normalized, boundDeducted, tradableDeducted);
        }
        return RenamePlayerResponse.newBuilder().setErrorMessage(tip(OK)).build();
    }

    private void onContentChecked(SceneWorld world, long playerId, long ownerEpoch, String normalized,
                                  long boundDeducted, long tradableDeducted, CheckTextResponse resp) {
        ScenePlayer current = world.playerById(playerId);
        PlayerRenameTables.Rule rule = tables.rule();
        if (current == null || current.ownerEpoch() != ownerEpoch) {
            // shortcut: 玩家在改名送检在途期间离线且送检失败时改名卡已预扣无法原位退还，待接入持久化补偿收件箱升级
            if (resp == null || resp.getVerdict() != TextVerdict.TEXT_VERDICT_PASS) {
                log.error("Role rename content check failed after player left scene; card refund skipped player={} item={} bound={} tradable={}",
                        Long.toUnsignedString(playerId), rule.renameItemId(), boundDeducted, tradableDeducted);
            }
            return;
        }
        if (resp == null || resp.getVerdict() == TextVerdict.TEXT_VERDICT_UNSPECIFIED) {
            current.setRenameInFlight(false);
            refundRenameCard(current, rule.renameItemId(), boundDeducted, tradableDeducted);
            pushFailure(world, current, ROLE_NAME_CHECK_UNAVAILABLE, 0);
            return;
        }
        if (resp.getVerdict() != TextVerdict.TEXT_VERDICT_PASS) {
            current.setRenameInFlight(false);
            refundRenameCard(current, rule.renameItemId(), boundDeducted, tradableDeducted);
            pushFailure(world, current, ROLE_NAME_SENSITIVE, 0);
            return;
        }
        // 内容安全回调时再次核对冻结/同名/冷却状态（改名卡已于受理时预扣）。
        int recheck = precheckAfterInFlight(current, normalized, clock.epochMillis(), false);
        if (recheck != OK) {
            current.setRenameInFlight(false);
            refundRenameCard(current, rule.renameItemId(), boundDeducted, tradableDeducted);
            pushFailure(world, current, recheck, 0);
            return;
        }
        dispatchStoreRename(world, current, normalized, boundDeducted, tradableDeducted);
    }

    private void dispatchStoreRename(SceneWorld world, ScenePlayer player, String normalized,
                                     long boundDeducted, long tradableDeducted) {
        long playerId = player.playerId();
        long ownerEpoch = player.ownerEpoch();
        PlayerRenameTables.Rule rule = tables.rule();
        world.repository().renamePlayer(playerId, ownerEpoch, normalized, rule.cooldownMs(), rule.oldNameHoldMs(),
                outcome -> onStoreRenameDone(world, playerId, ownerEpoch, boundDeducted, tradableDeducted, outcome));
    }

    private void onStoreRenameDone(SceneWorld world, long playerId, long ownerEpoch,
                                   long boundDeducted, long tradableDeducted, RenameOutcome outcome) {
        ScenePlayer current = world.playerById(playerId);
        PlayerRenameTables.Rule rule = tables.rule();
        if (current == null || current.ownerEpoch() != ownerEpoch) {
            // shortcut: 玩家在改名 SQL 在途期间离线且改名失败时改名卡已预扣无法原位退还，待接入持久化补偿收件箱升级
            if (outcome == null || outcome.status() != RenameStatus.OK) {
                log.error("Role rename SQL failed after player left scene; card refund skipped player={} status={} item={} bound={} tradable={}",
                        Long.toUnsignedString(playerId), outcome == null ? "null" : outcome.status(),
                        rule.renameItemId(), boundDeducted, tradableDeducted);
            }
            return;
        }
        current.setRenameInFlight(false);
        if (outcome == null) {
            refundRenameCard(current, rule.renameItemId(), boundDeducted, tradableDeducted);
            pushFailure(world, current, SERVICE_UNAVAILABLE, 0);
            return;
        }
        if (outcome.status() == RenameStatus.OK) {
            current.applyRename(outcome.name(), outcome.renamedMs());
            world.requestSave(current);
            PlayerRenamedNotify notify = PlayerRenamedNotify.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setName(outcome.name())
                    .setNextRenameTime(outcome.nextAllowedMs() / 1_000L)
                    .build();
            world.sendTo(current, SceneMessageIds.push(notifyPlayerRenamedId, notify));
            return;
        }
        refundRenameCard(current, rule.renameItemId(), boundDeducted, tradableDeducted);
        int tipId = switch (outcome.status()) {
            case NAME_TAKEN -> ROLE_NAME_TAKEN;
            case SAME_NAME -> ROLE_RENAME_SAME_NAME;
            case COOLDOWN -> ROLE_RENAME_COOLDOWN;
            case FROZEN -> ROLE_RENAME_FROZEN;
            default -> SERVICE_UNAVAILABLE;
        };
        long nextSec = outcome.status() == RenameStatus.COOLDOWN ? outcome.nextAllowedMs() / 1_000L : 0L;
        pushFailure(world, current, tipId, nextSec);
    }

    private void refundRenameCard(ScenePlayer player, int itemId, long boundCount, long tradableCount) {
        refundSlice(player, itemId, boundCount, true);
        refundSlice(player, itemId, tradableCount, false);
    }

    private void refundSlice(ScenePlayer player, int itemId, long count, boolean bound) {
        if (count <= 0L) {
            return;
        }
        var res = bags.addItems(player, BagType.INVENTORY, Map.of(itemId, count), bound,
                AssetAudit.Reason.SYSTEM_GRANT, 0L, "player_rename_refund");
        if (res.tip() == 0) {
            return;
        }
        // 主背包已满时回退临时背包，避免异步改名失败静默销毁改名卡
        var tempRes = bags.addItems(player, BagType.TEMPORARY, Map.of(itemId, count), bound,
                AssetAudit.Reason.SYSTEM_GRANT, 0L, "player_rename_refund_temp");
        if (tempRes.tip() != 0) {
            log.error("Role rename failed and refund card failed in both INVENTORY and TEMPORARY player={} item={} count={} bound={} tip={}/{}",
                    Long.toUnsignedString(player.playerId()), itemId, count, bound, res.tip(), tempRes.tip());
        }
    }

    private String precheck(ScenePlayer player, String rawName, long nowMs) {
        if (player.frozen() || player.renameInFlight()) {
            return null;
        }
        return tables.normalizeAndValidate(rawName);
    }

    private int precheckError(ScenePlayer player, String rawName, String normalized, long nowMs) {
        if (player.frozen()) {
            return ROLE_RENAME_FROZEN;
        }
        if (player.renameInFlight()) {
            return ROLE_RENAME_IN_PROGRESS;
        }
        if (normalized == null) {
            return ROLE_NAME_INVALID;
        }
        return precheckAfterInFlight(player, normalized, nowMs, true);
    }

    private int precheckAfterInFlight(ScenePlayer player, String normalized, long nowMs, boolean checkCard) {
        if (player.frozen()) {
            return ROLE_RENAME_FROZEN;
        }
        String currentName = player.name();
        if (!currentName.isEmpty() && PlayerStore.nameKey(normalized).equals(PlayerStore.nameKey(currentName))) {
            return ROLE_RENAME_SAME_NAME;
        }
        PlayerRenameTables.Rule rule = tables.rule();
        if (rule.cooldownMs() > 0 && player.renamedMs() > 0 && nowMs < player.renamedMs() + rule.cooldownMs()) {
            return ROLE_RENAME_COOLDOWN;
        }
        if (checkCard) {
            long cardCount = player.bags().bag(BagType.INVENTORY).total(rule.renameItemId());
            if (cardCount < rule.renameItemCount()) {
                return ROLE_RENAME_NO_CARD;
            }
        }
        return OK;
    }

    private void pushFailure(SceneWorld world, ScenePlayer player, int tipId, long nextRenameTimeSec) {
        PlayerRenamedNotify notify = PlayerRenamedNotify.newBuilder()
                .setErrorMessage(tip(tipId))
                .setNextRenameTime(nextRenameTimeSec)
                .build();
        world.sendTo(player, SceneMessageIds.push(notifyPlayerRenamedId, notify));
    }
}
