package com.game.battle.room;

import com.game.battle.metrics.BattleMetrics;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 战斗配表指纹闸（基线 {@code CheckTableFingerprint}，{@code room.cpp:306-451}；battle-node-spec §4.3.3）。出快照的 scene / 编排的 match
 * 与本节点读的不是同一份战斗表时，确定性引擎的结果对各方不再可复现。
 *
 * <ul>
 *   <li>{@link FingerprintMode#OFF}：不比；</li>
 *   <li>比较范围只含<b>非空</b>值：request 本身的一个，加上每个玩家快照里的一个；</li>
 *   <li>{@link FingerprintMode#WARN}：不一致计数 + 打 WARN，照常开局；</li>
 *   <li>{@link FingerprintMode#ENFORCE}：不一致计数 + 打 ERROR，拒绝开局（1006）。1006 的 {@code parameters[0]} 逐字照基线：
 *       {@code "battle table fingerprint mismatch: node=<本节点> request=<request.table_fingerprint>"}——不一致来自快照时 {@code request=}
 *       后面可能是空串（B8）。</li>
 * </ul>
 * 模式由配置枚举绑定（非法值拒启，§11 N18），构造后不变。只在逻辑线程上用。
 */
final class FingerprintGuard {

    private static final Logger log = LoggerFactory.getLogger(FingerprintGuard.class);

    /** 判定结果。 */
    enum Decision {
        /** 没有不一致（或 OFF）。 */
        MATCH,
        /** 不一致但 WARN 放行。 */
        MISMATCH_ALLOWED,
        /** 不一致且 ENFORCE：拒绝开局。 */
        REJECT
    }

    private final FingerprintMode mode;
    private final String self;
    private final BattleMetrics metrics;

    FingerprintGuard(FingerprintMode mode, String selfFingerprint, BattleMetrics metrics) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.self = Objects.requireNonNull(selfFingerprint, "selfFingerprint");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** 判定一次建房请求；不一致时按模式计数、打日志。 */
    Decision check(CreateBattleRequest request) {
        if (mode == FingerprintMode.OFF) {
            return Decision.MATCH;
        }
        String mismatches = mismatches(request, self);
        if (mismatches.isEmpty()) {
            return Decision.MATCH;
        }
        metrics.fingerprintMismatch(mode);
        if (mode == FingerprintMode.ENFORCE) {
            log.error("metric=battle_table_fingerprint_reject battle_id={} match_mode={} self={} mismatch:{}，配表指纹不一致，拒绝开局(mode=enforce)",
                    Long.toUnsignedString(request.getBattleId()), request.getMatchMode(), self, mismatches);
            return Decision.REJECT;
        }
        log.warn("metric=battle_table_fingerprint_mismatch battle_id={} match_mode={} self={} mismatch:{}，配表指纹不一致，照常开局(mode=warn)",
                Long.toUnsignedString(request.getBattleId()), request.getMatchMode(), self, mismatches);
        return Decision.MISMATCH_ALLOWED;
    }

    /** 1006 的 {@code parameters[0]}（逐字照基线 {@code room.cpp:529-532}）。 */
    String rejectionText(CreateBattleRequest request) {
        return "battle table fingerprint mismatch: node=" + self + " request=" + request.getTableFingerprint();
    }

    /** 收集全部不一致来源（只比非空值），一次日志说清楚；没有不一致返回空串。纯函数。 */
    static String mismatches(CreateBattleRequest request, String self) {
        StringBuilder out = new StringBuilder();
        if (!request.getTableFingerprint().isEmpty() && !request.getTableFingerprint().equals(self)) {
            out.append(" request=").append(request.getTableFingerprint());
        }
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            if (!snapshot.getTableFingerprint().isEmpty() && !snapshot.getTableFingerprint().equals(self)) {
                out.append(" player_").append(Long.toUnsignedString(snapshot.getPlayerId())).append('=')
                        .append(snapshot.getTableFingerprint());
            }
        }
        return out.toString();
    }
}
