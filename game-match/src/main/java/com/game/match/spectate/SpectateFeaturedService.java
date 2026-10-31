package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.proto.eSpectateTier;
import com.game.proto.match.SetSpectateFeaturedRequest;
import com.game.proto.match.SetSpectateFeaturedResponse;
import com.game.proto.match.SpectateFeaturedReject;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运营把一场公开局标成 / 取消标成赛事局（mmorpg battle-spectate-tiered-fanout.md §15.6，语义同 C++ / Go 版的 {@code SetSpectateFeatured}）：记录不在或房间已不在 → NOT_FOUND；非公开 → PRIVATE；
 * Redis 故障 → INTERNAL（可重试）；标记时立刻打开 battle 的中继开关（调不通保持原档，不算失败）；取消只删标记，不降档；重复设置同值幂等。
 *
 * <p>阻塞等 Redis 与 battle，在管理端口的 Tomcat 线程上调。无状态，线程安全。
 */
public final class SpectateFeaturedService {

    private static final Logger log = LoggerFactory.getLogger(SpectateFeaturedService.class);

    private final SpectateStore store;
    private final SpectateTierStore tiers;
    private final SpectateRelay relay;

    public SpectateFeaturedService(SpectateStore store, SpectateTierStore tiers, SpectateRelay relay) {
        this.store = Objects.requireNonNull(store, "store");
        this.tiers = Objects.requireNonNull(tiers, "tiers");
        this.relay = Objects.requireNonNull(relay, "relay");
    }

    public SetSpectateFeaturedResponse set(SetSpectateFeaturedRequest request, Deadline d) {
        long battleId = request.getBattleId();
        try {
            BattlePlacement placement;
            switch (store.read(battleId, d).record()) {
                case SpectateStore.Record.Found found -> placement = found.placement();
                case SpectateStore.Record.Absent absent -> {
                    return rejected(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_NOT_FOUND);
                }
                case SpectateStore.Record.Corrupt corrupt -> {
                    log.error("[spectate] 赛事局标记：落点记录损坏 battle_id={}: {}", Long.toUnsignedString(battleId), corrupt.why());
                    return rejected(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_INTERNAL);
                }
            }
            if (!SpectateVisibilityPolicy.isPublic(placement)) {
                return rejected(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_PRIVATE);
            }
            if (!request.getFeatured()) {
                tiers.setFeatured(battleId, false, d);
                return tier(tiers.tier(battleId, d));
            }
            SpectateRelay.Promotion promotion = relay.promote(placement, d);
            if (promotion.gone()) {
                return rejected(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_NOT_FOUND);
            }
            tiers.setFeatured(battleId, true, d);
            log.info("[spectate] 赛事局已标记 battle_id={} tier={}", Long.toUnsignedString(battleId), promotion.tier());
            return tier(promotion.tier());
        } catch (Deadline.DependencyException e) {
            log.warn("[spectate] 赛事局标记依赖故障（可重试） battle_id={}: {}", Long.toUnsignedString(battleId), e.toString());
            return rejected(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_INTERNAL);
        }
    }

    private static SetSpectateFeaturedResponse rejected(SpectateFeaturedReject reject) {
        return SetSpectateFeaturedResponse.newBuilder().setReject(reject).build();
    }

    private static SetSpectateFeaturedResponse tier(eSpectateTier tier) {
        return SetSpectateFeaturedResponse.newBuilder().setTier(tier).build();
    }
}
