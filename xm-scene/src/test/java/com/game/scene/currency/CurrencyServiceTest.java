package com.game.scene.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.audit.GainAnomalyDetector.Threshold;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Wallet;
import com.game.scene.testing.CurrencyAudit;
import com.game.scene.testing.ManualClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CurrencyServiceTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Long> audited = new ArrayList<>();
    private final AssetAudit audit = (CurrencyAudit) (playerId, type, delta, before, after, reason) -> audited.add(delta);
    private final SceneMetrics metrics = new SceneMetrics(meters);
    private final CurrencyService service = new CurrencyService(audit, new GainAnomalyDetector(
            new Threshold(Duration.ofSeconds(60), 0, 100), Map.of(), Map.of(), new ManualClock(), metrics), metrics,
            new ManualClock());
    private final ScenePlayer player = WorldTestAccess.player(1001);

    private double counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void 全服封禁的币种加不进_回27005_不记流水不进异常窗口_计封禁拒绝_换名单后恢复() {
        service.applyGlobalBlocks(new GlobalGainBlocks(Set.of(Wallet.DIAMOND), Set.of()));

        Wallet.Change blocked = service.add(player, Wallet.DIAMOND, 500, AssetAudit.Reason.GM_GRANT);

        assertThat(blocked.tipId()).isEqualTo(27005);
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isZero();
        assertThat(audited).isEmpty();
        assertThat(counter("xm.scene.gain.blocked", "category", "currency")).isEqualTo(1);
        assertThat(player.gainWindows().currency(Wallet.DIAMOND).count()).as("被拒的获取不进异常窗口").isZero();
        assertThat(counter("xm.scene.gain.anomalies", "currency_type", "1")).isZero();
        assertThat(service.add(player, Wallet.GOLD, 5, AssetAudit.Reason.GM_GRANT).ok()).as("别的币种不受影响").isTrue();
        assertThat(service.add(player, Wallet.DIAMOND, 0, AssetAudit.Reason.GM_GRANT).tipId()).as("参数错误先判")
                .isEqualTo(1005);
        assertThat(counter("xm.scene.gain.blocked", "category", "currency")).isEqualTo(1);

        service.applyGlobalBlocks(GlobalGainBlocks.NONE);
        assertThat(service.add(player, Wallet.DIAMOND, 500, AssetAudit.Reason.GM_GRANT).ok()).isTrue();
        assertThat(audited).containsExactly(5L, 500L);
        assertThat(counter("xm.scene.gain.anomalies", "currency_type", "1")).as("解封后第一次成功加 500 才告警").isEqualTo(1);
    }

    @Test
    void 本人封禁不计全服封禁拒绝() {
        player.wallet().block(Wallet.GOLD);
        assertThat(service.add(player, Wallet.GOLD, 5, AssetAudit.Reason.GM_GRANT).tipId()).isEqualTo(27005);
        assertThat(counter("xm.scene.gain.blocked", "category", "currency")).isZero();
    }

    /**
     * 跨节点换图冻结中（交出事务在途，scene-handoff-spec §0.5）：加 / 扣在参数校验之后回 27003，预检同口径；GM 封禁 / 解封回 1005；
     * 余额、封禁、异常窗口都不动，不记流水。选目标中不冻结。
     */
    @Test
    void 冻结中加扣回27003_参数错仍回1005_封禁解封回1005_零改动零流水_选目标中照常() {
        service.add(player, Wallet.GOLD, 100, AssetAudit.Reason.GM_GRANT);
        audited.clear();
        com.game.player.store.state.PlayerState before = WorldTestAccess.persistentState(player);
        WorldTestAccess.startFreezing(player);

        Wallet.Change add = service.add(player, Wallet.GOLD, 5, AssetAudit.Reason.GM_GRANT);
        Wallet.Change deduct = service.deduct(player, Wallet.GOLD, 5, AssetAudit.Reason.GM_DEDUCT);

        assertThat(add.tipId()).isEqualTo(27003);
        assertThat(add.before()).isEqualTo(100);
        assertThat(add.after()).isEqualTo(100);
        assertThat(deduct.tipId()).isEqualTo(27003);
        assertThat(service.checkAdd(player, Wallet.GOLD, 5, 0)).as("预检与加币同口径").isEqualTo(27003);
        assertThat(service.add(player, Wallet.GOLD, 0, AssetAudit.Reason.GM_GRANT).tipId())
                .as("参数错先判：解冻后照样失败的请求不该拿到可重投的 27003").isEqualTo(1005);
        assertThat(service.deduct(player, 9, 5, AssetAudit.Reason.GM_DEDUCT).tipId()).isEqualTo(1005);
        assertThat(service.block(player, Wallet.DIAMOND)).isEqualTo(1005);
        assertThat(service.unblock(player, Wallet.DIAMOND)).isEqualTo(1005);
        assertThat(WorldTestAccess.persistentState(player)).isEqualTo(before);
        assertThat(audited).isEmpty();
        assertThat(player.gainWindows().currency(Wallet.GOLD).count()).isEqualTo(1);

        WorldTestAccess.clearSwitch(player);
        WorldTestAccess.startResolving(player);
        assertThat(service.add(player, Wallet.GOLD, 5, AssetAudit.Reason.GM_GRANT).ok()).isTrue();
        assertThat(service.deduct(player, Wallet.GOLD, 5, AssetAudit.Reason.GM_DEDUCT).ok()).isTrue();
        assertThat(service.block(player, Wallet.DIAMOND)).isZero();
        assertThat(player.wallet().isBlocked(Wallet.DIAMOND)).isTrue();
        assertThat(service.unblock(player, Wallet.DIAMOND)).isZero();
        assertThat(audited).containsExactly(5L, -5L);
    }

    @Test
    void 成功加币才进异常检测_扣币不算获取() {
        service.add(player, Wallet.GOLD, 100, AssetAudit.Reason.GM_GRANT);
        service.deduct(player, Wallet.GOLD, 50, AssetAudit.Reason.GM_DEDUCT);
        assertThat(counter("xm.scene.gain.anomalies", "currency_type", "0")).isZero();

        service.add(player, Wallet.GOLD, 1, AssetAudit.Reason.GM_GRANT);
        assertThat(counter("xm.scene.gain.anomalies", "currency_type", "0")).isEqualTo(1);
    }
}
