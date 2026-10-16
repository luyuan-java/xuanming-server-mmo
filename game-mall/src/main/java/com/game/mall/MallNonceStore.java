package com.game.mall;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 签名的防重放事实保存在同一 SQL 权威域，不以各应用副本的本地时钟裁决有效窗口。 */
public final class MallNonceStore {
    private final JdbcTemplate jdbc;
    private final String databaseNow;
    private final TransactionTemplate transaction;
    private final AtomicInteger calls = new AtomicInteger();

    public MallNonceStore(JdbcTemplate jdbc, PlatformTransactionManager manager, MallProperties properties) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
        boolean h2 = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().equals("H2")));
        databaseNow = h2 ? "CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP())*1000 AS BIGINT)"
                : properties.requireTidb() ? "(TIDB_CURRENT_TSO() >> 18)"
                : "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)";
        jdbc.setQueryTimeout(5);
    }

    public void claim(String role, String nonce, long signedAtMs) {
        transaction.executeWithoutResult(status -> claimInTransaction(role, nonce, signedAtMs));
    }

    private void claimInTransaction(String role, String nonce, long signedAtMs) {
        // JDBC延迟BEGIN时只设置autoCommit=false仍会得到TSO=0；先实际读当前主键启动事务。
        // 不加锁、不争共享热行，最终由同事务唯一约束消费nonce。
        jdbc.query("SELECT nonce FROM commerce_request_nonce WHERE caller_role=? AND nonce=?",
                (row, index) -> row.getBytes(1), role, nonce.getBytes(StandardCharsets.US_ASCII));
        Long transactionNow = jdbc.queryForObject("SELECT " + databaseNow, Long.class);
        if (transactionNow == null || transactionNow <= 0) {
            throw new MallException(503, "认证数据库时钟不可用");
        }
        if ((calls.getAndIncrement() & 63) == 0) {
            long now = transactionNow;
            // 一次最多128行，索引是启动门禁；过签名窗口后额外保留10分钟，覆盖数据库锁等待。
            for (Expired expired : jdbc.query("SELECT caller_role,nonce FROM commerce_request_nonce WHERE expires_ms<? ORDER BY expires_ms,caller_role,nonce LIMIT 128",
                    (row, index) -> new Expired(row.getString(1), row.getBytes(2)), now)) {
                jdbc.update("DELETE FROM commerce_request_nonce WHERE caller_role=? AND nonce=? AND expires_ms<?", expired.role(), expired.nonce(), now);
            }
        }
        try {
            int changed = jdbc.update("INSERT INTO commerce_request_nonce(caller_role,nonce,expires_ms) SELECT ?,?,? WHERE ?>="
                            + databaseNow + "-120000 AND ?<=" + databaseNow + "+120000",
                    role, nonce.getBytes(StandardCharsets.US_ASCII), signedAtMs + 720_000, signedAtMs, signedAtMs);
            if (changed != 1) {
                throw new MallException(401, "签名请求已过期");
            }
        } catch (DuplicateKeyException replay) {
            throw new MallException(409, "签名请求已使用，请以新nonce重试原业务请求号");
        }
    }

    private record Expired(String role, byte[] nonce) { }
}
