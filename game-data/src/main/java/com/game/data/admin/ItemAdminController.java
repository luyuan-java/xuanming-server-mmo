package com.game.data.admin;

import com.game.audit.proto.TransactionReason;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsRequests;
import com.game.data.query.AuditViews;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.query.TransactionLogQueryService.Page;
import com.game.data.query.TxCursor;
import com.game.data.store.TransactionLogEntry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维：物品追溯（data-ops-spec §6.4；对应 104 GmTraceItem）。精确回收（110）随 7.2c 挂在同一前缀下。鉴权见 {@link AdminAuthFilter}。
 */
@RestController
public class ItemAdminController {

    public static final String PATH = "/admin/items";

    private final TransactionLogQueryService txlog;

    public ItemAdminController(TransactionLogQueryService txlog) {
        this.txlog = txlog;
    }

    /**
     * 某个物品实例的全部流水（跳），按（时间、流水号）升序（走 idx_txlog_uuid，同基线 QueryByItemUUID 的语义）。最后一页给末跳提示 {@code hint}：
     * {@code HELD_BY}（末跳是获得，{@code holder} = 获得方）/ {@code MERGED}（整理时并掉的空实例：物品销毁、数量 0）/
     * {@code DESTROYED}（被扣除、无接收方）。<b>只是提示</b>：Java 没有全局物品索引，不扫全表找当前持有者；入包流水只记写到的第一个实例，
     * 追溯是尽力而为（D17）。
     */
    @GetMapping(PATH + "/{uuid}/trace")
    public Map<String, Object> trace(@PathVariable("uuid") String uuid,
                                     @RequestParam(name = "limit", defaultValue = "" + TransactionLogQueryService.MAX_LIMIT) int limit,
                                     @RequestParam(name = "after", required = false) String after) {
        long itemUuid = OpsRequests.u64("uuid", uuid);
        if (itemUuid == 0) {
            throw OpsException.badRequest("uuid 必须非 0");
        }
        Page page = txlog.trace(itemUuid, limit, TxCursor.parse(after));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemUuid", Long.toUnsignedString(itemUuid));
        out.put("hops", page.rows().stream().map(AuditViews::txlog).toList());
        out.put("nextCursor", page.next() == null ? null : page.next().format());
        String hint = null;
        String holder = null;
        if (page.next() == null && !page.rows().isEmpty()) {
            TransactionLogEntry last = page.rows().get(page.rows().size() - 1);
            hint = hint(last);
            holder = "HELD_BY".equals(hint) ? Long.toUnsignedString(last.getToPlayer()) : null;
        }
        out.put("hint", hint);
        out.put("holder", holder);
        out.put("caveat", "提示只依据末跳，不是权威持有者；入包流水只记写到的第一个实例，堆叠物品的追溯是尽力而为");
        return out;
    }

    static String hint(TransactionLogEntry last) {
        if (last.getReason() == TransactionReason.TX_ITEM_DESTROY_VALUE && last.getItemQuantity() == 0) {
            return "MERGED";
        }
        return last.getToPlayer() != 0 ? "HELD_BY" : "DESTROYED";
    }
}
