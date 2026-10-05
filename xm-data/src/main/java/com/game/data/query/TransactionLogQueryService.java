package com.game.data.query;

import com.game.audit.proto.AssetKind;
import com.game.data.ops.OpsException;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogMapper;
import com.game.data.store.TransactionLogQuery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 流水查询（data-ops-spec §6.5，对应 114 / 117）与物品追溯（§6.4，对应 104）。
 *
 * <ul>
 *   <li>条件：玩家（扣减方或获得方，可选）、kind、币种（0 = 金币，有效）、物品配置号、物品 uuid、原因（多值）、半开毫秒窗口 [since, until)；</li>
 *   <li><b>走索引的约束</b>：不给玩家时必须给 uuid、物品配置号、币种之一；只给原因 / 时间窗时窗口不得超过 {@code xm.data.ops.max-window}
 *       （走 idx_txlog_time）——基线 QueryLog 不做这种约束，热表上随便一查就是全表扫（B4）；</li>
 *   <li>排序按（时间、流水号）升序（基线倒序），取 limit+1 判截断，给下一页游标；不回总数（基线每次 {@code COUNT(*)}，H14）；</li>
 *   <li>玩家条件分两次走各自的索引（from / to）再归并，不用 OR（OR 跨两列走不了同一条索引）。</li>
 * </ul>
 * 只读，线程安全。
 */
