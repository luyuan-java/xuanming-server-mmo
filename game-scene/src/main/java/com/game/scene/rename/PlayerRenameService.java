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

    /** 入口（场景逻辑线程）：同步前置校验，立即回受理或拒绝；受理后异步推进内容安全与 SQL 提交并推送 429。 */
    public RenamePlayerResponse requestRename(SceneWorld world, ScenePlayer player, RenamePlayerRequest request) {
        String normalized = precheck(player, request == null ? "" : request.getName(), clock.epochMillis());
        int err = precheckError(player, request == null ? "" : request.getName(), normalized, clock.epochMillis());
        if (err != OK) {
            return RenamePlayerResponse.newBuilder().setErrorMessage(tip(err)).build();
        }
        player.setRenameInFlight(true);
        long playerId = player.playerId();
        long ownerEpoch = player.ownerEpoch();

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
                    logicExecutor.execute(() -> onContentChecked(world, playerId, ownerEpoch, normalized, finalResp));
                });
            } catch (RejectedExecutionException full) {
                player.setRenameInFlight(false);
                log.warn("Role rename content guard executor full player={}", Long.toUnsignedString(playerId));
                return RenamePlayerResponse.newBuilder().setErrorMessage(tip(ROLE_NAME_CHECK_UNAVAILABLE)).build();
            }
        } else {
            dispatchStoreRename(world, player, normalized);
        }
        return RenamePlayerResponse.newBuilder().setErrorMessage(tip(OK)).build();
    }

    private void onContentChecked(SceneWorld world, long playerId, long ownerEpoch, String normalized,
                                  CheckTextResponse resp) {
        ScenePlayer current = world.playerById(playerId);
        if (current == null || current.ownerEpoch() != ownerEpoch) {
            return;
        }
        if (resp == null) {
            current.setRenameInFlight(false);
            pushFailure(world, current, ROLE_NAME_CHECK_UNAVAILABLE, 0);
            return;
        }
        if (resp.getVerdict() != TextVerdict.TEXT_VERDICT_PASS) {
            current.setRenameInFlight(false);
            pushFailure(world, current, ROLE_NAME_SENSITIVE, 0);
            return;
        }
        // 内容安全回调时再次核对状态（期间可能冻结或改名卡被消耗）。
        int recheck = precheckAfterInFlight(current, normalized, clock.epochMillis());
        if (recheck != OK) {
            current.setRenameInFlight(false);
            pushFailure(world, current, recheck, 0);
            return;
        }
        dispatchStoreRename(world, current, normalized);
    }

    private void dispatchStoreRename(SceneWorld world, ScenePlayer player, String normalized) {
        long playerId = player.playerId();
        long ownerEpoch = player.ownerEpoch();
        PlayerRenameTables.Rule rule = tables.rule();
        world.repository().renamePlayer(playerId, ownerEpoch, normalized, rule.cooldownMs(), rule.oldNameHoldMs(),
                outcome -> onStoreRenameDone(world, playerId, ownerEpoch, outcome));
    }

    private void onStoreRenameDone(SceneWorld world, long playerId, long ownerEpoch, RenameOutcome outcome) {
        ScenePlayer current = world.playerById(playerId);
        if (current == null || current.ownerEpoch() != ownerEpoch) {
            return;
        }
        current.setRenameInFlight(false);
        if (outcome == null) {
            pushFailure(world, current, SERVICE_UNAVAILABLE, 0);
            return;
        }
        if (outcome.status() == RenameStatus.OK) {
            PlayerRenameTables.Rule rule = tables.rule();
            Map<Integer, Long> removed = bags.removeClamped(current, BagType.INVENTORY,
                    Map.of(rule.renameItemId(), rule.renameItemCount()),
                    AssetAudit.Reason.ITEM_DESTROY, 0L, "player_rename");
            long deducted = removed.getOrDefault(rule.renameItemId(), 0L);
            if (deducted < rule.renameItemCount()) {
                log.warn("Role rename committed in SQL but rename card short player={} expected={} actual={}",
                        Long.toUnsignedString(playerId), rule.renameItemCount(), deducted);
            }
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
        return precheckAfterInFlight(player, normalized, nowMs);
    }

    private int precheckAfterInFlight(ScenePlayer player, String normalized, long nowMs) {
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
        long cardCount = player.bags().bag(BagType.INVENTORY).total(rule.renameItemId());
        if (cardCount < rule.renameItemCount()) {
            return ROLE_RENAME_NO_CARD;
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
