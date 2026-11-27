package com.game.login.hosted;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离线托管归位对账器（对齐主仓 {@code docs/design/player-connection-lost-hold.md} §4.5）：
 * <ul>
 *   <li>会话服务给断线托管角色续租时顺带对账：读 {@code player:hosted_presence:{id}} 在场心跳与 {@code player:hosted_homing:{id}} 归位信息；</li>
 *   <li>角色不在任何节点托管时，先把归位信息 TTL 对齐到当前挂机月卡到期时刻（月卡续费不会让归位断掉）；</li>
 *   <li>若心跳缺失、归位信息匹配本条会话与登录代次、且挂机月卡剩余 ≥ 60 秒，向<b>角色所在 zone</b> 的落点服务申请一次托管落点（使用归位信息里的 {@code owner_revision}）；</li>
 *   <li>落点撞上旧持有节点未交出时走 {@code keep_held = true} 探测，绝不打扰存活实体；若归属修订已失效（交易/删号/过户）则删除归位信息，不再归位。</li>
 * </ul>
 */
public final class HostedHomingReconciler {

    private static final Logger log = LoggerFactory.getLogger(HostedHomingReconciler.class);

    /** 触发归位要求挂机月卡至少剩余 60 秒。 */
    public static final long MIN_REMAINING_AFK_PASS_SECONDS = 60L;

    /** 归位元数据：持久化在 {@code player:hosted_homing:{playerId}}，TTL 对齐月卡到期。 */
    public record HomingRecord(int zoneId, long ownerRevision, int sessionId, long loginGeneration, long expiresAtSec) {
        public String encode() {
            return Integer.toUnsignedString(zoneId) + "|"
                    + Long.toUnsignedString(ownerRevision) + "|"
                    + Integer.toUnsignedString(sessionId) + "|"
                    + Long.toUnsignedString(loginGeneration) + "|"
                    + Long.toUnsignedString(expiresAtSec);
        }

        public static Optional<HomingRecord> decode(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String[] parts = raw.trim().split("\\|");
            if (parts.length != 5) {
                return Optional.empty();
            }
            try {
                int zoneId = Integer.parseUnsignedInt(parts[0]);
                long ownerRev = Long.parseUnsignedLong(parts[1]);
                int sessionId = Integer.parseUnsignedInt(parts[2]);
                long loginGen = Long.parseUnsignedLong(parts[3]);
                long expiresAtSec = Long.parseUnsignedLong(parts[4]);
                if (zoneId == 0 || ownerRev == 0 || loginGen == 0) {
                    return Optional.empty();
                }
                return Optional.of(new HomingRecord(zoneId, ownerRev, sessionId, loginGen, expiresAtSec));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
    }

    public enum PlacementOutcome {
        PLACED,
        /** 持有节点仍有活实体，keep_held 保活不打扰。 */
        KEPT_HELD,
        /** 归属修订已失效（交易/封存/换号），必须删除归位信息。 */
        OWNER_REVISION_INVALID,
        RETRYABLE_ERROR
    }

    public enum ReconcileResult {
        NO_HOMING,
        ALREADY_HOSTED,
        PASS_INSUFFICIENT,
        SESSION_MISMATCH,
        HOMING_PLACED,
        HOMING_KEPT_HELD,
        HOMING_INVALIDATED,
        HOMING_RETRYABLE_ERROR
    }

    /** 归位相关 Redis 读写端口（同槽键 {@code player:hosted_presence / hosted_homing / afk_pass}）。 */
    public interface Store {
        boolean hasHostedPresence(long playerId);

        Optional<HomingRecord> readHoming(long playerId);

        long readAfkPassExpiresAtSec(long playerId);

        /** 对齐归位元数据的 TTL 到当前挂机月卡到期时刻。 */
        void alignHomingTtl(long playerId, HomingRecord updated, long ttlSeconds);

        void deleteHoming(long playerId);
    }

    /** 按角色所在 zone 路由的托管落点客户端（绝不发给登录服本 zone）。 */
    @FunctionalInterface
    public interface PlacementClient {
        PlacementOutcome requestHostedPlacement(int targetZoneId, long playerId, long ownerRevision,
                                                int sessionId, long loginGeneration);
    }

    private final Store store;
    private final PlacementClient placementClient;

    public HostedHomingReconciler(Store store, PlacementClient placementClient) {
        this.store = Objects.requireNonNull(store, "store");
        this.placementClient = Objects.requireNonNull(placementClient, "placementClient");
    }

    public ReconcileResult reconcileOnRenew(long playerId, int sessionId, long loginGeneration, long nowUnixSec) {
        if (playerId == 0 || loginGeneration == 0) {
            return ReconcileResult.NO_HOMING;
        }
        Optional<HomingRecord> maybeHoming = store.readHoming(playerId);
        if (maybeHoming.isEmpty()) {
            return ReconcileResult.NO_HOMING;
        }
        HomingRecord homing = maybeHoming.get();
        if (store.hasHostedPresence(playerId)) {
            return ReconcileResult.ALREADY_HOSTED;
        }
        long passExpiresAtSec = store.readAfkPassExpiresAtSec(playerId);
        long remainingSec = passExpiresAtSec - nowUnixSec;
        if (remainingSec < MIN_REMAINING_AFK_PASS_SECONDS) {
            return ReconcileResult.PASS_INSUFFICIENT;
        }
        // 2026-11-13 补：角色当前不在任何节点托管时，按最新月卡到期对齐归位元数据 TTL，避免月卡续费后归位提前过期
        if (passExpiresAtSec > homing.expiresAtSec()) {
            HomingRecord aligned = new HomingRecord(
                    homing.zoneId(), homing.ownerRevision(), homing.sessionId(), homing.loginGeneration(), passExpiresAtSec);
            store.alignHomingTtl(playerId, aligned, remainingSec);
            homing = aligned;
        }
        if (homing.sessionId() != sessionId || homing.loginGeneration() != loginGeneration) {
            return ReconcileResult.SESSION_MISMATCH;
        }
        PlacementOutcome outcome = placementClient.requestHostedPlacement(
                homing.zoneId(), playerId, homing.ownerRevision(), sessionId, loginGeneration);
        return switch (outcome) {
            case PLACED -> {
                log.info("hosted homing placed player_id={} target_zone={} owner_rev={}",
                        Long.toUnsignedString(playerId), Integer.toUnsignedString(homing.zoneId()), homing.ownerRevision());
                yield ReconcileResult.HOMING_PLACED;
            }
            case KEPT_HELD -> ReconcileResult.HOMING_KEPT_HELD;
            case OWNER_REVISION_INVALID -> {
                store.deleteHoming(playerId);
                log.info("hosted homing invalidated and cleared player_id={} target_zone={} owner_rev={}",
                        Long.toUnsignedString(playerId), Integer.toUnsignedString(homing.zoneId()), homing.ownerRevision());
                yield ReconcileResult.HOMING_INVALIDATED;
            }
            case RETRYABLE_ERROR -> ReconcileResult.HOMING_RETRYABLE_ERROR;
        };
    }
}
