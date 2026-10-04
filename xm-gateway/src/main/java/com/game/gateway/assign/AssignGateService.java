package com.game.gateway.assign;

import com.game.api.proto.GateNodeInfo;
import com.game.gateway.gate.GatePicker;
import com.game.gateway.gate.GateSource;
import com.game.gateway.gate.GateTokenIssuer;
import com.game.gateway.gate.GateTokenIssuer.IssuedGateToken;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分配 gate：区服准入 → 读 gate 目录 → 挑人数最少的 gate → 签令牌。
 *
 * <p>全部失败路径 fail-closed：区服不存在 / 不开放、目录不可达、没有可连接的 gate，都不签令牌，
 * 以 {@link AssignGateResponse} 的业务码返回（HTTP 恒 200）。区服准入在读目录之前，维护中的区不会触达 Redis。
 *
 * <p>线程模型：无可变状态，线程安全；会阻塞读 Redis，只在 Servlet 请求线程上调用。
 */
public final class AssignGateService {

    private static final Logger log = LoggerFactory.getLogger(AssignGateService.class);

    private final ZoneDirectory zones;
    private final GateSource gates;
    private final GateTokenIssuer issuer;

    public AssignGateService(ZoneDirectory zones, GateSource gates, GateTokenIssuer issuer) {
        this.zones = zones;
        this.gates = gates;
        this.issuer = issuer;
    }

    public AssignGateResponse assign(int zoneId) {
        Optional<ZoneRow> zone;
        try {
            zone = zones.find(zoneId);
        } catch (RuntimeException e) {
            // 区服表读不到：fail-closed（同基线 zone_admission_unavailable）
            log.error("读取区服目录失败，拒绝分配 zone={}", zoneId, e);
            return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL,
                    AssignGateResponse.ERR_ZONE_ADMISSION_UNAVAILABLE);
        }
        if (zone.isEmpty()) {
            return AssignGateResponse.rejected(AssignGateResponse.CODE_ZONE_NOT_FOUND, AssignGateResponse.ERR_ZONE_NOT_FOUND);
        }
        switch (zone.get().status()) {
            case MAINTENANCE -> {
                return AssignGateResponse.rejected(AssignGateResponse.CODE_ZONE_UNAVAILABLE,
                        AssignGateResponse.ERR_ZONE_MAINTENANCE);
            }
            case CLOSED -> {
                return AssignGateResponse.rejected(AssignGateResponse.CODE_ZONE_UNAVAILABLE,
                        AssignGateResponse.ERR_ZONE_CLOSED);
            }
            case PREVIEW -> {
                // 同基线：白名单不参与准入（assign-gate 的 account 未经认证，按它放行等于谁都能进），一律未开放
                return AssignGateResponse.rejected(AssignGateResponse.CODE_ZONE_UNAVAILABLE,
                        AssignGateResponse.ERR_ZONE_NOT_OPEN);
            }
            case OPEN -> {
                // 放行，继续选 gate。
            }
        }

        List<GateNodeInfo> candidates;
        try {
            candidates = gates.listGates(zoneId);
        } catch (RuntimeException e) {
            log.error("读取 gate 目录失败，拒绝分配 zone={}", zoneId, e);
            return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL,
                    AssignGateResponse.ERR_GATE_DIRECTORY_UNAVAILABLE);
        }

        Optional<GateNodeInfo> picked = GatePicker.pick(zoneId, candidates);
        if (picked.isEmpty()) {
            log.warn("没有可分配的 gate zone={} 目录条目数={}", zoneId, candidates.size());
            return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_NO_GATE_AVAILABLE);
        }

        GateNodeInfo gate = picked.get();
        IssuedGateToken token = issuer.issue(gate.getNodeId(), zoneId);
        log.debug("已分配 gate zone={} node={} 在线={}", zoneId, gate.getNodeId(), Integer.toUnsignedLong(gate.getPlayerCount()));
        return AssignGateResponse.admitted(gate.getClientHost(), gate.getClientPort(),
                token.payload().toByteArray(), token.signature().toByteArray(), token.deadlineEpochSec());
    }
}
