package com.game.mall;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 原账号和修订作为字节保存；此处不冒充场景交付或真实外部支付验收。 */
class OfficialOrderOwnershipTest {
    private static final String ACCOUNT = " 原账号 ", REVISION = "7";
    private static MallApi.CreateOrder create(String request, String account, String revision) {
        return new MallApi.CreateOrder(request,1,1,account,revision);
    }
    private static MallApi.OwnedOrder owned(String order) { return new MallApi.OwnedOrder(order,ACCOUNT,REVISION); }
    @Test void 幂等键不能换账号或归属修订_原字节不trim() {
        try (var f = new MockPaymentTestDatabase()) {
            f.sku(1,10,100); var input=create("owned-order-0001",ACCOUNT,REVISION); var order=f.store.create("91",input);
            assertEquals(order,f.store.create("91",input));
            for(var changed:List.of(create(input.requestId(),"buyer","8"),create(input.requestId(),ACCOUNT,"9"),create(input.requestId(),ACCOUNT.trim(),REVISION)))
                assertEquals(409,assertThrows(MallException.class,()->f.store.create("91",changed)).status());
            assertEquals(1,f.count("oms_order"));assertEquals(1,f.count("commerce_official_owner"));
            assertEquals(ACCOUNT,CommerceIdentity.storedAccount(f.jdbc.queryForObject("SELECT buyer_account FROM commerce_official_owner",byte[].class)));
        }
    }
    @Test void 历史查询取消和列表需原身份_同角色新主和同账号ABA均拒绝() {
        try(var f=new MockPaymentTestDatabase()){
            f.sku(1,10,100);var order=f.store.create("91",create("owned-order-0002",ACCOUNT,REVISION));
            for(var foreign:List.of(new MallApi.OwnedOrder(order.orderId(),"new-owner","8"),new MallApi.OwnedOrder(order.orderId(),ACCOUNT,"9"))){
                assertEquals(404,assertThrows(MallException.class,()->f.store.get("91",foreign)).status());
                assertEquals(404,assertThrows(MallException.class,()->f.store.cancel("91",foreign)).status());
                assertTrue(f.store.list("91",new DeliveryApi.OrderPage("",20,foreign.buyerAccount(),foreign.buyerOwnerRevision())).isEmpty());
            }
            assertEquals(404,assertThrows(MallException.class,()->f.store.get("91",order.orderId())).status());
            assertEquals(404,assertThrows(MallException.class,()->f.store.cancel("91",order.orderId())).status());
            assertEquals(order,f.store.get("91",owned(order.orderId())));
            assertEquals("CANCELLED",f.store.cancel("91",owned(order.orderId())).state());
            assertEquals(0,f.value("pms_sku_stock","lock_stock"));
        }
    }
    @Test void 线协议完整uint64修订冻结并进入交付意图_不转signedLong() {
        try(var f=new MockPaymentTestDatabase()){
            f.sku(1,10,100);String max="18446744073709551615";
            var order=f.store.create(max,create("owned-order-0003",ACCOUNT,max));
            f.store.recordTrustedPayment(new MallApi.Payment(order.orderId(),"test","owned-payment-0003",100,"CNY"));
            var delivery=new DeliveryStore(f.jdbc,f.manager,f.props,com.game.common.RunMode.TEST);
            var intent=delivery.poll(20).getFirst();assertEquals(ACCOUNT,intent.buyerAccount());assertEquals(max,intent.buyerOwnerRevision());
            assertEquals(order.buyerId(),intent.buyerId());assertEquals(100,intent.amountMinor());
        }
    }
    @Test void 非规范修订或非法原账号在任何库存和订单副作用前拒绝() {
        try(var f=new MockPaymentTestDatabase()){
            f.sku(1,10,100);
            for(String revision:new String[]{null,"","0","01","+1"," 1","1 ","-1","18446744073709551616","1.0"})
                assertEquals(400,assertThrows(MallException.class,()->f.store.create("91",create("invalid-owner-0004",ACCOUNT,revision))).status());
            for(String account:new String[]{null,"","x".repeat(192),"\ud800"})
                assertEquals(400,assertThrows(MallException.class,()->f.store.create("91",create("invalid-owner-0004",account,REVISION))).status());
            assertEquals(0,f.count("oms_order"));assertEquals(0,f.count("commerce_official_owner"));assertEquals(0,f.value("pms_sku_stock","lock_stock"));
        }
    }
    @Test void 丢失身份原件则付款交付和终局均拒绝_不从当前角色推导回填() {
        try(var f=new MockPaymentTestDatabase()){
            f.sku(1,10,100);var order=f.store.create("91",create("owned-order-0005",ACCOUNT,REVISION));
            f.jdbc.update("DELETE FROM commerce_official_owner WHERE order_sn=?",order.orderId());
            var payment=new MallApi.Payment(order.orderId(),"test","owned-payment-0005",100,"CNY");
            assertEquals(503,assertThrows(MallException.class,()->f.store.recordTrustedPayment(payment)).status());
            assertEquals(0,f.count("commerce_payment_receipt"));assertEquals(0,f.count("commerce_delivery_intent"));
            f.jdbc.update("INSERT INTO commerce_official_owner VALUES(?,?,?)",order.orderId(),ACCOUNT.getBytes(java.nio.charset.StandardCharsets.UTF_8),REVISION.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            f.store.recordTrustedPayment(payment);f.jdbc.update("DELETE FROM commerce_official_owner WHERE order_sn=?",order.orderId());
            var delivery=new DeliveryStore(f.jdbc,f.manager,f.props,com.game.common.RunMode.TEST);
            assertEquals(503,assertThrows(MallException.class,()->delivery.poll(20)).status());
            assertEquals(503,assertThrows(MallException.class,()->delivery.receipt(new DeliveryApi.Receipt(order.orderId(),"101","DELIVERED","a".repeat(64)))).status());
            assertEquals(0,f.count("commerce_delivery_receipt"));
        }
    }
}
