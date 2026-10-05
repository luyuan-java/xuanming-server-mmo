package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.metrics.BattleMetrics;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 配表指纹闸（基线 {@code room.cpp:408-451}；battle-node-spec §4.3.3、§13.1）。 */
class FingerprintGuardTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);

    private static CreateBattleRequest request(String requestFp, String... snapshotFps) {
        CreateBattleRequest.Builder request = CreateBattleRequest.newBuilder().setBattleId(9).setTableFingerprint(requestFp);
        long playerId = 5001;
        for (String fp : snapshotFps) {
            request.addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(playerId++).setTableFingerprint(fp));
        }
        return request.build();
    }

    private double mismatches(String mode) {
        return registry.get("xm.battle.fingerprint.mismatch").tag("mode", mode).counter().count();
    }

    @Test
    void off不比() {
        FingerprintGuard guard = new FingerprintGuard(FingerprintMode.OFF, "self", metrics);

        assertThat(guard.check(request("other", "other"))).isEqualTo(FingerprintGuard.Decision.MATCH);
        assertThat(mismatches("warn") + mismatches("enforce")).isZero();
    }

    @Test
    void 空值不比() {
        FingerprintGuard guard = new FingerprintGuard(FingerprintMode.ENFORCE, "self", metrics);

        assertThat(guard.check(request("", "", ""))).isEqualTo(FingerprintGuard.Decision.MATCH);
        assertThat(guard.check(request("self", "self", ""))).isEqualTo(FingerprintGuard.Decision.MATCH);
    }

    @Test
    void request不符与仅某个快照不符都算不一致() {
        assertThat(FingerprintGuard.mismatches(request("other"), "self")).isEqualTo(" request=other");
        assertThat(FingerprintGuard.mismatches(request("", "self", "bad"), "self")).isEqualTo(" player_5002=bad");
        assertThat(FingerprintGuard.mismatches(request("self", "self"), "self")).isEmpty();
    }

    @Test
    void warn放行并计数() {
        FingerprintGuard guard = new FingerprintGuard(FingerprintMode.WARN, "self", metrics);

        assertThat(guard.check(request("other"))).isEqualTo(FingerprintGuard.Decision.MISMATCH_ALLOWED);
        assertThat(mismatches("warn")).isEqualTo(1);
        assertThat(mismatches("enforce")).isZero();
    }

    @Test
    void enforce拒绝且文案逐字相同_含request为空串的情形() {
        FingerprintGuard guard = new FingerprintGuard(FingerprintMode.ENFORCE, "self", metrics);

        CreateBattleRequest byRequest = request("other");
        assertThat(guard.check(byRequest)).isEqualTo(FingerprintGuard.Decision.REJECT);
        assertThat(guard.rejectionText(byRequest)).isEqualTo("battle table fingerprint mismatch: node=self request=other");

        CreateBattleRequest bySnapshot = request("", "bad");
        assertThat(guard.check(bySnapshot)).isEqualTo(FingerprintGuard.Decision.REJECT);
        assertThat(guard.rejectionText(bySnapshot)).as("B8：request= 后面永远是 request 的指纹").isEqualTo(
                "battle table fingerprint mismatch: node=self request=");
        assertThat(mismatches("enforce")).isEqualTo(2);
    }
}
