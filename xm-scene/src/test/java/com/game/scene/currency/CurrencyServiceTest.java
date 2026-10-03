package com.game.scene.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.audit.GainAnomalyDetector.Threshold;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Wallet;
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
    private final AssetAudit audit = (playerId, type, delta, before, after, reason) -> audited.add(delta);
    private final SceneMetrics metrics = new SceneMetrics(meters);
    private final CurrencyService service = new CurrencyService(audit, new GainAnomalyDetector(
            new Threshold(Duration.ofSeconds(60), 0, 100), Map.of(), new ManualClock(), metrics), metrics);
    private final ScenePlayer player = WorldTestAccess.player(1001);

    private double counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void 全服封禁的币种加不进_回27005_不记流水不进异常窗口_计封禁拒绝_换名单后恢复() {
        service.applyGlobalBlocks(new GlobalGainBlocks(Set.of(Wallet.DIAMOND)));

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

    @Test
    void 成功加币才进异常检测_扣币不算获取() {
        service.add(player, Wallet.GOLD, 100, AssetAudit.Reason.GM_GRANT);
        service.deduct(player, Wallet.GOLD, 50, AssetAudit.Reason.GM_DEDUCT);
        assertThat(counter("xm.scene.gain.anomalies", "currency_type", "0")).isZero();

        service.add(player, Wallet.GOLD, 1, AssetAudit.Reason.GM_GRANT);
        assertThat(counter("xm.scene.gain.anomalies", "currency_type", "0")).isEqualTo(1);
    }
}