public final class TransactionLogQueryService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;
    /** 游标扫描（回收匹配）一页的行数。 */
    static final int SCAN_PAGE = 1000;

    public static final Comparator<TransactionLogEntry> ORDER = Comparator.comparingLong(TransactionLogEntry::getTimeMs)
            .thenComparing(TransactionLogEntry::getTxId, Long::compareUnsigned);

    /**
     * 查询条件（已解析好的值；为 null = 不过滤）。
     *
     * @param kind 1 货币 / 2 物品（{@code xm.audit.AssetKind}）
     */
    public record Filter(Long player, Integer kind, Integer currencyType, Integer itemConfigId, Long itemUuid,
                         List<Integer> reasons, long since, long until, int limit, TxCursor after) {
    }

    /** 一页结果；{@code next} 非 null 表示还有更多（取它作下一页的 after）。 */
    public record Page(List<TransactionLogEntry> rows, TxCursor next) {
    }

    private final TransactionLogMapper mapper;
    private final Duration maxWindow;

    public TransactionLogQueryService(TransactionLogMapper mapper, Duration maxWindow) {
        this.mapper = mapper;
        this.maxWindow = maxWindow;
    }

    /** 解析 kind 参数：{@code currency} / {@code item} / 数值 1 / 2；null = 不过滤。 */
    public static Integer parseKind(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "currency", "1" -> AssetKind.ASSET_CURRENCY_VALUE;
            case "item", "2" -> AssetKind.ASSET_ITEM_VALUE;
            default -> throw OpsException.badRequest("kind 只能是 currency / item");
        };
    }

    /** 按 §6.5 的规则校验并查询一页。 */
    public Page query(Filter f) {
        if (f.limit() < 1 || f.limit() > MAX_LIMIT) {
            throw OpsException.badRequest("limit 必须在 1–" + MAX_LIMIT + " 之间");
        }
        if (f.since() >= f.until()) {
            throw OpsException.badRequest("since 必须小于 until（半开窗口 [since, until)）");
        }
        Integer kind = f.kind();
        if (f.currencyType() != null && f.itemConfigId() != null) {
            throw OpsException.badRequest("currencyType 与 itemConfigId 不能同时给");
        }
        if (f.currencyType() != null) {
            kind = requireKind(kind, AssetKind.ASSET_CURRENCY_VALUE, "currencyType 只用于货币流水（kind=currency）");
        }
        if (f.itemConfigId() != null) {
            if (f.itemConfigId() == 0) {
                throw OpsException.badRequest("itemConfigId 必须非 0");
            }
            kind = requireKind(kind, AssetKind.ASSET_ITEM_VALUE, "itemConfigId 只用于物品流水（kind=item）");
        }
        if (f.itemUuid() != null && f.itemUuid() == 0) {
            throw OpsException.badRequest("itemUuid 必须非 0");
        }
        boolean indexed = f.player() != null || f.itemUuid() != null || f.itemConfigId() != null
                || f.currencyType() != null;
        if (!indexed && windowExceeds(f.since(), f.until(), maxWindow)) {
            throw OpsException.badRequest("不给 player 时必须给 itemUuid、itemConfigId、currencyType 之一；只按原因 / 时间窗查时窗口不超过 "
                    + maxWindow.toHours() + " 小时（同时给 since 与 until）");
        }
        TransactionLogQuery base = TransactionLogQuery.builder()
                .kind(kind)
                .currencyType(f.currencyType())
                .itemConfigId(f.itemConfigId())
                .itemUuid(f.itemUuid())
                .reasons(f.reasons())
                .window(f.since(), f.until())
                .after(f.after() == null ? null : f.after().timeMs(), f.after() == null ? null : f.after().txId())
                .fetch(f.limit() + 1)
                .build();
        List<TransactionLogEntry> rows = f.player() == null ? mapper.query(base) : byPlayer(base, f.player());
        return page(rows, f.limit());
    }

    /**
     * 物品追溯：某个实例 uuid 的全部流水，按（时间、流水号）升序（同基线 QueryByItemUUID 的语义，走 idx_txlog_uuid）。
     */
    public Page trace(long itemUuid, int limit, TxCursor after) {
        return query(new Filter(null, null, null, null, itemUuid, List.of(), 0, Long.MAX_VALUE, limit, after));
    }

    /**
     * 游标扫描：按（时间、流水号）升序取满 {@code maxRows + 1} 行或取尽为止（调用方据「多出一行」判截断，不做 COUNT）。
     * 不做 §6.5 的索引约束检查，调用方负责给出能走索引的条件。
     */
    public List<TransactionLogEntry> scan(TransactionLogQuery query, int maxRows) {
        List<TransactionLogEntry> out = new ArrayList<>();
        TransactionLogQuery next = query;
        while (out.size() <= maxRows) {
            int fetch = Math.min(SCAN_PAGE, maxRows + 1 - out.size());
            List<TransactionLogEntry> page = mapper.query(next.toBuilder().fetch(fetch).build());
            out.addAll(page);
            if (page.size() < fetch) {
                break;
            }
            TransactionLogEntry last = page.get(page.size() - 1);
            next = next.toBuilder().after(last.getTimeMs(), last.getTxId()).build();
        }
        return out;
    }

    /** 某玩家作为扣减方或获得方：两条索引各取 fetch 行，归并去重后取前 fetch 行（每路的前 fetch 行一定覆盖并集的前 fetch 行）。 */
    public List<TransactionLogEntry> byPlayer(TransactionLogQuery base, long player) {
        Map<Long, TransactionLogEntry> merged = new LinkedHashMap<>();
        for (TransactionLogEntry e : mapper.query(base.toBuilder().fromPlayer(player).build())) {
            merged.put(e.getTxId(), e);
        }
        for (TransactionLogEntry e : mapper.query(base.toBuilder().toPlayer(player).build())) {
            merged.putIfAbsent(e.getTxId(), e);
        }
        List<TransactionLogEntry> rows = new ArrayList<>(merged.values());
        rows.sort(ORDER);
        return rows.size() > base.getFetch() ? new ArrayList<>(rows.subList(0, base.getFetch())) : rows;
    }

    private static Page page(List<TransactionLogEntry> rows, int limit) {
        if (rows.size() <= limit) {
            return new Page(rows, null);
        }
        List<TransactionLogEntry> kept = new ArrayList<>(rows.subList(0, limit));
        return new Page(kept, TxCursor.of(kept.get(limit - 1)));
    }

    private static Integer requireKind(Integer kind, int expected, String message) {
        if (kind != null && kind != expected) {
            throw OpsException.badRequest(message);
        }
        return expected;
    }

    /** 窗口是否超过上限（until − since 溢出也算超过）。回收计划（{@code RecallPlanner}）的全服窗口上限也用它。 */
    public static boolean windowExceeds(long since, long until, Duration max) {
        long width;
        try {
            width = Math.subtractExact(until, since);
        } catch (ArithmeticException e) {
            return true;
        }
        return width > max.toMillis();
    }
}
