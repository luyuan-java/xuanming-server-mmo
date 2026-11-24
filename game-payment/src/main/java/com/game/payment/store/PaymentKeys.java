package com.game.payment.store;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 支付服务的自然键确定性派生工具（与 Go {@code payment/internal/data/tables.go} 逐字节一致）。
 * 所有主键（订单键、交易键、实名消费主体键、退款幂等单号）均从业务自然键确定性派生，不依赖外部发号器。
 */
public final class PaymentKeys {

    private static final Pattern ORDER_ID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern OUT_TRADE_NO_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final HexFormat HEX = HexFormat.of();

    private PaymentKeys() {}

    /** 128 位大端无符号双字键（以两个 64 位 long 存储全部 128 bit）。 */
    public record Key128(long hi, long lo) {
        public boolean isZero() {
            return hi == 0L && lo == 0L;
        }
    }

    /** 将小写 UUID 商城订单号拆成高低两个 64 位主键；格式非法或全零返回 {@code null}。 */
    public static Key128 orderKey(String orderId) {
        if (orderId == null || !ORDER_ID_PATTERN.matcher(orderId).matches()) {
            return null;
        }
        String compact = orderId.replace("-", "");
        byte[] raw = HEX.parseHex(compact);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        long hi = buf.getLong();
        long lo = buf.getLong();
        if (hi == 0L && lo == 0L) {
            return null;
        }
        return new Key128(hi, lo);
    }

    /** 由主键两列还原标准小写 UUID 订单号文本。 */
    public static String orderId(long hi, long lo) {
        byte[] raw = ByteBuffer.allocate(16).putLong(hi).putLong(lo).array();
        String hex = HEX.formatHex(raw);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
    }

    /** 订单号转 32 位十六进制商户单号（去掉连字符）；非法返回 {@code null}。 */
    public static String outTradeNo(String orderId) {
        return orderKey(orderId) == null ? null : orderId.replace("-", "");
    }

    /** 32 位商户单号还原商城订单号；非本服务格式或全零返回 {@code null}。 */
    public static String orderIdFromOutTradeNo(String outTradeNo) {
        if (outTradeNo == null || !OUT_TRADE_NO_PATTERN.matcher(outTradeNo).matches()) {
            return null;
        }
        String orderId = outTradeNo.substring(0, 8) + "-" + outTradeNo.substring(8, 12) + "-"
                + outTradeNo.substring(12, 16) + "-" + outTradeNo.substring(16, 20) + "-" + outTradeNo.substring(20, 32);
        return orderKey(orderId) == null ? null : orderId;
    }

    /** 渠道交易号是否满足商城回执字符集约束（1–128 位 {@code [A-Za-z0-9_-]}）。 */
    public static boolean validTransactionId(String transactionId) {
        return transactionId != null && TRANSACTION_ID_PATTERN.matcher(transactionId).matches();
    }

    /** 由渠道交易号派生付款事实主键后两列：SHA-256 前 16 字节。 */
    public static Key128 txnKey(String transactionId) {
        return sha256Key128(transactionId);
    }

    /** 由国家系统实名标识 {@code pi} 派生未成年人消费主体键：SHA-256("spender|" + pi) 前 16 字节。 */
    public static Key128 spenderKey(String pi) {
        return sha256Key128("spender|" + pi);
    }

    /** 由渠道名与交易号确定性派生 32 字符退款幂等单号（{@code "rf" + hex(SHA-256("refund|ch|txn")[:15])}）。 */
    public static String outRefundNo(String channelName, String transactionId) {
        byte[] sum = sha256("refund|" + channelName + "|" + transactionId);
        return "rf" + HEX.formatHex(sum, 0, 15);
    }

    private static Key128 sha256Key128(String text) {
        byte[] sum = sha256(text);
        ByteBuffer buf = ByteBuffer.wrap(sum, 0, 16);
        return new Key128(buf.getLong(), buf.getLong());
    }

    public static byte[] sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
