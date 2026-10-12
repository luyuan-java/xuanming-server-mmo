package com.game.data.query;

import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.TransactionLogEntry;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流水 / 快照行的 JSON 视图。uint64 字段（流水号、玩家号、余额……）一律是十进制字符串，免得 JavaScript 读成浮点丢精度；
 * uint32 字段按无符号数值输出。字段名与批次 2.3 起的运维查询一致（robot 的 audit 场景按这些名字读）。
 */
public final class AuditViews {

    private AuditViews() {
    }

    public static Map<String, Object> txlog(TransactionLogEntry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("txId", Long.toUnsignedString(e.getTxId()));
        out.put("timeMs", e.getTimeMs());
        out.put("reason", e.getReason());
        out.put("kind", e.getKind());
        out.put("fromPlayer", Long.toUnsignedString(e.getFromPlayer()));
        out.put("toPlayer", Long.toUnsignedString(e.getToPlayer()));
        out.put("currencyType", Integer.toUnsignedLong(e.getCurrencyType()));
        out.put("currencyDelta", e.getCurrencyDelta());
        out.put("balanceBefore", Long.toUnsignedString(e.getBalanceBefore()));
        out.put("balanceAfter", Long.toUnsignedString(e.getBalanceAfter()));
        out.put("itemUuid", Long.toUnsignedString(e.getItemUuid()));
        out.put("itemConfigId", Integer.toUnsignedLong(e.getItemConfigId()));
        out.put("itemQuantity", Integer.toUnsignedLong(e.getItemQuantity()));
        out.put("correlationId", Long.toUnsignedString(e.getCorrelationId()));
        out.put("extra", e.getExtra());
        out.put("zoneId", Integer.toUnsignedLong(e.getZoneId()));
        out.put("ingestedAt", e.getIngestedAt());
        return out;
    }

    /** 快照元数据（不含玩法数据本体，只给字节数）。{@code cause} 是数值（与 2.3b 起的输出一致），{@code causeName} 是名字。 */
    public static Map<String, Object> snapshotMeta(PlayerSnapshotEntry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", Long.toUnsignedString(e.getSnapshotId()));
        out.put("playerId", Long.toUnsignedString(e.getPlayerId()));
        out.put("timeMs", e.getTimeMs());
        out.put("cause", e.getCause());
        out.put("causeName", SnapshotCauses.name(e.getCause()));
        out.put("zoneId", Integer.toUnsignedLong(e.getZoneId()));
        out.put("ownerEpoch", Long.toUnsignedString(e.getOwnerEpoch()));
        out.put("level", Integer.toUnsignedLong(e.getLevel()));
        out.put("sceneConfigId", Integer.toUnsignedLong(e.getSceneConfigId()));
        out.put("posX", e.getPosX());
        out.put("posY", e.getPosY());
        out.put("posZ", e.getPosZ());
        out.put("stateBytes", e.getStateBytes());
        out.put("ingestedAt", e.getIngestedAt());
        out.put("operator", e.getOperator());
        out.put("note", e.getNote());
        return out;
    }
}
