package com.game.player.store;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    public static final class Failure extends RuntimeException {
        private final Code code;
        public Failure(Code code, String message) { super(message); this.code = code; }
        public Code code() { return code; }
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

    public PlayerTradeStore(DataSource source, PlatformTransactionManager transactions, LongSupplier millis,
                            int maxPlayers, FreezeVerifier verifier) {
        this(source, transactions, millis, maxPlayers, verifier, null);
    }
    public PlayerTradeStore(DataSource source, PlatformTransactionManager transactions, LongSupplier millis,
                            int maxPlayers, FreezeVerifier verifier, FreezeEligibility eligibility) {
        if (maxPlayers < 1 || maxPlayers > 10_000) throw fail(Code.INVALID, "账号角色上限非法");
        this.sql = new JdbcTemplate(source);
        this.source=Objects.requireNonNull(source);
        this.sql.setQueryTimeout(5);
        this.transactions = Objects.requireNonNull(transactions);
        this.millis = Objects.requireNonNull(millis);
        this.maxPlayers = maxPlayers;
        this.verifier = verifier;
        this.eligibility = eligibility;
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
        var rows = sql.queryForList("SELECT * FROM player_trade_drain WHERE listing_id=?", receipt.listingId());
        if (rows.size() != 1) return false;
        var proof = rows.getFirst();
        if (((Number) proof.get("player_id")).longValue() != receipt.playerId()
                || ((Number) proof.get("owner_epoch")).longValue() != receipt.ownerEpoch()) return false;
        var owners = sql.queryForList("SELECT owner_epoch,owner_released FROM player WHERE player_id=?", receipt.playerId());
        if (owners.size() != 1 || ((Number) owners.getFirst().get("owner_epoch")).longValue() != receipt.ownerEpoch()
                || ((Number) owners.getFirst().get("owner_released")).intValue() != 1) return false;
        if (!closedOtherAdmissions(sql, receipt)) return false;
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
    }
}
