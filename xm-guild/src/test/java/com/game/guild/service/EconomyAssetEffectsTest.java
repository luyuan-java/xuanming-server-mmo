package com.game.guild.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.guild.asset.AssetOpStatus;
import com.game.guild.asset.DeliveryOrigin;
import com.game.guild.asset.GuildAssetStore.CleanupTable;
import com.game.guild.asset.GuildAssetStore.FinalizedOp;
import com.game.guild.asset.GuildAssetStore.Orphan;
import com.game.guild.store.GuildTxOp;
import com.game.guild.store.Invalidation;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.proto.guild.GuildChangeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 资产 Store 提交之后的副作用（照基线 economy_logic_test.go:1228-1276 的 OnAssetFinalized 用例；guild-economy-spec §5、E9）：同步终结不推送、
 * 后台终结推 9 / 13 只推本人、人工终结不推、未知 kind 不推；终结失效 guild 与映射；orphan / 清理计数。
 */
class EconomyAssetEffectsTest {

    private final GuildServiceFixture f = new GuildServiceFixture();
    private final EconomyAssetEffects effects = new EconomyAssetEffects(f.invalidator, f.guildPushes, f.metrics);

    @AfterEach
    void close() {
        f.close();
    }

    private static FinalizedOp op(GuildAssetOpKind kind, DeliveryOrigin origin) {
        return new FinalizedOp(9, 1001, 500, kind, AssetOpStatus.APPLIED, origin);
    }

    @Test
    void 同步投递与人工终结不推送() {
        effects.finalized(op(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, DeliveryOrigin.SYNC));
        effects.finalized(op(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP, DeliveryOrigin.MANUAL));
        assertThat(f.pushes).isEmpty();
    }

    @Test
    void 后台终结只推本人_捐献推FUNDS_CHANGED_兑换与活动推DELIVERY_DONE() {
        effects.finalized(op(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, DeliveryOrigin.LOOP));
        effects.finalized(op(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP, DeliveryOrigin.LOOP));
        effects.finalized(op(GuildAssetOpKind.GUILD_ASSET_OP_KIND_ACTIVITY_REWARD, DeliveryOrigin.LOOP));
        assertThat(f.pushes).hasSize(3);
        assertThat(f.pushes).extracting(p -> p.change().getKind()).containsExactly(GuildChangeKind.GUILD_CHANGE_KIND_FUNDS_CHANGED,
                GuildChangeKind.GUILD_CHANGE_KIND_DELIVERY_DONE, GuildChangeKind.GUILD_CHANGE_KIND_DELIVERY_DONE);
        for (var push : f.pushes) {
            assertThat(push.recipients()).containsExactly(1001L);
            assertThat(push.change().getActorPlayerId()).isZero();
            assertThat(push.change().getTargetPlayerId()).isEqualTo(1001L);
            assertThat(push.change().getGuildId()).isEqualTo(500L);
        }
    }

    @Test
    void 未知kind不推() {
        effects.finalized(op(GuildAssetOpKind.GUILD_ASSET_OP_KIND_UNSPECIFIED, DeliveryOrigin.LOOP));
        assertThat(f.pushes).isEmpty();
    }

    @Test
    void 终结改过资金帮贡_失效guild与映射() {
        effects.invalidate(Invalidation.of(GuildTxOp.ASSET_FINALIZE, 500, 1001));
        assertThat(f.journal).anyMatch(j -> j.contains(RedisKeys.guildSnapshot(500)));
        assertThat(f.journal).anyMatch(j -> j.contains(RedisKeys.guildOfPlayer(1001)));
    }

    @Test
    void orphan与清理计数() {
        effects.orphan(Orphan.DONATE_MEMBER_GONE);
        effects.cleanupDeleted(CleanupTable.ASSET_OP, 3);
        effects.cleanupDeleted(CleanupTable.DAILY_COUNTER, 0);
        assertThat(f.meters.get("xm.guild.asset.orphans").tag("kind", "donate").tag("what", "member_gone").counter().count())
                .isEqualTo(1);
        assertThat(f.meters.get("xm.guild.asset.cleanup.deleted").tag("table", "guild_asset_op").counter().count()).isEqualTo(3);
        assertThat(f.meters.get("xm.guild.asset.cleanup.deleted").tag("table", "guild_daily_counter").counter().count())
                .isZero();
    }
}
