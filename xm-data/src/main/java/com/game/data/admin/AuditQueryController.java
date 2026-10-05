package com.game.data.admin;

import com.game.data.ops.OpsException;
import com.game.data.ops.OpsRequests;
import com.game.data.query.AuditViews;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.query.TransactionLogQueryService.Filter;
import com.game.data.query.TransactionLogQueryService.Page;
import com.game.data.query.TxCursor;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PlayerSnapshotMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维查询：资产流水（114 / 117 的 Java 对应）与玩家快照列表（97 / 115）。鉴权见 {@link AdminAuthFilter}。
 * uint64 字段在 JSON 里一律是十进制字符串（{@link AuditViews}）。应答体仍是行数组（批次 2.3 起的形状，robot 按数组读）；
 * 流水还有下一页时，游标放在应答头 {@value #NEXT_CURSOR_HEADER}（键集分页，data-ops-spec §6.5）。
 */
@RestController
public class AuditQueryController {

    public static final String TRANSACTION_LOG_PATH = "/admin/transaction-log";
    public static final String PLAYER_SNAPSHOTS_PATH = "/admin/player-snapshots";
    /** 流水查询还有下一页时的游标（{@code <timeMs>:<txId>}），作为下一次请求的 {@code after}。 */
    public static final String NEXT_CURSOR_HEADER = "X-Xm-Next-Cursor";
    static final int DEFAULT_LIMIT = TransactionLogQueryService.DEFAULT_LIMIT;
    static final int MAX_LIMIT = TransactionLogQueryService.MAX_LIMIT;

    private final TransactionLogQueryService transactionLog;
    private final PlayerSnapshotMapper playerSnapshot;

    public AuditQueryController(TransactionLogQueryService transactionLog, PlayerSnapshotMapper playerSnapshot) {
        this.transactionLog = transactionLog;
        this.playerSnapshot = playerSnapshot;
    }

    /**
     * 流水筛选查询，按（时间、流水号）升序，至多 {@code limit} 条。规则见 {@link TransactionLogQueryService}：
     * 不给 {@code player} 时必须给 {@code itemUuid} / {@code itemConfigId} / {@code currencyType} 之一，或时间窗不超过上限。
     *
     * @param player       玩家号（无符号十进制，扣减方或获得方；可选）
     * @param kind         {@code currency} / {@code item}（可选；给了币种或配置号时可省）
     * @param currencyType 币种（0 = 金币，有效）
     * @param itemConfigId 物品配置号（非 0）
     * @param itemUuid     物品实例号（非 0）
     * @param reasons      原因（{@code xm.audit.TransactionReason} 数值，可多值或逗号分隔）
     * @param since        起始 Unix 毫秒（含）
     * @param until        截止 Unix 毫秒（不含）
     * @param after        上一页应答头里的游标
     */
    @GetMapping(TRANSACTION_LOG_PATH)
    public ResponseEntity<List<Map<String, Object>>> transactionLog(
            @RequestParam(name = "player", required = false) String player,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "currencyType", required = false) String currencyType,
            @RequestParam(name = "itemConfigId", required = false) String itemConfigId,
            @RequestParam(name = "itemUuid", required = false) String itemUuid,
            @RequestParam(name = "reasons", required = false) List<String> reasons,
            @RequestParam(name = "since", defaultValue = "0") long since,
            @RequestParam(name = "until", defaultValue = "9223372036854775807") long until,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(name = "after", required = false) String after) {
        Filter filter = new Filter(
                player == null ? null : OpsRequests.u64("player", player),
                TransactionLogQueryService.parseKind(kind),
                currencyType == null ? null : OpsRequests.u32("currencyType", currencyType),
                itemConfigId == null ? null : OpsRequests.u32("itemConfigId", itemConfigId),
                itemUuid == null ? null : OpsRequests.u64("itemUuid", itemUuid),
                parseReasons(reasons), since, until, limit, TxCursor.parse(after));
        Page page = transactionLog.query(filter);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (page.next() != null) {
            ok.header(NEXT_CURSOR_HEADER, page.next().format());
        }
        return ok.body(page.rows().stream().map(AuditViews::txlog).toList());
    }

    /**
     * 玩家的快照元数据（不含玩法数据本体，只给字节数），所有来源一起列；可按原因过滤、正序（缺省）或倒序，至多 {@code limit}（≤ 1000）条。
     * 本体与 JSON 化的玩法数据见 {@code GET /admin/player-snapshots/{snapshotId}}。
     *
     * @param causes 原因（名字如 {@code LOGOUT} / {@code GM_MANUAL}，或数值；可多值或逗号分隔）
     * @param order  {@code asc}（缺省）/ {@code desc}
     */
    @GetMapping(PLAYER_SNAPSHOTS_PATH)
    public List<Map<String, Object>> playerSnapshots(@RequestParam("player") String player,
                                                     @RequestParam(name = "since", defaultValue = "0") long since,
                                                     @RequestParam(name = "until", defaultValue = "9223372036854775807") long until,
                                                     @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
                                                     @RequestParam(name = "cause", required = false) List<String> causes,
                                                     @RequestParam(name = "order", defaultValue = "asc") String order) {
        long playerId = OpsRequests.u64("player", player);
        checkLimit(limit);
        boolean desc = switch (order) {
            case "asc" -> false;
            case "desc" -> true;
            default -> throw OpsException.badRequest("order 只能是 asc / desc");
        };
        return playerSnapshot.listByPlayer(playerId, since, until, parseCauses(causes), desc, limit).stream()
                .map(AuditViews::snapshotMeta).toList();
    }

    static List<Integer> parseReasons(List<String> raw) {
        List<Integer> out = new ArrayList<>();
        if (raw != null) {
            for (String r : raw) {
                if (r != null && !r.isBlank()) {
                    out.add(OpsRequests.u32("reasons", r.trim()));
                }
            }
        }
        return out;
    }

    static List<Integer> parseCauses(List<String> raw) {
        List<Integer> out = new ArrayList<>();
        if (raw != null) {
            for (String c : raw) {
                if (c == null || c.isBlank()) {
                    continue;
                }
                int cause = SnapshotCauses.parse(c);
                if (cause < 0) {
                    throw OpsException.badRequest("不认识的快照原因：" + c);
                }
                out.add(cause);
            }
        }
        return out.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw OpsException.badRequest("limit 必须在 1–" + MAX_LIMIT + " 之间");
        }
    }
}
