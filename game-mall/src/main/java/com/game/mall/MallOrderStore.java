package com.game.mall;

import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.PmsSkuStock;
import com.game.common.RunMode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单与官方库存的唯一 SQL 写入口。复用上游 PMS/OMS 模型，补齐游戏订单幂等、预占和状态竞争。
 * 同库提交只确认付款与持久交付意图；没有玩家资产持久回执，永不报告发货完成。
 */
public final class MallOrderStore {
    static final long MAX_AMOUNT_MINOR = 9_000_000_000_000_000L;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int maxUnpaid;
    private final PaymentPolicy paymentPolicy;
    private final RmbConsignmentStore consignments;

    public MallOrderStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, MallProperties properties) {
        this(jdbc, manager, clock, properties, RunMode.PROD);
    }
    public MallOrderStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, MallProperties properties, RunMode runMode) {
        this(jdbc, manager, clock, properties, runMode, new RmbConsignmentStore(jdbc, manager, clock,
                new RmbConsignmentProperties(), (RmbConsignmentStore.PayoutEvidenceVerifier) null));
    }
    public MallOrderStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, MallProperties properties,
                          RunMode runMode, RmbConsignmentStore consignments) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.consignments = consignments;
        maxUnpaid = properties.maxUnpaidOrders();
        paymentPolicy = new PaymentPolicy(jdbc, properties, runMode);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
        jdbc.setQueryTimeout(5);
    }

    public MallApi.Order create(String buyer, MallApi.CreateOrder in) {
        MallAuthentication.requirePlayer(buyer);
        if (in == null || in.requestId() == null || !in.requestId().matches("[A-Za-z0-9_-]{8,128}") || in.skuId() <= 0 || in.quantity() < 1 || in.quantity() > 10000) {
            throw new MallException(400, "下单参数非法");
        }
        CommerceIdentity.account(in.buyerAccount()); CommerceIdentity.revision(in.buyerOwnerRevision());
        Stored prior = byRequest(buyer, in.requestId());
        if (prior != null) { return sameRequest(prior, in); }
        // 首次建 guard 为单条独立提交，行从不删除；没有全服共享锁行。
        jdbc.update("INSERT INTO commerce_buyer_guard(buyer_id) VALUES (?) ON DUPLICATE KEY UPDATE buyer_id=buyer_id", buyer);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.queryForObject("SELECT buyer_id FROM commerce_buyer_guard WHERE buyer_id=? FOR UPDATE", String.class, buyer);
                Stored replay = byRequest(buyer, in.requestId());
                if (replay != null) { return sameRequest(replay, in); }
                Integer unpaid = jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE buyer_id=? AND status=0", Integer.class, buyer);
                if (unpaid == null || unpaid >= maxUnpaid) { throw new MallException(429, "未支付订单数量已达上限，请先处理已有订单"); }
                List<Sku> values = jdbc.query("SELECT * FROM pms_sku_stock WHERE id=? FOR UPDATE", (rs, row) -> sku(rs), in.skuId());
                if (values.isEmpty()) { throw new MallException(404, "商品不存在"); }
                Sku sku = values.get(0);
                // 同 SKU 竞争者可能刚提交相同请求；当前读隔离下重新复核，原价订单不会被新价格覆盖。
                Stored existing = byRequest(buyer, in.requestId());
                if (existing != null) { return sameRequest(existing, in); }
                PmsSkuStock model = sku.model();
                if (!sku.published() || model.getStock() < 0 || model.getLockStock() < 0 || model.getLockStock() > model.getStock()) {
                    throw new MallException(409, "商品未开放或库存状态异常");
                }
                long unitPrice = model.getPrice().longValueExact();
                long total;
                try { total = Math.multiplyExact(unitPrice, in.quantity()); }
                catch (ArithmeticException overflow) { throw new MallException(409, "商品金额超限"); }
                if (unitPrice <= 0 || total <= 0 || total > MAX_AMOUNT_MINOR || !sku.currency().matches("[A-Z][A-Z0-9_]{2,15}")) {
                    throw new MallException(409, "商品金额或币种非法");
                }
                String orderId = UUID.randomUUID().toString();
                long now = clock.millis();
                // 15 分钟支付窗口供渠道适配器使用；超时仍保留预占，未接关单确认前不自动释放。
                jdbc.update("INSERT INTO oms_order(order_sn,buyer_id,request_id,sku_id,quantity,pay_amount,currency,status,create_time_ms,payment_deadline_ms) VALUES (?,?,?,?,?,?,?,0,?,?)",
                        orderId, buyer, ascii(in.requestId()), in.skuId(), in.quantity(), total, sku.currency(), now, now + 900_000);
                jdbc.update("INSERT INTO commerce_official_owner(order_sn,buyer_account,buyer_owner_revision) VALUES(?,?,?)",
                    orderId, in.buyerAccount().getBytes(StandardCharsets.UTF_8), ascii(in.buyerOwnerRevision()));
                // 改自上游库存双计数：持主键行锁，预占条件在写入时原子复核，绝不先读后绝对值覆盖。
                int changed = jdbc.update("UPDATE pms_sku_stock SET lock_stock=lock_stock+? WHERE id=? AND published=1 AND stock-lock_stock>=?",
                        in.quantity(), in.skuId(), in.quantity());
                if (changed != 1) { throw new MallException(409, "库存不足"); }
                jdbc.update("INSERT INTO oms_order_item(order_sn,product_id,product_sku_id,product_sku_code,product_quantity,product_price_minor) VALUES (?,?,?,?,?,?)",
                        orderId, model.getProductId(), in.skuId(), model.getSkuCode(), in.quantity(), unitPrice);
                if(jdbc.update("INSERT INTO commerce_order_grant(order_sn,item_config_id,count_per_unit,title) SELECT ?,item_config_id,count_per_unit,title FROM commerce_sku_grant WHERE sku_id=? AND item_config_id>0 AND count_per_unit>0 AND count_per_unit*?<=4096 AND CHAR_LENGTH(TRIM(title))>0",orderId,in.skuId(),in.quantity())!=1)throw new MallException(409,"SKU没有合法游戏交付映射或单次数量越界");
                return view(Objects.requireNonNull(byId(orderId, false)));
            }));
        } catch (DuplicateKeyException conflict) {
            // 跨 SKU 的同幂等键竞争由唯一约束裁决；失败事务先回滚，再返回已提交的同一结果。
            Stored existing = byRequest(buyer, in.requestId());
            if (existing != null) { return sameRequest(existing, in); }
            throw new MallException(409, "请求冲突，请使用原请求号重试");
        }
    }

    public MallApi.Order get(String buyer, String orderId) {
        MallAuthentication.requirePlayer(buyer); requireOrderId(orderId);
        throw new MallException(404, "订单查询需要原账号和归属修订");
    }
    public MallApi.Order get(String buyer, MallApi.OwnedOrder input) {
        MallAuthentication.requirePlayer(buyer);
        return view(officialOwned(input, buyer, false));
    }

    public MallApi.Order createConsignment(RmbConsignmentApi.Buy input) {
        if (input == null) { throw new MallException(400, "寄售购买参数缺失"); }
        RmbConsignmentStore.account(input.buyerAccount()); RmbConsignmentStore.player(input.buyerPlayerId());
        CommerceIdentity.revision(input.buyerOwnerRevision());
        RmbConsignmentStore.player(input.listingId()); RmbConsignmentStore.request(input.requestId());
        String prior = consignments.orderByRequest(input.buyerAccount(), input.requestId());
        if (prior != null) { return sameConsignment(prior, input); }
        jdbc.update("INSERT INTO commerce_buyer_guard(buyer_id) VALUES(?) ON DUPLICATE KEY UPDATE buyer_id=buyer_id", input.buyerPlayerId());
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.queryForObject("SELECT buyer_id FROM commerce_buyer_guard WHERE buyer_id=? FOR UPDATE", String.class, input.buyerPlayerId());
                String replay = consignments.orderByRequest(input.buyerAccount(), input.requestId());
                if (replay != null) { return sameConsignment(replay, input); }
                if (byRequest(input.buyerPlayerId(), input.requestId()) != null) {
                    throw new MallException(409, "请求号已用于其它订单");
                }
                Integer unpaid = jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE buyer_id=? AND status=0", Integer.class, input.buyerPlayerId());
                if (unpaid == null || unpaid >= maxUnpaid) { throw new MallException(429, "未支付订单数量已达上限"); }
                String orderId = UUID.randomUUID().toString();
                var frozen = consignments.reserve(input, orderId);
                long now = clock.millis();
                // 寄售仍写同一OMS，sku_id=0明确区分唯一托管资产，不改PMS库存。
                jdbc.update("INSERT INTO oms_order(order_sn,buyer_id,request_id,sku_id,quantity,pay_amount,currency,status,create_time_ms,payment_deadline_ms)"
                    + " VALUES(?,?,?,0,1,?,'CNY',0,?,?)", orderId, input.buyerPlayerId(), ascii(input.requestId()), frozen.priceFen(), now, now + 900_000);
                jdbc.update("INSERT INTO oms_order_item(order_sn,product_id,product_sku_id,product_sku_code,product_quantity,product_price_minor)"
                    + " VALUES(?,0,0,?,1,?)", orderId, "consignment-" + frozen.listingId(), frozen.priceFen());
                return view(Objects.requireNonNull(byId(orderId, false)));
            }));
        } catch (DuplicateKeyException conflict) {
            String existing = consignments.orderByRequest(input.buyerAccount(), input.requestId());
            if (existing != null) { return sameConsignment(existing, input); }
            throw new MallException(409, "寄售购买请求冲突，请使用原请求号重试");
        }
    }
    private MallApi.Order sameConsignment(String orderId, RmbConsignmentApi.Buy input) {
        consignments.sameOrder(orderId, input);
        Stored original = byId(orderId, false);
        if (original == null || !original.buyer().equals(input.buyerPlayerId())) {
            throw new MallException(409, "购买请求号的角色原件不符");
        }
        return view(original);
    }
    public MallApi.Order getConsignment(RmbConsignmentApi.OrderQuery input) {
        if (input == null) { throw new MallException(400, "寄售订单查询参数缺失"); }
        return view(consignmentOwned(input, false));
    }
    public List<MallApi.Order> listConsignments(RmbConsignmentApi.OrderPage input) {
        if (input == null || input.afterOrderId() == null || input.limit() < 1 || input.limit() > 100) {
            throw new MallException(400, "寄售订单分页非法");
        }
        RmbConsignmentStore.account(input.account());
        long after = input.afterOrderId().isEmpty() ? 0 : consignmentOwned(new RmbConsignmentApi.OrderQuery(input.account(), input.afterOrderId()), false).model().getId();
        return jdbc.query("SELECT o.* FROM oms_order o JOIN commerce_consignment_order c ON c.order_sn=o.order_sn "
            + "WHERE c.buyer_account=? AND o.id>? ORDER BY o.id LIMIT ?", (row, index) -> view(stored(row)),
            input.account().getBytes(StandardCharsets.UTF_8), after, input.limit());
    }
    private Stored consignmentOwned(RmbConsignmentApi.OrderQuery input, boolean lock) {
        RmbConsignmentStore.account(input.account()); requireOrderId(input.orderId());
        Stored original = byId(input.orderId(), lock);
        var mapping = consignments.mapping(input.orderId());
        if (original == null || mapping == null || !mapping.buyerAccount().equals(input.account())) {
            throw new MallException(404, "寄售订单不存在");
        }
        return original;
    }
    /** 支付适配器核对买家与原订单条款的只读入口；调用方须在 HTTP 边界完成支付角色认证。 */
    public MallApi.Order paymentOrderView(String orderId) {
        requireOrderId(orderId);
        Stored order = byId(orderId, false);
        if (order == null) {
            throw new MallException(404, "订单不存在");
        }
        return view(order);
    }

    public List<MallApi.Order> list(String buyer,DeliveryApi.OrderPage page){
        MallAuthentication.requirePlayer(buyer);if(page==null||page.limit()<1||page.limit()>100||page.afterOrderId()==null)throw new MallException(400,"订单分页非法");
        CommerceIdentity.account(page.buyerAccount()); CommerceIdentity.revision(page.buyerOwnerRevision());
        long after=0;if(!page.afterOrderId().isEmpty()){after=officialOwned(new MallApi.OwnedOrder(page.afterOrderId(),page.buyerAccount(),page.buyerOwnerRevision()),buyer,false).model().getId();}
        return jdbc.query("SELECT o.* FROM oms_order o JOIN commerce_official_owner p ON p.order_sn=o.order_sn "
            + "WHERE o.buyer_id=? AND p.buyer_account=? AND p.buyer_owner_revision=? AND o.sku_id<>0 AND o.id>? ORDER BY o.id LIMIT ?",
            (row,n)->view(stored(row)),buyer,page.buyerAccount().getBytes(StandardCharsets.UTF_8),ascii(page.buyerOwnerRevision()),after,page.limit());
    }

    public MallApi.Order cancel(String buyer, String orderId) {
        MallAuthentication.requirePlayer(buyer); requireOrderId(orderId);
        throw new MallException(404, "订单取消需要原账号和归属修订");
    }
    public MallApi.Order cancel(String buyer, MallApi.OwnedOrder input) {
        MallAuthentication.requirePlayer(buyer);
        return Objects.requireNonNull(transaction.execute(status -> {
            return cancelOrder(officialOwned(input, buyer, true));
        }));
    }
    public MallApi.Order cancelConsignment(RmbConsignmentApi.OrderQuery input) {
        if (input == null) { throw new MallException(400, "寄售订单取消参数缺失"); }
        return Objects.requireNonNull(transaction.execute(status -> cancelOrder(consignmentOwned(input, true))));
    }
    private MallApi.Order cancelOrder(Stored order) {
            String orderId = order.model().getOrderSn();
            if (order.model().getStatus() == 4) {
                return view(order);
            }
            if (order.model().getStatus() != 0) {
                throw new MallException(409, "已支付订单不能直接取消");
            }
            if (order.skuId() == 0) {
                if (!consignments.cancelled(orderId)) { throw new MallException(503, "寄售订单关联缺失"); }
            } else if (jdbc.update("UPDATE pms_sku_stock SET lock_stock=lock_stock-? WHERE id=? AND lock_stock>=?",
                    order.quantity(), order.skuId(), order.quantity()) != 1) {
                throw new MallException(409, "库存预占状态异常");
            }
            if (jdbc.update("UPDATE oms_order SET status=4 WHERE order_sn=? AND status=0", orderId) != 1) {
                throw new MallException(409, "订单状态冲突");
            }
            order.model().setStatus(4);
            return view(order);
    }

    public MallApi.Order recordTrustedPayment(MallApi.Payment in) {
        requireOrderId(in.orderId());
        if (in.channel() == null || !in.channel().matches("[a-z][a-z0-9_-]{1,31}") || in.transactionId() == null || !in.transactionId().matches("[A-Za-z0-9_-]{1,128}") || in.amountMinor() <= 0 || in.amountMinor() > MAX_AMOUNT_MINOR || in.currency() == null) {
            throw new MallException(400, "支付回执参数非法");
        }
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                Stored order = byId(in.orderId(), true);
                if (order == null) { throw new MallException(404, "订单不存在"); }
                if (order.skuId() != 0) { officialOwner(in.orderId()); }
                if (order.model().getPayAmount().longValueExact() != in.amountMinor() || !order.currency().equals(in.currency())) {
                    throw new MallException(409, "支付金额或币种不匹配");
                }
                paymentPolicy.payment(in);
                if (java.util.Set.of(1,2,5,6,7).contains(order.model().getStatus())) {
                    Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt WHERE order_sn=? AND channel=? AND transaction_id=? AND amount_minor=? AND currency=?", Integer.class,
                            in.orderId(), ascii(in.channel()), ascii(in.transactionId()), in.amountMinor(), in.currency());
                    if (matches != null && matches == 1) { return view(order); }
                    throw new MallException(409, "该订单已有不同支付回执");
                }
                if (order.model().getStatus() == 4) {
                    jdbc.update("INSERT INTO commerce_payment_receipt(channel,transaction_id,order_sn,amount_minor,currency) VALUES(?,?,?,?,?)",ascii(in.channel()),ascii(in.transactionId()),in.orderId(),in.amountMinor(),in.currency());
                    jdbc.update("INSERT INTO commerce_refund_intent(order_sn,status) VALUES(?,'PENDING')",in.orderId());
                    jdbc.update("UPDATE oms_order SET status=5 WHERE order_sn=? AND status=4",in.orderId());order.model().setStatus(5);return view(order);
                }
                if (order.model().getStatus() != 0) { throw new MallException(409, "订单状态冲突"); }
                jdbc.update("INSERT INTO commerce_payment_receipt(channel,transaction_id,order_sn,amount_minor,currency) VALUES (?,?,?,?,?)",
                        ascii(in.channel()), ascii(in.transactionId()), in.orderId(), in.amountMinor(), in.currency());
                // 复用上游 stock/lock_stock 一并扣减结构；补足状态 CAS、幂等收据及非负条件。
                if (order.skuId() == 0) {
                    if (!consignments.paid(in.orderId())) { throw new MallException(503, "寄售订单关联缺失"); }
                } else {
                    int deducted = jdbc.update("UPDATE pms_sku_stock SET stock=stock-?,lock_stock=lock_stock-? WHERE id=? AND stock>=? AND lock_stock>=?",
                        order.quantity(), order.quantity(), order.skuId(), order.quantity(), order.quantity());
                    if (deducted != 1) { throw new MallException(409, "库存预占状态异常"); }
                }
                int changed = jdbc.update("UPDATE oms_order SET status=1,payment_time_ms=? WHERE order_sn=? AND status=0", clock.millis(), in.orderId());
                if (changed != 1) { throw new MallException(409, "订单状态冲突"); }
                jdbc.update("INSERT INTO commerce_delivery_intent(order_sn,buyer_id,sku_id,quantity,status,created_ms) VALUES (?,?,?,?,?,?)",
                        in.orderId(), order.buyer(), order.skuId(), order.quantity(), "PENDING", clock.millis());
                // 此提交只确认支付和交付意图；没有可信游戏资产持久回执，永不迁移到完成。
                order.model().setStatus(1); return view(order);
            }));
        } catch (DuplicateKeyException conflict) { throw new MallException(409, "支付流水已用于其他订单"); }
    }

    private Stored byRequest(String buyer, String requestId) {
        List<Stored> rows = jdbc.query("SELECT * FROM oms_order WHERE buyer_id=? AND request_id=?", (rs, row) -> stored(rs), buyer, ascii(requestId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Stored byId(String orderId, boolean lock) {
        List<Stored> rows = jdbc.query("SELECT * FROM oms_order WHERE order_sn=?" + (lock ? " FOR UPDATE" : ""), (rs, row) -> stored(rs), orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Stored owned(Stored order, String buyer) {
        if (order == null || !order.buyer().equals(buyer)) {
            throw new MallException(404, "订单不存在");
        }
        return order;
    }

    private MallApi.Order sameRequest(Stored prior, MallApi.CreateOrder in) {
        if (prior.skuId() != in.skuId() || prior.quantity() != in.quantity()) { throw new MallException(409, "请求号已用于不同下单参数"); }
        var original = officialOwner(prior.model().getOrderSn());
        if (!original.account().equals(in.buyerAccount()) || !original.revision().equals(in.buyerOwnerRevision())) {
            throw new MallException(409, "请求号的原账号或归属修订不符");
        }
        return view(prior);
    }
    private Stored officialOwned(MallApi.OwnedOrder input, String buyer, boolean lock) {
        if (input == null) { throw new MallException(400, "订单身份缺失"); }
        requireOrderId(input.orderId()); CommerceIdentity.account(input.buyerAccount()); CommerceIdentity.revision(input.buyerOwnerRevision());
        Stored order = owned(byId(input.orderId(), lock), buyer);
        if (order.skuId() == 0) { throw new MallException(404, "请通过原账号的寄售订单入口操作"); }
        var original = officialOwner(input.orderId());
        if (!original.account().equals(input.buyerAccount()) || !original.revision().equals(input.buyerOwnerRevision())) {
            throw new MallException(404, "订单不存在");
        }
        return order;
    }
    private record OfficialOwner(String account, String revision) {}
    private OfficialOwner officialOwner(String orderId) {
        var rows = jdbc.query("SELECT buyer_account,buyer_owner_revision FROM commerce_official_owner WHERE order_sn=?",
            (row,index)->new OfficialOwner(CommerceIdentity.storedAccount(row.getBytes(1)),CommerceIdentity.storedRevision(row.getBytes(2))),orderId);
        if (rows.size() != 1) { throw new MallException(503, "官方订单原身份缺失"); }
        return rows.getFirst();
    }
    private MallApi.Order view(Stored order) {
        String state = switch (order.model().getStatus()) {
            case 0 -> "AWAITING_PAYMENT";
            case 1 -> "DELIVERY_PENDING";
            case 2 -> "DELIVERED";
            case 4 -> "CANCELLED";
            case 5 -> "REFUND_PENDING";
            case 6 -> "REFUNDED";
            case 7 -> "MANUAL";
            default -> throw new MallException(503, "订单状态无法处理");
        };
        return new MallApi.Order(order.model().getOrderSn(), order.buyer(), order.skuId(), order.quantity(),
                order.model().getPayAmount().longValueExact(), order.currency(), state, order.deadline());
    }

    private static void requireOrderId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new MallException(400, "订单号非法");
        }
    }

    private static Sku sku(ResultSet row) throws SQLException {
        PmsSkuStock model = new PmsSkuStock();
        model.setId(row.getLong("id"));
        model.setProductId(row.getLong("product_id"));
        model.setSkuCode(row.getString("sku_code"));
        model.setStock(row.getInt("stock"));
        model.setLockStock(row.getInt("lock_stock"));
        model.setPrice(BigDecimal.valueOf(row.getLong("price_minor")));
        return new Sku(model, row.getString("currency"), row.getInt("published") == 1);
    }

    private static Stored stored(ResultSet row) throws SQLException {
        OmsOrder model = new OmsOrder();
        model.setId(row.getLong("id"));
        model.setOrderSn(row.getString("order_sn"));
        model.setPayAmount(BigDecimal.valueOf(row.getLong("pay_amount")));
        model.setStatus(row.getInt("status"));
        model.setCreateTime(new Date(row.getLong("create_time_ms")));
        return new Stored(model, row.getString("buyer_id"), row.getLong("sku_id"), row.getInt("quantity"),
                row.getString("currency"), row.getLong("payment_deadline_ms"));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private record Sku(PmsSkuStock model, String currency, boolean published) { }
    private record Stored(OmsOrder model, String buyer, long skuId, int quantity, String currency, long deadline) { }
}
