package com.game.player.store;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 角色交易的唯一SQL写面。账号归属仍在player，玩法字节、名字和home zone不复制。
 * 锁序为SQL路由meta→zone围栏→player→原UTF-8字节序账号→listing→tx→最终存盘；事务内禁止调用网络、Redis或场景线程。
 * 请求提交结果不明时只以原listing/tx重放；取消和过户收据永久保留，无TTL解锁。
 */
public final class PlayerTradeStore {
    public enum Status { FREEZING, FROZEN, UNLOCKED, TRANSFERRED }
    public enum Code { INVALID, NOT_FOUND, CONFLICT, FULL, NOT_READY, TIMED_OUT, CORRUPT }
    /** 业务拒绝；只有 {@link RecoveryCommitted} 一个子类型（封闭层级，Code 枚举不扩：它的名字经 game-login 上线）。 */
    public static sealed class Failure extends RuntimeException permits RecoveryCommitted {
        private final Code code;
        public Failure(Code code, String message) { super(message); this.code = code; }
        public Code code() { return code; }
    }
    /**
     * 计划时发现这条旧OPEN已由永久恢复原件退休（同UUID原请求，或同角色同旧代的另一UUID）：既不是新计划也不是未完成，
     * 运维须用该次原计划文件按原请求 apply 只读重放取回原收据。code 记 CONFLICT（新计划被拒）；只携带已校验格式的非敏感字段。
     */
    public static final class RecoveryCommitted extends Failure {
        private final String recoveryId;
        private final String proofMode;
        private RecoveryCommitted(String recoveryId, String proofMode) {
            super(Code.CONFLICT, "旧OPEN已由永久恢复原件退休，只能按原计划只读重放");
            this.recoveryId = recoveryId; this.proofMode = proofMode;
        }
        /** 已提交的那次恢复UUID（规范小写UUID）。 */
        public String recoveryId() { return recoveryId; }
        /** 原收据的证明方式（SCENE_ACK / SQL_NO_ADMISSION）。 */
        public String proofMode() { return proofMode; }
    }
    public record FreezeRequest(long listingId, long playerId, String sellerAccount, long ownerRevision) {}
    public record FreezeReceipt(long listingId, long playerId, String sellerAccount, long ownerRevision,
                                long ownerEpoch, Status status) {}
    public record TransferRequest(long txId, long listingId, long playerId, String sellerAccount,
                                  String buyerAccount, long ownerRevision) {}
    public record TransferReceipt(long txId, long listingId, long playerId, String sellerAccount,
                                  String buyerAccount, long previousRevision, long newRevision) {}
    public record DrainTask(FreezeRequest request, long ownerEpoch, String sourceInstance) {}

    /**
     * 仅在已持本事务player锁时核同库持久排空证据；不能接受调用方自报offline/epoch/快照。
     * 实现只能使用给定事务SQL，不得等待场景或远程服务。缺实现时Confirm必须拒绝。
     */
    @FunctionalInterface public interface FreezeVerifier {
        boolean frozen(JdbcTemplate sql, FreezeReceipt original);
    }
    /** 已持 player 锁后的资格提交复核；生产实现必须与加入帮派共用锁序。 */
    @FunctionalInterface public interface FreezeEligibility {
        void check(JdbcTemplate sql, FreezeRequest request);
    }
    private record FreezeRow(FreezeReceipt receipt, byte[] digest, byte[] token, boolean cancelling) {}
    private record TransferRow(TransferReceipt receipt, byte[] digest, byte[] token, String status) {}
    private record Owner(String account, long revision, long listing, long epoch, boolean released) {}
    private final JdbcTemplate sql;
    private final DataSource source;
    private final PlatformTransactionManager transactions;
    private final LongSupplier millis;
    private final int maxPlayers;
    private final FreezeVerifier verifier;
    private final FreezeEligibility eligibility;
    /** 独立维护目标只限定恢复操作；普通交易不依赖此标记。 */
    public record RecoveryTarget(String nonce,long zoneId,String database,String physicalAddress,String instance) {}
    /** 复用维护CLI的正式实例/强会话核验；必须只读给定SQL连接，不能访问其他服务。 */
    @FunctionalInterface public interface RecoveryInstanceIdentity {
        String read(Connection connection) throws Exception;
    }
    private final RecoveryTarget recoveryTarget;
    private final RecoveryInstanceIdentity recoveryIdentity;

    public PlayerTradeStore(DataSource source, PlatformTransactionManager transactions, LongSupplier millis,
                            int maxPlayers, FreezeVerifier verifier) {
        this(source, transactions, millis, maxPlayers, verifier, null);
    }
    public PlayerTradeStore(DataSource source, PlatformTransactionManager transactions, LongSupplier millis,
                            int maxPlayers, FreezeVerifier verifier, FreezeEligibility eligibility) {
        this(source,transactions,millis,maxPlayers,verifier,eligibility,null,null);
    }
    public PlayerTradeStore(DataSource source, PlatformTransactionManager transactions, LongSupplier millis,
                            int maxPlayers, FreezeVerifier verifier, FreezeEligibility eligibility,
                            RecoveryTarget recoveryTarget, RecoveryInstanceIdentity recoveryIdentity) {
        if ((recoveryTarget==null)!=(recoveryIdentity==null))throw fail(Code.INVALID,"维护目标与实例核验必须成对提供");
        if (recoveryTarget!=null)validateRecoveryTarget(recoveryTarget);
        if (maxPlayers < 1 || maxPlayers > 10_000) throw fail(Code.INVALID, "账号角色上限非法");
        this.sql = new JdbcTemplate(source);
        this.source=Objects.requireNonNull(source);
        this.sql.setQueryTimeout(5);
        this.transactions = Objects.requireNonNull(transactions);
        this.millis = Objects.requireNonNull(millis);
        this.maxPlayers = maxPlayers;
        this.verifier = verifier;
        this.eligibility = eligibility;
        this.recoveryTarget=recoveryTarget;
        this.recoveryIdentity=recoveryIdentity;
    }

    public FreezeReceipt beginFreeze(FreezeRequest request, Duration timeout) {
        validate(request);
        byte[] digest = digest("freeze", request.listingId(), request.playerId(), request.ownerRevision(), request.sellerAccount());
        byte[] token = ascii(UUID.randomUUID().toString());
        return transaction(timeout, () -> {
            routeLock(request.playerId());
            lockAccounts(request.sellerAccount());
            long now = millis.getAsLong();
            sql.update("INSERT INTO player_trade_freeze(listing_id,player_id,seller_account,owner_revision,freeze_epoch,"
                    + "request_digest,creation_token,status,created_ms,updated_ms) VALUES(?,?,?,?,0,?,?,'FREEZING',?,?)"
                    + " ON DUPLICATE KEY UPDATE listing_id=listing_id", request.listingId(), request.playerId(),
                    utf8(request.sellerAccount()), request.ownerRevision(), digest, token, now, now);
            FreezeRow row = freeze(request.listingId());
            match(row, request, digest);
            if (!Arrays.equals(token, row.token())) {
                if (terminal(row.receipt().status())) return row.receipt();
                bound(row.receipt());
                return row.receipt();
            }
            if (request.ownerRevision() == Long.MAX_VALUE) throw fail(Code.CONFLICT, "账号归属修订已耗尽，不能再出售角色");
            Owner owner = owner(request.playerId());
            if (!request.sellerAccount().equals(owner.account()) || request.ownerRevision() != owner.revision()
                    || owner.listing() != 0) throw fail(Code.CONFLICT, "角色归属或交易锁已变化");
            if (RolePendingFence.blocked(sql,request.playerId()))
                throw fail(Code.NOT_READY,"角色仍有历练ACTIVE、待发奖、未终局帮会/邮件资产或匹配在途");
            if (eligibility == null) throw fail(Code.NOT_READY, "角色出售资格提交验证尚未装配");
            eligibility.check(sql, request);
            if (sql.update("UPDATE player SET trade_listing_id=?,updated_at=? WHERE player_id=? AND owner_revision=?"
                    + " AND trade_listing_id=0", request.listingId(), now, request.playerId(), request.ownerRevision()) != 1)
                throw fail(Code.CONFLICT, "角色冻结提交冲突");
            sql.update("UPDATE player_trade_freeze SET freeze_epoch=? WHERE listing_id=?", owner.epoch(), request.listingId());
            return freeze(request.listingId()).receipt();
        });
    }

