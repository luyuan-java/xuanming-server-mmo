package com.game.data.admin;

import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维查询：某玩家的资产流水与快照（客服 / 回档排查用）。鉴权见 {@link AdminAuthFilter}。
 * uint64 字段（流水号、玩家号、余额……）在 JSON 里一律是十进制字符串，免得 JavaScript 读成浮点丢精度。
 */
@RestController
public class AuditQueryController {

    public static final String TRANSACTION_LOG_PATH = "/admin/transaction-log";
    public static final String PLAYER_SNAPSHOTS_PATH = "/admin/player-snapshots";
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    private final TransactionLogMapper transactionLog;
    private final PlayerSnapshotMapper playerSnapshot;

    public AuditQueryController(TransactionLogMapper transactionLog, PlayerSnapshotMapper playerSnapshot) {
        this.transactionLog = transactionLog;
        this.playerSnapshot = playerSnapshot;
    }

    /**
     * 玩家作为扣减方或获得方的流水，按（时间、流水号）升序，至多 {@code limit} 条。分两次走各自的索引再归并，不用 OR。
     *
     * @param player 玩家号（无符号十进制）
     * @param since  起始 Unix 毫秒（含）
     * @param until  截止 Unix 毫秒（不含）
     */
    @GetMapping(TRANSACTION_LOG_PATH)
    public List<Map<String, Object>> transactionLog(@RequestParam("player") String player,
                                                    @RequestParam(name = "since", defaultValue = "0") long since,
                                                    @RequestParam(name = "until", defaultValue = "9223372036854775807") long until,
                                                    @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        long playerId = parsePlayer(player);
        checkLimit(limit);
        Map<Long, TransactionLogEntry> merged = new LinkedHashMap<>();
        for (TransactionLogEntry e : transactionLog.findByFromPlayer(playerId, since, until, limit)) {
            merged.put(e.getTxId(), e);
        }
        for (TransactionLogEntry e : transactionLog.findByToPlayer(playerId, since, until, limit)) {
            merged.putIfAbsent(e.getTxId(), e);
        }
        List<TransactionLogEntry> rows = new ArrayList<>(merged.values());
        rows.sort(Comparator.comparingLong(TransactionLogEntry::getTimeMs)
                .thenComparing(TransactionLogEntry::getTxId, Long::compareUnsigned));
        return rows.stream().limit(limit).map(AuditQueryController::view).toList();
    }

    /**
     * 玩家的快照元数据（不含玩法数据本体，只给字节数），按（时间、快照号）升序，至多 {@code limit} 条。
     * 参数同 {@link #transactionLog}。
     */
    @GetMapping(PLAYER_SNAPSHOTS_PATH)
    public List<Map<String, Object>> playerSnapshots(@RequestParam("player") String player,
                                                     @RequestParam(name = "since", defaultValue = "0") long since,
                                                     @RequestParam(name = "until", defaultValue = "9223372036854775807") long until,
                                                     @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        long playerId = parsePlayer(player);
        checkLimit(limit);
        return playerSnapshot.findByPlayer(playerId, since, until, limit).stream()
                .map(AuditQueryController::view).toList();
    }

    private static long parsePlayer(String player) {
        try {
            return Long.parseUnsignedLong(player);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "player 必须是无符号十进制整数");
        }
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须在 1–" + MAX_LIMIT + " 之间");
        }
    }

    private static Map<String, Object> view(TransactionLogEntry e) {
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

    private static Map<String, Object> view(PlayerSnapshotEntry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", Long.toUnsignedString(e.getSnapshotId()));
        out.put("playerId", Long.toUnsignedString(e.getPlayerId()));
        out.put("timeMs", e.getTimeMs());
        out.put("cause", e.getCause());
        out.put("zoneId", Integer.toUnsignedLong(e.getZoneId()));
        out.put("ownerEpoch", Long.toUnsignedString(e.getOwnerEpoch()));
        out.put("level", Integer.toUnsignedLong(e.getLevel()));
        out.put("sceneConfigId", Integer.toUnsignedLong(e.getSceneConfigId()));
        out.put("posX", e.getPosX());
        out.put("posY", e.getPosY());
        out.put("posZ", e.getPosZ());
        out.put("stateBytes", e.getStateBytes());
        out.put("ingestedAt", e.getIngestedAt());
        return out;
    }
}