    public FreezeReceipt confirmFreeze(FreezeRequest request, Duration timeout) {
        validate(request);
        return transaction(timeout, () -> {
            routeLock(request.playerId());
            lockAccounts(request.sellerAccount());
            var row = freeze(request.listingId());
            match(row, request, digest("freeze", request.listingId(), request.playerId(), request.ownerRevision(), request.sellerAccount()));
            if (terminal(row.receipt().status())) return row.receipt();
            bound(row.receipt());
            if (row.cancelling()) throw fail(Code.CONFLICT, "角色撤单排空尚未完成");
            if (verifier != null) sealUnadmitted(row.receipt());
            if (verifier == null || !verifier.frozen(sql, row.receipt())) {
                throw fail(Code.NOT_READY, "尚未取得原场景卸载及入场排空的SQL证明");
            }
            if (row.receipt().status() == Status.FROZEN) return row.receipt();
            sql.update("UPDATE player_trade_freeze SET status='FROZEN',updated_ms=? WHERE listing_id=?", millis.getAsLong(), request.listingId());
            return freeze(request.listingId()).receipt();
        });
    }

    public FreezeReceipt cancelFreeze(FreezeRequest request, Duration timeout) {
        validate(request);
        byte[] digest = digest("freeze", request.listingId(), request.playerId(), request.ownerRevision(), request.sellerAccount());
        byte[] token = ascii(UUID.randomUUID().toString());
        FreezeReceipt receipt = transaction(timeout, () -> {
            routeLock(request.playerId());
            lockAccounts(request.sellerAccount());
            long now = millis.getAsLong();
            // Cancel 可先于未知的 Begin 抵达；原 listing 留永久撤销墓碑，迟到 Begin 只能重放 UNLOCKED。
            sql.update("INSERT INTO player_trade_freeze(listing_id,player_id,seller_account,owner_revision,freeze_epoch,"
                + "request_digest,creation_token,status,created_ms,updated_ms) VALUES(?,?,?,?,0,?,?,'UNLOCKED',?,?)"
                + " ON DUPLICATE KEY UPDATE listing_id=listing_id", request.listingId(), request.playerId(),
                utf8(request.sellerAccount()), request.ownerRevision(), digest, token, now, now);
            var row = freeze(request.listingId());
            match(row, request, digest);
            if (terminal(row.receipt().status())) return row.receipt();
            bound(row.receipt());
            sql.update("UPDATE player_trade_freeze SET cancel_requested=1 WHERE listing_id=?", request.listingId());
            sealUnadmitted(row.receipt());
            if (!verifiedDrain(sql, row.receipt())) return row.receipt();
            unlock(row.receipt());
            return freeze(request.listingId()).receipt();
        });
        // 撤单意图已独立提交；尚未排空不能清锁或伪造UNLOCKED，原请求重试即可。
        if (!terminal(receipt.status())) throw fail(Code.NOT_READY, "撤单已登记，等待原场景与旧入场排空");
        return receipt;
    }

    public TransferReceipt transfer(TransferRequest request, Duration timeout) {
        if (request == null || request.txId() <= 0 || request.ownerRevision() == Long.MAX_VALUE) throw fail(Code.INVALID, "过户请求非法或归属修订已耗尽");
        validate(new FreezeRequest(request.listingId(), request.playerId(), request.sellerAccount(), request.ownerRevision()));
        account(request.buyerAccount());
        if (request.sellerAccount().equals(request.buyerAccount())) throw fail(Code.INVALID, "买卖账号必须不同");
        byte[] digest = digest("transfer", request.txId(), request.listingId(), request.playerId(), request.ownerRevision(),
                request.sellerAccount(), request.buyerAccount());
        byte[] token = ascii(UUID.randomUUID().toString());
        return transaction(timeout, () -> {
            routeLock(request.playerId());
            lockAccounts(request.sellerAccount(), request.buyerAccount());
            FreezeRow listing = freeze(request.listingId());
            var freezeRequest = new FreezeRequest(request.listingId(), request.playerId(), request.sellerAccount(), request.ownerRevision());
            match(listing, freezeRequest, digest("freeze", request.listingId(), request.playerId(), request.ownerRevision(), request.sellerAccount()));
            sql.update("INSERT INTO player_trade_transfer(tx_id,listing_id,player_id,seller_account,buyer_account,previous_revision,"
                    + "new_revision,request_digest,creation_token,status,created_ms) VALUES(?,?,?,?,?,?,0,?,?,'PENDING',?)"
                    + " ON DUPLICATE KEY UPDATE tx_id=tx_id", request.txId(), request.listingId(), request.playerId(),
                    utf8(request.sellerAccount()), utf8(request.buyerAccount()), request.ownerRevision(), digest, token, millis.getAsLong());
            TransferRow previous = transferRow(request.txId());
            if (previous.digest() == null || previous.digest().length != 32 || !validToken(previous.token()))
                throw fail(Code.CORRUPT, "过户收据摘要或创建标记损坏");
            if (!MessageDigest.isEqual(digest, previous.digest()) || !same(previous.receipt(), request))
                throw fail(Code.CONFLICT, "过户请求号已用于不同参数");
            if (!Arrays.equals(token, previous.token())) {
                if (!"APPLIED".equals(previous.status()) || previous.receipt().newRevision() != request.ownerRevision() + 1)
                    throw fail(Code.CORRUPT, "过户收据原件未完成或损坏");
                return previous.receipt();
            }
            if (listing.receipt().status() != Status.FROZEN || listing.cancelling()) throw fail(Code.NOT_READY, "角色尚未完成冻结或正在撤单");
            Owner owner = bound(listing.receipt());
            if (!owner.released() || !verifiedDrain(sql, listing.receipt())) throw fail(Code.NOT_READY, "原写者尚未完成最终释放或排空证明已损坏");
            var blocking = sql.queryForList("SELECT owner_epoch FROM player_final_save WHERE player_id=? AND "
                    + PlayerMapper.BLOCKING_FINAL_STATUS + " ORDER BY owner_epoch LIMIT 1 FOR UPDATE", request.playerId());
            if (!blocking.isEmpty()) throw fail(Code.NOT_READY, "仍有未完成最终写回");
            Long count = sql.queryForObject("SELECT COUNT(*) FROM player WHERE account=?", Long.class, request.buyerAccount());
            if (count == null || count >= maxPlayers) throw fail(Code.FULL, "买家账号角色已满");
            if (owner.revision() == Long.MAX_VALUE) throw fail(Code.CONFLICT, "账号归属修订已耗尽");
            long next = owner.revision() + 1;
            if (sql.update("UPDATE player SET account=?,owner_revision=?,trade_listing_id=0,updated_at=? WHERE player_id=?"
                    + " AND owner_revision=? AND trade_listing_id=?", request.buyerAccount(), next, millis.getAsLong(),
                    request.playerId(), owner.revision(), request.listingId()) != 1) throw fail(Code.CONFLICT, "角色过户提交冲突");
            sql.update("UPDATE player_trade_freeze SET status='TRANSFERRED',updated_ms=? WHERE listing_id=?", millis.getAsLong(), request.listingId());
            sql.update("UPDATE player_trade_transfer SET status='APPLIED',new_revision=? WHERE tx_id=?", next, request.txId());
            return transferRow(request.txId()).receipt();
        });
    }

    private Owner bound(FreezeReceipt receipt) {
        Owner owner = owner(receipt.playerId());
        if (!receipt.sellerAccount().equals(owner.account()) || receipt.ownerRevision() != owner.revision()
                || receipt.listingId() != owner.listing() || receipt.ownerEpoch() != owner.epoch())
            throw fail(Code.CONFLICT, "角色已不属于原冻结请求");
        return owner;
    }
    private Owner owner(long player) {
        var rows = sql.query("SELECT account,owner_revision,trade_listing_id,owner_epoch,owner_released FROM player WHERE player_id=? FOR UPDATE",
                (rs, ignored) -> new Owner(rs.getString(1), revision(rs.getString(2)), rs.getLong(3), rs.getLong(4), rs.getInt(5) == 1), player);
        if (rows.size() != 1) throw fail(Code.NOT_FOUND, "角色不存在");
        var owner = rows.getFirst();
        if (owner.revision() <= 0 || owner.listing() < 0 || owner.epoch() < 0) throw fail(Code.CORRUPT, "角色归属原件损坏");
        return owner;
    }
    private void routeLock(long player){
        var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
        try{com.game.common.player.PlayerRouteAuthority.lock(connection,List.of(player),5);}
        catch(SQLException failure){throw new Failure(Code.NOT_READY,"SQL归属区维护围栏尚未开放");}
        finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,source);}
    }
    private static long revision(String value) {
        try {
            long revision = Long.parseUnsignedLong(value);
            if (revision > 0) return revision;
        } catch (NumberFormatException ignored) { }
        throw fail(Code.CORRUPT, "Java账号归属修订必须在1..LongMax，越界或耗尽拒绝继续");
    }
    private FreezeRow freeze(long listing) {
        var rows = sql.query("SELECT * FROM player_trade_freeze WHERE listing_id=? FOR UPDATE", (rs, ignored) -> {
            Status state;
            try { state = Status.valueOf(rs.getString("status")); }
            catch (IllegalArgumentException | NullPointerException corrupt) { throw fail(Code.CORRUPT, "冻结收据状态损坏"); }
            return new FreezeRow(new FreezeReceipt(rs.getLong("listing_id"), rs.getLong("player_id"), text(rs, "seller_account"),
                    revision(rs.getString("owner_revision")), rs.getLong("freeze_epoch"), state), rs.getBytes("request_digest"), rs.getBytes("creation_token"), rs.getInt("cancel_requested") == 1);
        }, listing);
        if (rows.size() != 1) throw fail(Code.NOT_FOUND, "冻结原请求不存在");
        return rows.getFirst();
    }
    private TransferRow transferRow(long txId) {
        var rows = sql.query("SELECT * FROM player_trade_transfer WHERE tx_id=? FOR UPDATE", (rs, ignored) -> new TransferRow(
                new TransferReceipt(rs.getLong("tx_id"), rs.getLong("listing_id"), rs.getLong("player_id"), text(rs, "seller_account"),
                        text(rs, "buyer_account"), rs.getLong("previous_revision"), rs.getLong("new_revision")),
                rs.getBytes("request_digest"), rs.getBytes("creation_token"), rs.getString("status")), txId);
        if (rows.size() != 1) throw fail(Code.CORRUPT, "过户原请求不存在");
        return rows.getFirst();
    }
    private void lockAccounts(String... values) {
        List<String> accounts = new ArrayList<>(List.of(values));
        accounts.sort((left, right) -> Arrays.compareUnsigned(utf8(left), utf8(right)));
        for (String value : accounts.stream().distinct().toList()) {
            var rows = sql.queryForList("SELECT account FROM account WHERE account=? FOR UPDATE", String.class, value);
            if (rows.size() != 1 || !value.equals(rows.getFirst())) throw fail(Code.NOT_FOUND, "原账号不存在");
        }
    }
    private <T> T transaction(Duration timeout, Supplier<T> work) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw fail(Code.INVALID, "角色交易事务预算必须在0到30秒之间");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("角色交易必须独立事务提交");
        long start = System.nanoTime();
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout((int) Math.max(1, (timeout.toMillis() + 999) / 1000));
        return Objects.requireNonNull(tx.execute(status -> {
            T result = work.get();
            if (System.nanoTime() - start >= timeout.toNanos()) throw fail(Code.TIMED_OUT, "角色交易事务预算已到，提交前回滚");
            return result;
        }));
    }
    private static boolean terminal(Status state) { return state == Status.UNLOCKED || state == Status.TRANSFERRED; }
    private static boolean same(TransferReceipt receipt, TransferRequest request) {
        return receipt.txId() == request.txId() && receipt.listingId() == request.listingId() && receipt.playerId() == request.playerId()
                && receipt.previousRevision() == request.ownerRevision() && receipt.sellerAccount().equals(request.sellerAccount())
                && receipt.buyerAccount().equals(request.buyerAccount());
    }
    private static void match(FreezeRow row, FreezeRequest request, byte[] digest) {
        var receipt = row.receipt();
        if (receipt.ownerEpoch() < 0 || row.digest() == null || row.digest().length != 32 || !validToken(row.token()))
            throw fail(Code.CORRUPT, "冻结收据原件损坏");
        if (!MessageDigest.isEqual(digest, row.digest()) || receipt.listingId() != request.listingId()
                || receipt.playerId() != request.playerId() || !receipt.sellerAccount().equals(request.sellerAccount())
                || receipt.ownerRevision() != request.ownerRevision()) throw fail(Code.CONFLICT, "商品号已用于不同冻结请求");
    }
    private static void validate(FreezeRequest request) {
        if (request == null || request.listingId() <= 0 || request.playerId() == 0 || request.ownerRevision() <= 0)
            throw fail(Code.INVALID, "角色冻结请求非法");
        account(request.sellerAccount());
    }
    private static void account(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 64 || !value.equals(value.strip()))
            throw fail(Code.INVALID, "账号非法，不允许规范化或截断");
        try { StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value)); }
        catch (CharacterCodingException invalid) { throw fail(Code.INVALID, "账号含非法Unicode"); }
    }
    /** 有版本和长度的完整typed摘要，不拼接可歧义的账号分隔串。 */
    private static byte[] digest(String kind, Object... parts) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeUTF("player-trade/v1/" + kind);
            for (Object part : parts) {
                if (part instanceof Long number) { out.writeByte(1); out.writeLong(number); }
                else { byte[] value = utf8((String) part); out.writeByte(2); out.writeInt(value.length); out.write(value); }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String text(ResultSet rs, String column) throws SQLException {
        byte[] bytes = rs.getBytes(column);
        if (bytes == null) throw fail(Code.CORRUPT, "账号收据缺失");
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw fail(Code.CORRUPT, "账号收据编码损坏"); }
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static boolean validToken(byte[] token) {
        if (token == null || token.length != 36) return false;
        String value = new String(token, StandardCharsets.US_ASCII);
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static Failure fail(Code code, String message) { return new Failure(code, message); }

    /** 生产确认者只读实际同库证明；没有证明行时不能用owner_released代替。 */
    public static FreezeVerifier sqlFreezeVerifier() { return PlayerTradeStore::verifiedDrain; }

    private boolean sealUnadmitted(FreezeReceipt receipt) {
        if (!sql.queryForList("SELECT owner_epoch FROM player_final_save WHERE player_id=? AND owner_epoch=? FOR UPDATE",
                receipt.playerId(), receipt.ownerEpoch()).isEmpty()) return false;
        // 更旧OPEN也可能仍有RAM实体；无法排空时保留锁，不能拿租约到期当实例终结证明。
        if (!closedOtherAdmissions(sql, receipt)) return false;
        sql.update("UPDATE player SET owner_released=1,updated_at=? WHERE player_id=? AND owner_epoch=? AND trade_listing_id=?",
                millis.getAsLong(), receipt.playerId(), receipt.ownerEpoch(), receipt.listingId());
        insertDrain(receipt, "SQL_NO_ADMISSION", "", null);
        return true;
    }

    private void unlock(FreezeReceipt receipt) {
        if (sql.update("UPDATE player SET trade_listing_id=0,updated_at=? WHERE player_id=? AND trade_listing_id=? AND owner_revision=?",
                millis.getAsLong(), receipt.playerId(), receipt.listingId(), receipt.ownerRevision()) != 1)
            throw fail(Code.CONFLICT, "角色解冻提交冲突");
        sql.update("UPDATE player_trade_freeze SET status='UNLOCKED',updated_ms=? WHERE listing_id=?", millis.getAsLong(), receipt.listingId());
    }

    /** 固定来源+listing主键续页；每次至多20项，失败项下轮仍可重试，不固定堵在队头。 */
    public List<DrainTask> pendingSceneDrains(String source, long afterListing, int limit) {
        if (source == null || !source.matches("[A-Za-z0-9_-]{1,64}") || afterListing < 0 || limit < 1 || limit > 20)
            throw fail(Code.INVALID, "交易排空扫描参数非法");
        return sql.query("SELECT f.listing_id,f.player_id,f.seller_account,f.owner_revision,f.freeze_epoch FROM player_trade_freeze f"
                + " JOIN player_final_save s ON s.player_id=f.player_id AND s.owner_epoch=f.freeze_epoch"
                + " WHERE f.listing_id>? AND (f.status='FREEZING' OR (f.status='FROZEN' AND f.cancel_requested=1))"
                + " AND s.source_instance=? ORDER BY f.listing_id LIMIT ?", (rs, ignored) -> new DrainTask(
                        new FreezeRequest(rs.getLong(1), rs.getLong(2), text(rs, "seller_account"), rs.getLong(4)), rs.getLong(5), source),
                afterListing, source, limit);
    }

    /** 原scene逻辑线程排空后登记ACK。PENDING只保存ACK；Confirm/解锁仍须核实际APPLIED原件。 */
    public FreezeReceipt recordSceneDrain(DrainTask task, Duration timeout) {
        if (task == null || task.sourceInstance() == null || !task.sourceInstance().matches("[A-Za-z0-9_-]{1,64}"))
            throw fail(Code.INVALID, "排空来源非法");
        var request = task.request(); validate(request);
        return transaction(timeout, () -> {
            routeLock(request.playerId());
            lockAccounts(request.sellerAccount());
            var listing = freeze(request.listingId());
            match(listing, request, digest("freeze", request.listingId(), request.playerId(), request.ownerRevision(), request.sellerAccount()));
            if (terminal(listing.receipt().status())) return listing.receipt();
            bound(listing.receipt());
            if (task.ownerEpoch() != listing.receipt().ownerEpoch()) throw fail(Code.CONFLICT, "排空归属代已变化");
            var finalRows = sql.queryForList("SELECT * FROM player_final_save WHERE player_id=? AND owner_epoch=? FOR UPDATE",
                    request.playerId(), task.ownerEpoch());
            if (finalRows.size() != 1 || !task.sourceInstance().equals(finalRows.getFirst().get("source_instance"))
                    || !List.of("PENDING", "APPLIED").contains(finalRows.getFirst().get("status"))
                    || snapshot(listing.receipt(), finalRows.getFirst()) == null)
                throw fail(Code.NOT_READY, "原场景尚未登记可恢复的完整最终快照");
            insertDrain(listing.receipt(), "SCENE_ACK", task.sourceInstance(), (byte[]) finalRows.getFirst().get("payload_digest"));
            if (listing.cancelling() && verifiedDrain(sql, listing.receipt())) unlock(listing.receipt());
            return freeze(request.listingId()).receipt();
        });
    }

    private void insertDrain(FreezeReceipt receipt, String mode, String source, byte[] digest) {
        sql.update("INSERT INTO player_trade_drain(listing_id,player_id,owner_epoch,proof_mode,source_instance,payload_digest,created_ms)"
                + " VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE listing_id=listing_id", receipt.listingId(), receipt.playerId(),
                receipt.ownerEpoch(), mode, utf8(source), digest, millis.getAsLong());
        var rows = sql.queryForList("SELECT * FROM player_trade_drain WHERE listing_id=?", receipt.listingId());
        if (rows.size() != 1) throw fail(Code.CORRUPT, "旧入场排空证明缺失");
        var original = rows.getFirst();
        if (((Number) original.get("player_id")).longValue() != receipt.playerId()
                || ((Number) original.get("owner_epoch")).longValue() != receipt.ownerEpoch()
                || !mode.equals(original.get("proof_mode")) || !Arrays.equals(utf8(source), (byte[]) original.get("source_instance"))
                || !Arrays.equals(digest, (byte[]) original.get("payload_digest"))) throw fail(Code.CORRUPT, "旧入场排空证明冲突或损坏");
        if (mode.equals("SQL_NO_ADMISSION") && !verifiedDrain(sql, receipt)) throw fail(Code.CORRUPT, "无准入SQL封闭证据损坏");
    }

    private static boolean verifiedDrain(JdbcTemplate sql, FreezeReceipt receipt) {
        return verifiedDrain(sql,receipt,true);
    }
    private static boolean verifiedCurrentDrain(JdbcTemplate sql,FreezeReceipt receipt) {
        return verifiedDrain(sql,receipt,false);
    }
    private static boolean verifiedDrain(JdbcTemplate sql,FreezeReceipt receipt,boolean requireClosedOthers) {
        var rows = sql.queryForList("SELECT * FROM player_trade_drain WHERE listing_id=?", receipt.listingId());
        if (rows.size() != 1) return false;
        var proof = rows.getFirst();
        if (((Number) proof.get("player_id")).longValue() != receipt.playerId()
                || ((Number) proof.get("owner_epoch")).longValue() != receipt.ownerEpoch()) return false;
        var owners = sql.queryForList("SELECT owner_epoch,owner_released FROM player WHERE player_id=?", receipt.playerId());
        if (owners.size() != 1 || ((Number) owners.getFirst().get("owner_epoch")).longValue() != receipt.ownerEpoch()
                || ((Number) owners.getFirst().get("owner_released")).intValue() != 1) return false;
        if (requireClosedOthers && !closedOtherAdmissions(sql, receipt)) return false;
        var finalRows = sql.queryForList("SELECT * FROM player_final_save WHERE player_id=? AND owner_epoch=?", receipt.playerId(), receipt.ownerEpoch());
        String mode = (String) proof.get("proof_mode");
        if ("SQL_NO_ADMISSION".equals(mode)) {
            return finalRows.isEmpty() && proof.get("source_instance") instanceof byte[] source && source.length == 0
                    && proof.get("payload_digest") == null;
        }
        if (!"SCENE_ACK".equals(mode) || finalRows.size() != 1) return false;
        var original = finalRows.getFirst();
        if (!(original.get("source_instance") instanceof String sourceName)) return false;
        return proof.get("source_instance") instanceof byte[] source
                && Arrays.equals(source, utf8(sourceName))
                && proof.get("payload_digest") instanceof byte[] expected
                && original.get("payload_digest") instanceof byte[] actual && MessageDigest.isEqual(expected, actual)
                && applied(sql, receipt, original);
    }
    private static boolean closedOtherAdmissions(JdbcTemplate sql, FreezeReceipt receipt) {
        return sql.queryForList("SELECT owner_epoch FROM player_final_save WHERE player_id=? AND owner_epoch<>?"
                + " AND (owner_epoch>? OR NOT " + PlayerMapper.EXACT_APPLIED + ") ORDER BY owner_epoch LIMIT 1",
                receipt.playerId(), receipt.ownerEpoch(), receipt.ownerEpoch()).isEmpty();
    }

    /** 重算原摘要并核player_state实际字节，不拿状态标记或接管ACCEPTED冒充最终完成。 */
    private static boolean applied(JdbcTemplate sql, FreezeReceipt receipt, java.util.Map<String, Object> row) {
        PlayerRow snapshot = snapshot(receipt, row);
        if (snapshot == null || !"APPLIED".equals(row.get("status"))) return false;
        byte[] data = (byte[]) row.get("data");
        long config = Integer.toUnsignedLong(snapshot.getSceneConfigId());
        var players = sql.queryForList("SELECT level,scene_config_id,pos_x,pos_y,pos_z FROM player WHERE player_id=?", receipt.playerId());
        if (players.size() != 1) return false;
        var player = players.getFirst();
        if (((Number) player.get("level")).longValue() != snapshot.getLevel()
                || ((Number) player.get("scene_config_id")).longValue() != config
                || Double.doubleToRawLongBits(((Number) player.get("pos_x")).doubleValue()) != Double.doubleToRawLongBits(snapshot.getPosX())
                || Double.doubleToRawLongBits(((Number) player.get("pos_y")).doubleValue()) != Double.doubleToRawLongBits(snapshot.getPosY())
                || Double.doubleToRawLongBits(((Number) player.get("pos_z")).doubleValue()) != Double.doubleToRawLongBits(snapshot.getPosZ())) return false;
        var saved = sql.queryForList("SELECT data,saved_epoch FROM player_state WHERE player_id=?", receipt.playerId());
        return saved.size() == 1 && ((Number) saved.getFirst().get("saved_epoch")).longValue() == receipt.ownerEpoch()
                && saved.getFirst().get("data") instanceof byte[] actual && Arrays.equals(data, actual);
    }
    private static PlayerRow snapshot(FreezeReceipt receipt, java.util.Map<String, Object> row) {
        try {
            if (((Number) row.get("save_seq")).longValue() != 1
                    || ((Number) row.get("protocol_version")).intValue() != 1 || !(row.get("data") instanceof byte[] data)
                    || !(row.get("payload_digest") instanceof byte[] expected)) return null;
            PlayerRow snapshot = new PlayerRow(); snapshot.setPlayerId(receipt.playerId()); snapshot.setOwnerEpoch(receipt.ownerEpoch());
            long config = ((Number) row.get("scene_config_id")).longValue();
            if (config < 0 || config > 0xffff_ffffL) return null;
            snapshot.setLevel(((Number) row.get("level")).longValue()); snapshot.setSceneConfigId((int) config);
            snapshot.setPosX(((Number) row.get("pos_x")).doubleValue()); snapshot.setPosY(((Number) row.get("pos_y")).doubleValue());
            snapshot.setPosZ(((Number) row.get("pos_z")).doubleValue());
            com.game.player.store.state.PlayerState.parseFrom(data);
            return MessageDigest.isEqual(expected, PlayerStore.finalSaveDigest(snapshot, data)) ? snapshot : null;
        } catch (IllegalArgumentException | NullPointerException | com.google.protobuf.InvalidProtocolBufferException corrupt) { return null; }
    }    /** 受控维护计划只含原件及摘要；不是调用方自报的已排空/已到账证明。 */
    public record RecoveryRequest(String recoveryId,FreezeRequest freeze,long oldEpoch,String sourceInstance) {}
    public record RecoveryPlan(RecoveryRequest request,String database,long currentEpoch,String admissionHex,
            String proofMode,boolean sealUnusedEpoch,int remainingAdmissions,String remainingAdmissionsDigest,
            String routeGeneration,String routeDigest,String listingDigest,String playerDigest,String stateDigest,
            String finalDigest,String drainDigest,String oldRowDigest,String requestDigest) {}
    public record RecoveryReceipt(String recoveryId,String requestDigest,String status,String proofMode,
                                  int otherOpenAdmissions,boolean unadmittedEpochSealed) {}
    private static final String RECOVERED_OPEN="RETIRED_BY_SQL_SUCCESSOR";
    /** 领域计划原件的编码域（{@link #recoveryBytes} 的首字段；永久收据 plan_bytes 以它开头）。 */
    private static final String RECOVERY_PLAN_FORMAT="player-trade-open-retirement/v2";

    /**
     * 默认纯计划：同原路由/P锁读取真实SQL，不改OPEN、待投、角色数据或交易终局。
     * 维护标记先于路由/P锁：维护目标区（= home）与原入场区各一条，见 {@link #lockRecoveryTargets}。
     * 这条旧OPEN已由永久恢复原件退休时抛 {@link RecoveryCommitted}（不是未完成），提示按原计划只读重放。
     */
    public RecoveryPlan planOpenRecovery(RecoveryRequest request,Duration timeout) {
        validateRecoveryRequest(request);
        requireRecoveryTarget();
        return transaction(timeout,()->{
            // 身份核验之后的一次普通读只决定要锁哪几个区的标记，不授予任何权限；锁内仍逐字重核原件与区号
            var locked=lockRecoveryTargets(()->admissionZoneHint(request));
            requireNotCommitted(request);
            routeLock(request.freeze().playerId());lockAccounts(request.freeze().sellerAccount());
            return recoveryPlanLocked(request,locked);
        });
    }

    /** 唯一提交点：先重核完整计划，再同事务写永久原件收据并退休这一条精确OPEN；不伪造APPLIED。 */
    public RecoveryReceipt applyOpenRecovery(RecoveryPlan approved,Duration timeout) {
        validateRecoveryPlan(approved);
        requireRecoveryTarget();
        long admissionZone=admissionZone(java.util.HexFormat.of().parseHex(approved.admissionHex()));
        return transaction(timeout,()->{
            var locked=lockRecoveryTargets(()->java.util.OptionalLong.of(admissionZone));
            // 永久收据只读重放仍核独立维护目标（含原入场区标记），不借当前角色/下一代身份。
            var replay=readRecovery(approved,false);if(replay!=null)return replay;
            var request=approved.request();
            routeLock(request.freeze().playerId());lockAccounts(request.freeze().sellerAccount());
            var repeated=readRecovery(approved,true);if(repeated!=null)return repeated;
            var actual=recoveryPlanLocked(request,locked);
            if(!approved.equals(actual))throw fail(Code.CONFLICT,"恢复计划原件已改变，原OPEN与所有待办保持不变");
            byte[] encoded=recoveryBytes(approved);
            int inserted=sql.update("INSERT INTO player_trade_admission_recovery(recovery_id,listing_id,player_id,old_epoch,proof_mode,request_digest,plan_bytes,status,created_ms) VALUES(?,?,?,?,?,?,?,?,?)",
                    ascii(request.recoveryId()),request.freeze().listingId(),request.freeze().playerId(),request.oldEpoch(),
                    approved.proofMode(),java.util.HexFormat.of().parseHex(approved.requestDigest()),encoded,RECOVERED_OPEN,millis.getAsLong());
            if(inserted!=1)throw fail(Code.CONFLICT,"永久恢复原件未精确插入");
            if(sql.update("DELETE FROM player_final_save WHERE player_id=? AND owner_epoch=? AND "+PlayerMapper.EXACT_OPEN
                    +" AND save_seq=1 AND protocol_version=1 AND source_instance=? AND admission_proto=?",
                    request.freeze().playerId(),request.oldEpoch(),request.sourceInstance(),
                    java.util.HexFormat.of().parseHex(approved.admissionHex()))!=1)
                throw fail(Code.CONFLICT,"原OPEN退休CAS失败，回滚永久收据");
            if(approved.sealUnusedEpoch()) {
                // 计划已固定这是最后一条旧OPEN；复用正式无准入封闭，不造ACK/APPLIED或新归属。
                var current=freeze(request.freeze().listingId()).receipt();
                if(!sealUnadmitted(current) || !verifiedDrain(sql,current))
                    throw fail(Code.CONFLICT,"原未使用E没有完成SQL_NO_ADMISSION封闭，回滚退休与永久收据");
            }
            return Objects.requireNonNull(readRecovery(approved,true));
        });
    }

    /** @param lockedZones 本事务已在路由/P锁之前持锁核验维护标记的区号（{@link #lockRecoveryTargets} 的返回值） */
    private RecoveryPlan recoveryPlanLocked(RecoveryRequest request,java.util.Set<Long> lockedZones) {
        var wanted=request.freeze();var listing=freeze(wanted.listingId());
        match(listing,wanted,digest("freeze",wanted.listingId(),wanted.playerId(),wanted.ownerRevision(),wanted.sellerAccount()));
        if(terminal(listing.receipt().status()))throw fail(Code.CONFLICT,"终局交易不能补原OPEN恢复");
        var current=bound(listing.receipt());
        if(current.epoch()<=request.oldEpoch())throw fail(Code.NOT_READY,"没有真实更高SQL归属代");
        if(RolePendingFence.blocked(sql,wanted.playerId()) || RolePendingFence.economicBlocked(sql,wanted.playerId(),wanted.listingId()))
            throw fail(Code.NOT_READY,"角色仍有待投或未终局资产，恢复不改原待办");
        if(!sql.queryForList("SELECT owner_epoch FROM player_final_save WHERE player_id=? AND (owner_epoch>? OR "
                +PlayerMapper.BLOCKING_FINAL_STATUS+") ORDER BY owner_epoch LIMIT 1 FOR UPDATE",wanted.playerId(),current.epoch()).isEmpty())
            throw fail(Code.NOT_READY,"存在PENDING、未知或超前最终写，禁止退休OPEN");
        var currentFinal=sql.queryForList("SELECT owner_epoch FROM player_final_save WHERE player_id=? AND owner_epoch=? FOR UPDATE",wanted.playerId(),current.epoch());
        var proofs=sql.queryForList("SELECT proof_mode FROM player_trade_drain WHERE listing_id=? FOR UPDATE",wanted.listingId());
        String mode;
        if(currentFinal.isEmpty()) {
            // load两重载都先在同P写OPEN才返回快照。冻结已持P并阻新load；无行证明本E没有已登记RAM。
            if(!proofs.isEmpty())throw fail(Code.NOT_READY,"无准入E出现既有排空原件，禁止维护追认");
            requireActualStoredState(wanted.playerId(),current.epoch());
            mode="SQL_NO_ADMISSION";
        } else {
            if(!current.released()||proofs.size()!=1||!"SCENE_ACK".equals(proofs.getFirst().get("proof_mode"))
                    ||!verifiedCurrentDrain(sql,listing.receipt()))
                throw fail(Code.NOT_READY,"已准入更高代缺少原SCENE_ACK与实际两SQL完整APPLIED");
            mode="SCENE_ACK";
        }
        String remainingQuery="SELECT * FROM player_final_save WHERE player_id=? AND owner_epoch<>? AND owner_epoch<>? AND NOT "
                +PlayerMapper.EXACT_APPLIED+" ORDER BY owner_epoch FOR UPDATE";
        long remaining=sql.queryForObject("SELECT COUNT(*) FROM player_final_save WHERE player_id=? AND owner_epoch<>? AND owner_epoch<>? AND NOT "
                +PlayerMapper.EXACT_APPLIED,Long.class,wanted.playerId(),current.epoch(),request.oldEpoch());
        if(remaining<0||remaining>Integer.MAX_VALUE)throw fail(Code.CORRUPT,"剩余旧准入数量越界");
        String remainingDigest=rawRows(false,remainingQuery,wanted.playerId(),current.epoch(),request.oldEpoch());
        boolean sealUnused=mode.equals("SQL_NO_ADMISSION")&&remaining==0;
        var old=sql.queryForList("SELECT * FROM player_final_save WHERE player_id=? AND owner_epoch=? AND "+PlayerMapper.EXACT_OPEN+" FOR UPDATE",
                wanted.playerId(),request.oldEpoch());
        if(old.size()!=1)throw fail(Code.NOT_READY,"指定旧OPEN缺失或已变化；ABSENT不是恢复成功");
        var original=old.getFirst();
        if(!request.sourceInstance().equals(original.get("source_instance"))
                ||((Number)original.get("save_seq")).longValue()!=1||((Number)original.get("protocol_version")).intValue()!=1
                ||original.get("data")!=null||original.get("payload_digest")!=null||original.get("applied_at")!=null
                ||original.get("level")!=null||original.get("scene_config_id")!=null||original.get("pos_x")!=null
                ||original.get("pos_y")!=null||original.get("pos_z")!=null
                ||!(original.get("admission_proto") instanceof byte[] admission))
            throw fail(Code.NOT_READY,"旧OPEN来源、协议或完整入场原件不符");
        final long admissionZone;
        try {
            var identity=com.game.proto.common.base.RoleAdmissionIdentity.parseFrom(admission);
            if(identity.getPlayerId()!=wanted.playerId()||identity.getOwnerEpoch()!=request.oldEpoch()
                    ||identity.getOwnerRevision()!=wanted.ownerRevision()||!identity.getSceneInstanceId().equals(request.sourceInstance())
                    ||!Arrays.equals(admission,com.game.common.player.SceneAdmissionAuthority.original(identity)))
                throw fail(Code.CORRUPT,"旧完整入场身份不是原角色/来源/归属代");
            admissionZone=Integer.toUnsignedLong(identity.getZoneId());
        } catch(com.google.protobuf.InvalidProtocolBufferException | SecurityException invalid){throw fail(Code.CORRUPT,"旧入场原件无法解析或不完整");}
        var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
        try {
            String catalog=connection.getCatalog();if(catalog==null||catalog.isBlank())throw fail(Code.NOT_READY,"无法确定真实业务库");
            // 维护目标区号仍是角色唯一home（player.zone_id）；跨zone传送后入场的Scene区可以不同，但它的标记必须已在本事务先于路由/P锁持锁核验
            long zone=sql.queryForObject("SELECT zone_id FROM player WHERE player_id=?",Long.class,wanted.playerId());
            if(zone!=recoveryTarget.zoneId())throw fail(Code.CONFLICT,"维护目标区号与原角色不符");
            if(!lockedZones.contains(admissionZone))throw fail(Code.CONFLICT,"原入场区号的维护标记没有在本事务持锁核验（并发改动或缺标记）");
            String generation=sql.queryForObject("SELECT generation FROM route_meta WHERE id=1",String.class);
            String route=digestHex("recovery-route",rawRows("SELECT * FROM route_meta WHERE id=1"),
                    rawRows("SELECT * FROM zone_merge_fence WHERE zone_id=? ORDER BY shard",zone));
            var plan=new RecoveryPlan(request,catalog,current.epoch(),java.util.HexFormat.of().formatHex(admission),mode,sealUnused,(int)remaining,remainingDigest,generation,route,
                    rawRows("SELECT * FROM player_trade_freeze WHERE listing_id=?",wanted.listingId()),
                    rawRows("SELECT * FROM player WHERE player_id=? FOR UPDATE",wanted.playerId()),
                    rawRows("SELECT * FROM player_state WHERE player_id=? FOR UPDATE",wanted.playerId()),
                    rawRows(false,"SELECT * FROM player_final_save WHERE player_id=? AND owner_epoch=? FOR UPDATE",wanted.playerId(),current.epoch()),
                    rawRows(false,"SELECT * FROM player_trade_drain WHERE listing_id=? FOR UPDATE",wanted.listingId()),
                    rawRows("SELECT * FROM player_final_save WHERE player_id=? AND owner_epoch=? FOR UPDATE",wanted.playerId(),request.oldEpoch()),"");
            return recoveryDigest(plan);
        } catch(SQLException failure){throw fail(Code.NOT_READY,"真实恢复数据库身份不可读");}
        finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,source);}
    }

    /** 原SQL存档是唯一初始基底；维护不补空、不改savedE、不借RAM快照。 */
    private void requireActualStoredState(long player,long currentEpoch) {
        var states=sql.queryForList("SELECT data,saved_epoch FROM player_state WHERE player_id=? FOR UPDATE",player);
        if(states.size()!=1||!(states.getFirst().get("data") instanceof byte[] bytes)||bytes.length>16_777_215
                ||!(states.getFirst().get("saved_epoch") instanceof Number epoch)||epoch.longValue()<0||epoch.longValue()>currentEpoch)
            throw fail(Code.NOT_READY,"原player_state缺失、NULL或存档代次不合法，禁止无准入封闭");
        try{com.game.player.store.state.PlayerState.parseFrom(bytes);}
        catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw fail(Code.NOT_READY,"原player_state不是合法protobuf，维护不补初始数据");}
    }

    private static void validateRecoveryTarget(RecoveryTarget target) {
        if(target.nonce()==null||!target.nonce().matches("[0-9a-f]{32}")||target.nonce().equals("0".repeat(32))
                ||target.zoneId()<=0||target.zoneId()>0xffff_ffffL
                ||target.database()==null||!target.database().matches("[A-Za-z0-9_]{1,64}")
                ||target.instance()==null||target.instance().isBlank()||target.instance().length()>1024)
            throw fail(Code.INVALID,"独立维护目标scope或nonce无效");
        try {
            String address=target.physicalAddress();
            if(address==null||address.length()>255||address.chars().anyMatch(c->Character.isWhitespace(c)||c=='\\'||c=='@'||c=='|'))
                throw new IllegalArgumentException();
            var uri=java.net.URI.create("mysql://"+address);
            if(uri.getHost()==null||uri.getHost().isBlank()||uri.getPort()<1||uri.getPort()>65535
                    ||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                    ||!uri.getRawPath().isEmpty()||!address.equals(uri.getRawAuthority())
                    ||!address.substring(address.lastIndexOf(':')+1).equals(Integer.toString(uri.getPort())))
                throw new IllegalArgumentException();
        } catch(RuntimeException invalid){throw fail(Code.INVALID,"维护物理地址必须为无凭据的规范host:port");}
    }
    private void requireRecoveryTarget() {
        if(recoveryTarget==null||recoveryIdentity==null)throw fail(Code.NOT_READY,"恢复必须显式提供独立维护目标");
        validateRecoveryTarget(recoveryTarget);
    }
    /**
     * 标记行锁在路由/P锁之前取得；运维在维护命令之外预置，不设TTL或自动创建。跨zone（Java 已做跨 zone 传送，holder 可能在别的 zone 的 Scene 上）时，
     * 维护目标区（= 角色 home）与原入场区各要一条同nonce/同物理地址/同库的标记：去重后按区号升序逐条 FOR UPDATE（所有维护事务同一顺序，不成环），
     * 两区相同只核一次；缺任一条即拒绝，此时尚无任何领域读写锁与副作用。库/实例身份照旧只在本连接核一次。
     *
     * @param admissionZone 原入场区号（无符号 uint32），在本连接的库/实例身份核验之后才取；为空 = 计划前置普通读没取到原件，
     *                      只锁维护目标区，交由锁内原件校验按原规则拒绝
     * @return 本事务已持锁核验的区号集合（升序、不可变）
     */
    private java.util.SortedSet<Long> lockRecoveryTargets(Supplier<java.util.OptionalLong> admissionZone) {
        requireRecoveryTarget();
        var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
        try {
            if(!recoveryTarget.database().equals(connection.getCatalog())
                    ||!recoveryTarget.instance().equals(recoveryIdentity.read(connection)))
                throw fail(Code.CONFLICT,"事务内实际库或实例身份改变");
            var zones=new java.util.TreeSet<Long>();
            zones.add(recoveryTarget.zoneId());
            admissionZone.get().ifPresent(zones::add);
            for(long zone:zones) {
                var markers=sql.query("SELECT target_nonce,physical_addr FROM maintenance_recovery_target WHERE purpose=? AND zone_id=? AND schema_name=? FOR UPDATE",
                        (rs,ignored)->new byte[][]{rs.getBytes(1),rs.getBytes(2)},ascii("admission"),zone,ascii(recoveryTarget.database()));
                if(markers.size()!=1||!Arrays.equals(markers.getFirst()[0],ascii(recoveryTarget.nonce()))
                        ||!Arrays.equals(markers.getFirst()[1],ascii(recoveryTarget.physicalAddress())))
                    throw fail(Code.CONFLICT,"专用维护标记缺失或scope/nonce/物理地址不同");
            }
            return java.util.Collections.unmodifiableSortedSet(zones);
        } catch(Failure failure){throw failure;}
        catch(Exception unreadable){throw fail(Code.NOT_READY,"事务内维护目标无法核验");}
        finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,source);}
    }
    /** 原完整入场身份里的区号（无符号）；原件无法解析即 CORRUPT。 */
    private static long admissionZone(byte[] admission) {
        try {
            return Integer.toUnsignedLong(com.game.proto.common.base.RoleAdmissionIdentity.parseFrom(admission).getZoneId());
        } catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw fail(Code.CORRUPT,"原入场区号无法解析");}
    }
    /**
     * 计划在标记锁之前的一次普通读（不加锁）：只用来决定还要锁哪个区的维护标记，不授予任何权限。读不到 / NULL / 解析失败都返回空，
     * 交给锁内的原件校验按原规则报 NOT_READY / CORRUPT；锁内读到的区号若不在已锁集合里（并发改动）同样拒绝。
     */
    private java.util.OptionalLong admissionZoneHint(RecoveryRequest request) {
        try {
            var rows=sql.queryForList("SELECT admission_proto FROM player_final_save WHERE player_id=? AND owner_epoch=? AND "+PlayerMapper.EXACT_OPEN,
                    request.freeze().playerId(),request.oldEpoch());
            if(rows.size()!=1||!(rows.getFirst().get("admission_proto") instanceof byte[] admission))return java.util.OptionalLong.empty();
            return java.util.OptionalLong.of(Integer.toUnsignedLong(com.game.proto.common.base.RoleAdmissionIdentity.parseFrom(admission).getZoneId()));
        } catch(com.google.protobuf.InvalidProtocolBufferException | org.springframework.dao.DataAccessException unreadable) {
            return java.util.OptionalLong.empty();
        }
    }
    /**
     * 计划先认永久恢复原件（标记锁之后、路由/P锁之前，与 apply 的只读重放同序；只读，不改任何行）：
     * 同 UUID 已提交且原请求逐字段一致 → {@link RecoveryCommitted}；同 UUID 绑定了另一原请求 → CONFLICT；
     * 同角色同旧代已被另一 UUID 退休 → {@link RecoveryCommitted}（带那次的 UUID，运维用那次原计划重放）。
     * 否则旧 OPEN 已消失时会落到「指定旧OPEN缺失」的 NOT_READY，被误读成恢复未完成。
     */
    private void requireNotCommitted(RecoveryRequest request) {
        var byId=sql.queryForList("SELECT listing_id,player_id,old_epoch,proof_mode,status,plan_bytes FROM player_trade_admission_recovery WHERE recovery_id=?",
                ascii(request.recoveryId()));
        if(byId.size()>1)throw fail(Code.CORRUPT,"永久恢复原件不唯一");
        if(byId.size()==1) {
            var row=byId.getFirst();
            if(!committedFor(row,request))throw fail(Code.CONFLICT,"恢复UUID已绑定另一原件或永久收据损坏");
            throw new RecoveryCommitted(request.recoveryId(),proofModeOf(row));
        }
        var byEpoch=sql.queryForList("SELECT recovery_id,proof_mode,status FROM player_trade_admission_recovery WHERE player_id=? AND old_epoch=?",
                request.freeze().playerId(),request.oldEpoch());
        if(byEpoch.size()>1)throw fail(Code.CORRUPT,"永久恢复原件不唯一");
        if(byEpoch.isEmpty())return;
        var row=byEpoch.getFirst();
        if(!RECOVERED_OPEN.equals(row.get("status"))||!(row.get("recovery_id") instanceof byte[] raw))throw fail(Code.CORRUPT,"永久恢复收据损坏");
        String original=new String(raw,StandardCharsets.US_ASCII);
        try{if(!UUID.fromString(original).toString().equals(original))throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){throw fail(Code.CORRUPT,"永久恢复收据UUID损坏");}
        throw new RecoveryCommitted(original,proofModeOf(row));
    }
    private static String proofModeOf(java.util.Map<String,Object> row) {
        Object mode=row.get("proof_mode");
        if(!"SCENE_ACK".equals(mode)&&!"SQL_NO_ADMISSION".equals(mode))throw fail(Code.CORRUPT,"永久恢复收据证明方式损坏");
        return (String)mode;
    }
    /** 永久收据是否就是本次原请求：列与 plan_bytes 前缀（{@link #recoveryBytes} 的请求部分，同写入顺序）逐字段一致。 */
    private static boolean committedFor(java.util.Map<String,Object> row,RecoveryRequest request) {
        var wanted=request.freeze();
        if(((Number)row.get("listing_id")).longValue()!=wanted.listingId()||((Number)row.get("player_id")).longValue()!=wanted.playerId()
                ||((Number)row.get("old_epoch")).longValue()!=request.oldEpoch()||!RECOVERED_OPEN.equals(row.get("status"))
                ||!(row.get("plan_bytes") instanceof byte[] plan))return false;
        try(var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(plan))) {
            return RECOVERY_PLAN_FORMAT.equals(in.readUTF())&&request.recoveryId().equals(in.readUTF())
                    &&in.readLong()==wanted.listingId()&&in.readLong()==wanted.playerId()&&wanted.sellerAccount().equals(in.readUTF())
                    &&in.readLong()==wanted.ownerRevision()&&in.readLong()==request.oldEpoch()&&request.sourceInstance().equals(in.readUTF());
        } catch(java.io.IOException corrupt){return false;}
    }
    private static void validateRecoveryRequest(RecoveryRequest request) {
        if(request==null||request.freeze()==null)throw fail(Code.INVALID,"恢复缺少原冻结请求");
        validate(request.freeze());
        if(request.oldEpoch()<=0||request.sourceInstance()==null||!request.sourceInstance().matches("[A-Za-z0-9_-]{1,64}"))
            throw fail(Code.INVALID,"原OPEN代次或来源无效");
        try{if(!UUID.fromString(request.recoveryId()).toString().equals(request.recoveryId()))throw new IllegalArgumentException();}
        catch(RuntimeException invalid){throw fail(Code.INVALID,"恢复操作必须使用固定规范UUID");}
    }
    private static void validateRecoveryPlan(RecoveryPlan plan) {
        if(plan==null)throw fail(Code.INVALID,"恢复计划缺失");validateRecoveryRequest(plan.request());
        if(plan.database()==null||plan.database().isBlank()||plan.currentEpoch()<=plan.request().oldEpoch()
                ||plan.admissionHex()==null||plan.admissionHex().isEmpty()||plan.admissionHex().length()>2*com.game.common.player.SceneAdmissionAuthority.MAX_ORIGINAL_BYTES
                ||!plan.admissionHex().matches("[0-9a-f]+")||(plan.admissionHex().length()&1)!=0
                ||plan.routeGeneration()==null||plan.routeGeneration().isBlank()
                ||!("SCENE_ACK".equals(plan.proofMode())||"SQL_NO_ADMISSION".equals(plan.proofMode()))
                ||plan.remainingAdmissions()<0||plan.sealUnusedEpoch()!=(plan.proofMode().equals("SQL_NO_ADMISSION")&&plan.remainingAdmissions()==0))
            throw fail(Code.INVALID,"恢复计划范围或完整原件不合法");
        for(String digest:new String[]{plan.remainingAdmissionsDigest(),plan.routeDigest(),plan.listingDigest(),plan.playerDigest(),plan.stateDigest(),plan.finalDigest(),plan.drainDigest(),plan.oldRowDigest(),plan.requestDigest()})
            if(digest==null||!digest.matches("[0-9a-f]{64}"))throw fail(Code.INVALID,"恢复计划摘要不合法");
        if(!recoveryDigest(plan).requestDigest().equals(plan.requestDigest()))throw fail(Code.CONFLICT,"恢复计划完整摘要不符");
    }
    private static RecoveryPlan recoveryDigest(RecoveryPlan p) {
        String digest=hexSha(recoveryBytes(p));
        return new RecoveryPlan(p.request(),p.database(),p.currentEpoch(),p.admissionHex(),p.proofMode(),p.sealUnusedEpoch(),p.remainingAdmissions(),p.remainingAdmissionsDigest(),p.routeGeneration(),p.routeDigest(),
                p.listingDigest(),p.playerDigest(),p.stateDigest(),p.finalDigest(),p.drainDigest(),p.oldRowDigest(),digest);
    }
    private static byte[] recoveryBytes(RecoveryPlan p) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);var r=p.request();var f=r.freeze();
            out.writeUTF(RECOVERY_PLAN_FORMAT);
            out.writeUTF(r.recoveryId());out.writeLong(f.listingId());out.writeLong(f.playerId());out.writeUTF(f.sellerAccount());
            out.writeLong(f.ownerRevision());out.writeLong(r.oldEpoch());out.writeUTF(r.sourceInstance());out.writeUTF(p.database());
            out.writeLong(p.currentEpoch());out.writeUTF(p.admissionHex());out.writeUTF(p.proofMode());out.writeBoolean(p.sealUnusedEpoch());
            out.writeInt(p.remainingAdmissions());out.writeUTF(p.remainingAdmissionsDigest());out.writeUTF(p.routeGeneration());out.writeUTF(p.routeDigest());
            out.writeUTF(p.listingDigest());out.writeUTF(p.playerDigest());out.writeUTF(p.stateDigest());out.writeUTF(p.finalDigest());
            out.writeUTF(p.drainDigest());out.writeUTF(p.oldRowDigest());return bytes.toByteArray();
        } catch(java.io.IOException invalid){throw fail(Code.INVALID,"恢复计划不能按固定类型编码");}
    }
    private RecoveryReceipt readRecovery(RecoveryPlan plan,boolean lock) {
        var rows=sql.queryForList("SELECT * FROM player_trade_admission_recovery WHERE recovery_id=?"+(lock?" FOR UPDATE":""),ascii(plan.request().recoveryId()));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw fail(Code.CORRUPT,"永久恢复原件不唯一");
        var row=rows.getFirst();
        if(((Number)row.get("listing_id")).longValue()!=plan.request().freeze().listingId()
                ||((Number)row.get("player_id")).longValue()!=plan.request().freeze().playerId()
                ||((Number)row.get("old_epoch")).longValue()!=plan.request().oldEpoch()
                ||((Number)row.get("created_ms")).longValue()<=0
                ||!RECOVERED_OPEN.equals(row.get("status"))||!plan.proofMode().equals(row.get("proof_mode"))||!(row.get("plan_bytes") instanceof byte[] original)
                ||!Arrays.equals(original,recoveryBytes(plan))||!(row.get("request_digest") instanceof byte[] digest)
                ||!MessageDigest.isEqual(digest,java.util.HexFormat.of().parseHex(plan.requestDigest())))
            throw fail(Code.CONFLICT,"恢复UUID已绑定另一原件或永久收据损坏");
        return new RecoveryReceipt(plan.request().recoveryId(),plan.requestDigest(),RECOVERED_OPEN,plan.proofMode(),plan.remainingAdmissions(),plan.sealUnusedEpoch());
    }
    /** 全列类型化摘要；二进制保原字节，标量按JDBC类型规范编码；保持NULL/列名/行边界与16MiB上限。 */
    private String rawRows(String query,Object... arguments) { return rawRows(true,query,arguments); }
    private String rawRows(boolean requireRows,String query,Object... arguments) {
        return sql.query(query,rs->{
            try {
                var bytes=new ByteArrayOutputStream();var encoded=new DataOutputStream(bytes);encoded.writeUTF("player-trade-recovery-sql/v2");
                var metadata=rs.getMetaData();var columns=new java.util.TreeMap<String,Integer>();
                for(int i=1;i<=metadata.getColumnCount();i++)if(columns.put(metadata.getColumnName(i).toLowerCase(java.util.Locale.ROOT),i)!=null)
                    throw fail(Code.CORRUPT,"SQL证明重复列");
                int count=0;
                while(rs.next()) {
                    encoded.writeByte(1);count++;
                    for(var column:columns.entrySet()) {
                        int type=metadata.getColumnType(column.getValue());
                        encoded.writeUTF(column.getKey());encoded.writeInt(type);
                        byte[] value=recoverySqlValue(rs,column.getValue(),type);
                        encoded.writeBoolean(value!=null);if(value!=null){encoded.writeInt(value.length);encoded.write(value);}
                        if(bytes.size()>16*1024*1024)throw fail(Code.CORRUPT,"恢复SQL证明超过16MiB");
                    }
                }
                if(requireRows&&count==0)throw fail(Code.NOT_READY,"恢复实际SQL证明缺行");encoded.writeByte(0);return hexSha(bytes.toByteArray());
            }catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
        },arguments);
    }
    /** 仅支持恢复所读取正式表的JDBC类型；未知类型拒绝，不能退为驱动自选的二进制转换。 */
    private static byte[] recoverySqlValue(ResultSet row,int column,int type) throws SQLException {
        return switch(type) {
            case java.sql.Types.BINARY,java.sql.Types.VARBINARY,java.sql.Types.LONGVARBINARY,java.sql.Types.BLOB -> row.getBytes(column);
            case java.sql.Types.CHAR,java.sql.Types.VARCHAR,java.sql.Types.LONGVARCHAR,
                 java.sql.Types.NCHAR,java.sql.Types.NVARCHAR,java.sql.Types.LONGNVARCHAR,
                 java.sql.Types.CLOB,java.sql.Types.NCLOB -> {
                String value=row.getString(column);yield value==null?null:utf8(value);
            }
            case java.sql.Types.TINYINT,java.sql.Types.SMALLINT,java.sql.Types.INTEGER,java.sql.Types.BIGINT -> {
                var value=row.getBigDecimal(column);
                try{yield value==null?null:utf8(value.toBigIntegerExact().toString());}
                catch(ArithmeticException invalid){throw fail(Code.CORRUPT,"SQL整数证明不是精确整数");}
            }
            case java.sql.Types.NUMERIC,java.sql.Types.DECIMAL -> {
                var value=row.getBigDecimal(column);yield value==null?null:utf8(value.toPlainString());
            }
            case java.sql.Types.REAL,java.sql.Types.FLOAT,java.sql.Types.DOUBLE -> {
                double value=row.getDouble(column);boolean missing=row.wasNull();
                if(!missing&&!Double.isFinite(value))throw fail(Code.CORRUPT,"SQL浮点证明不是有限值");
                yield missing?null:ByteBuffer.allocate(Long.BYTES).putLong(Double.doubleToRawLongBits(value)).array();
            }
            case java.sql.Types.BOOLEAN,java.sql.Types.BIT -> {
                if(type==java.sql.Types.BIT&&row.getMetaData().getPrecision(column)!=1)
                    throw fail(Code.CORRUPT,"SQL位证明不是单一布尔值");
                boolean value=row.getBoolean(column);yield row.wasNull()?null:new byte[]{(byte)(value?1:0)};
            }
            default -> throw fail(Code.CORRUPT,"恢复SQL证明含未支持的JDBC类型");
        };
    }
    private static String hexSha(byte[] raw) {
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String digestHex(String kind,Object... fields){return java.util.HexFormat.of().formatHex(digest(kind,fields));}


}
