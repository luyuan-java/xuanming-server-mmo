package com.game.mail;

import static com.game.discovery.sql.AuthorityJdbc.u64;

import com.game.api.asset.MailGrants;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetStream;
import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.discovery.sql.AuthorityIds;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.asset.PersistedAssetLedger;
import com.game.proto.TipInfoMessage;
import com.game.proto.mail.*;
import com.game.table.CommonErrorTip;
import com.game.table.MailErrorTip;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/** 全局邮件权威：邮箱守卫→状态→操作账本的锁序；广播附件只认封存名单。 */
public final class MailStore {
    public static final class Reject extends RuntimeException {
        final int tip;
        Reject(int tip) {
            this.tip = tip;
        }
    }
    static final int INVALID = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int NOT_FOUND = MailErrorTip.mail_error.kMailNotFound_VALUE;
    static final int EXPIRED = MailErrorTip.mail_error.kMailExpired_VALUE;
    static final int CONFLICT = MailErrorTip.mail_error.kMailRequestConflict_VALUE;
    static final int FULL = MailErrorTip.mail_error.kMailRecipientBoxFull_VALUE;
    static final int PENDING = MailErrorTip.mail_error.kMailClaimPending_VALUE;
    static final int MANUAL = MailErrorTip.mail_error.kMailClaimManual_VALUE;
    private final AuthorityJdbc sql;
    private final AuthorityIds ids;
    private final IntPredicate itemExists;
    private final LongSupplier clock;
    private static final String MC = "mail_id,owner_player_id,guild_id,kind,sender_kind,request_id,request_hash,title,body,attachments,"
                                     + "sent_ms,expire_ms,campaign_id,manifest_generation";
    public MailStore(AuthorityJdbc sql, AuthorityIds ids, IntPredicate itemExists, LongSupplier clock) {
        this.sql = sql;
        this.ids = ids;
        this.itemExists = itemExists;
        this.clock = clock;
    }
    public static int sender(String caller) {
        return switch (caller) {
            case "admin" -> 1;
            case "guild" -> 2;
            case "rank" -> 3;
            case "trade" -> 4;
            case "battle" -> 5;
            default -> 0;
        };
    }
    private static void require(boolean condition, int tip) {
        if (!condition)
            throw new Reject(tip);
    }
    private static Object U(long value) {
        return u64(value);
    }
    private record Mail(long id, long owner, long guild, int kind, int sender, long request, String hash, String title, String body,
            byte[] attachments, long sent, long expire, long campaign, long generation) {}
    private record State(long read, long deleted, int claim, long op, long version, int reason) {}
    private static Mail mail(ResultSet r) throws SQLException {
        return new Mail(u64(r, 1), u64(r, 2), u64(r, 3), r.getInt(4), r.getInt(5), u64(r, 6), r.getString(7), r.getString(8),
                r.getString(9), r.getBytes(10), u64(r, 11), u64(r, 12), u64(r, 13), u64(r, 14));
    }
    private static State state(ResultSet r, int offset) throws SQLException {
        return new State(
                u64(r, offset), u64(r, offset + 1), r.getInt(offset + 2), u64(r, offset + 3), u64(r, offset + 4), r.getInt(offset + 5));
    }
    private static MailAttachments attachments(Mail m) throws SQLException {
        try {
            return MailAttachments.parseFrom(m.attachments);
        } catch (Exception e) {
            throw new SQLException("邮件附件无法解码", e);
        }
    }
    private static MailView view(Mail m, State s) throws SQLException {
        var b = MailView.newBuilder()
                        .setMailId(m.id)
                        .setKindValue(m.kind)
                        .setTitle(m.title)
                        .setBody(m.body)
                        .setSentMs(m.sent)
                        .setExpireMs(m.expire)
                        .setIsRead(s.read != 0)
                        .setClaimStateValue(s.claim)
                        .setAssetOpId(s.op)
                        .setAttachments(attachments(m));
        if (s.reason != 0)
            b.setClaimReason(TipInfoMessage.newBuilder().setId(s.reason));
        return b.build();
    }
    private void ensure(Deadline d, long player) throws SQLException {
        sql.read(d, s -> {
            s.update("INSERT IGNORE INTO mail_box (player_id,created_ms) VALUES (?,?)", U(player), clock.getAsLong());
            return null;
        });
    }
    private static void lock(AuthorityJdbc.Session s, long player) throws SQLException {
        require(s.one("SELECT player_id FROM mail_box WHERE player_id=? FOR UPDATE", r -> u64(r, 1), U(player)) != null, CONFLICT);
    }
    private static String visible() {
        return "m.kind IN (1,2,3) AND (m.owner_player_id=? OR (m.owner_player_id=0 AND ((m.campaign_id>0 AND EXISTS (SELECT 1 FROM "
               + "mail_campaign_recipient q WHERE q.sender_kind=m.sender_kind AND q.campaign_id=m.campaign_id AND "
               + "q.manifest_generation=m.manifest_generation AND q.player_id=?)) OR (m.kind=2 AND m.campaign_id=0) OR (m.kind=3 AND "
               + "m.campaign_id=0 AND EXISTS (SELECT 1 FROM guild_member gm WHERE gm.player_id=? AND gm.guild_id=m.guild_id)))))";
    }
    private static String cols() {
        return "m." + MC.replace(",", ",m.");
    }
    private static String stateCols() {
        return "COALESCE(t.read_ms,0),COALESCE(t.deleted_ms,0),COALESCE(t.claim_state,IF(LENGTH(m.attachments)>0,1,0)),COALESCE(t.current_"
               + "op_id,0),COALESCE(t.claim_version,0),COALESCE(t.reason_tip_id,0)";
    }
    private static Object[] args(long player, long now) {
        return new Object[] {U(player), U(player), U(player), U(player), now};
    }

    public SendMailResponse send(String caller, int kind, SendMailRequest in, Deadline d) throws SQLException {
        require(sender(caller) != 0 && sender(caller) == in.getSenderKind() && in.getRequestId() != 0 && !in.getTitle().isBlank()
                        && in.getTitle().codePointCount(0, in.getTitle().length()) <= 128
                        && in.getBody().codePointCount(0, in.getBody().length()) <= 4096 && in.getUnknownFields().asMap().isEmpty(),
                INVALID);
        String hash = ServiceCallSignatures.digest(in);
        // 判重在当前时钟和配表校验之前，旧成功不能被过期/满箱重判。
        var old = sql.read(d,
                s
                -> s.one("SELECT " + MC + " FROM mail_message WHERE sender_kind=? AND request_id=?", MailStore::mail, in.getSenderKind(),
                        U(in.getRequestId())));
        if (old != null) {
            int oldKind = old.kind;
            if (oldKind == 0)
                oldKind = sql.read(d,
                        s
                        -> s.one("SELECT kind FROM mail_campaign WHERE sender_kind=? AND campaign_id=? AND manifest_generation=?",
                                r -> r.getInt(1), old.sender, U(old.campaign), U(old.generation)));
            require(old.hash.equals(hash) && oldKind == kind, CONFLICT);
            return SendMailResponse.newBuilder().setMailId(old.id).setDuplicated(true).build();
        }
        try {
            MailGrants.bundle(in.getAttachments(), itemExists);
        } catch (IllegalArgumentException invalid) {
            throw new Reject(MailErrorTip.mail_error.kMailAttachmentInvalid_VALUE);
        }
        byte[] payload = in.getAttachments().toByteArray();
        long now = clock.getAsLong(), expire = in.getExpireMs() == 0 ? now + 30L * 86400000 : in.getExpireMs();
        require(expire > now && expire - now <= 30L * 86400000, INVALID);
        boolean campaign = kind != 1 && payload.length > 0;
        if (kind == 1)
            require(in.getRecipientPlayerId() != 0 && in.getGuildId() == 0, INVALID);
        else if (kind == 2)
            require(caller.equals("admin") && in.getRecipientPlayerId() == 0 && in.getGuildId() == 0, INVALID);
        else if (kind == 3)
            require(caller.equals("guild") && in.getRecipientPlayerId() == 0 && in.getGuildId() != 0, INVALID);
        else
            throw new Reject(INVALID);
        if (campaign)
            require(in.getCampaignId() != 0 && in.getManifestGeneration() != 0 && in.getExpectedRecipientCount() > 0
                            && in.getExpectedRecipientCount() <= 1000000 && in.getExpectedShardCount() > 0
                            && in.getExpectedShardCount() <= 10000 && in.getManifestHash().matches("[0-9a-f]{64}"),
                    INVALID);
        else
            require(in.getCampaignId() == 0 && in.getManifestGeneration() == 0 && in.getExpectedRecipientCount() == 0
                            && in.getExpectedShardCount() == 0 && in.getManifestHash().isEmpty(),
                    INVALID);
        long id = ids.next(d), guard = kind == 1 ? in.getRecipientPlayerId() : 0;
        ensure(d, guard);
        return sql.tx(d, s -> {
            lock(s, guard);
            var previous = s.one("SELECT " + MC + " FROM mail_message WHERE sender_kind=? AND request_id=?", MailStore::mail,
                    in.getSenderKind(), U(in.getRequestId()));
            if (previous != null) {
                require(previous.hash.equals(hash), CONFLICT);
                return SendMailResponse.newBuilder().setMailId(previous.id).setDuplicated(true).build();
            }
            long count = kind == 1
                    ? s.count("SELECT COUNT(*) FROM mail_message m JOIN mail_state t ON t.mail_id=m.mail_id AND "
                              + "t.player_id=m.owner_player_id WHERE m.owner_player_id=? AND t.deleted_ms=0 AND (m.expire_ms>? OR "
                              + "t.claim_state NOT IN (0,1,3))",
                              U(guard), now)
                    : s.count("SELECT COUNT(*) FROM mail_message WHERE owner_player_id=0 AND kind IN (0,2,3) AND expire_ms>?", now);
            require(count < (kind == 1 ? 200 : 100), FULL);
            s.update("INSERT INTO mail_message (" + MC + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", U(id), U(in.getRecipientPlayerId()),
                    U(in.getGuildId()), campaign ? 0 : kind, in.getSenderKind(), U(in.getRequestId()), hash, in.getTitle(), in.getBody(),
                    payload, now, expire, U(in.getCampaignId()), U(in.getManifestGeneration()));
            if (kind == 1)
                s.update("INSERT INTO mail_state "
                         + "(player_id,mail_id,read_ms,deleted_ms,claim_state,current_op_id,claim_version,reason_tip_id,updated_ms) VALUES "
                         + "(?,?,0,0,?,0,0,0,?)",
                        U(guard), U(id), payload.length == 0 ? 0 : 1, now);
            if (campaign)
                s.update("INSERT INTO mail_campaign "
                         + "(sender_kind,campaign_id,manifest_generation,mail_id,request_id,producer,request_hash,title,body,attachments,"
                         + "expire_ms,expected_recipient_count,expected_shard_count,manifest_hash,state,created_ms,sealed_ms,kind,guild_id)"
                         + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,0,?,?)",
                        in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()), U(id), U(in.getRequestId()), caller, hash,
                        in.getTitle(), in.getBody(), payload, expire, U(in.getExpectedRecipientCount()), in.getExpectedShardCount(),
                        in.getManifestHash(), now, kind, U(in.getGuildId()));
            return SendMailResponse.newBuilder().setMailId(id).build();
        });
    }
    static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    private record Campaign(long mail, String producer, long expected, int shards, String hash, int state, long expire, int kind) {}
    private static Campaign campaign(AuthorityJdbc.Session s, int sender, long id, long generation) throws SQLException {
        var c = s.one("SELECT mail_id,producer,expected_recipient_count,expected_shard_count,manifest_hash,state,expire_ms,kind FROM "
                      + "mail_campaign WHERE sender_kind=? AND campaign_id=? AND manifest_generation=? FOR UPDATE",
                r
                -> new Campaign(u64(r, 1), r.getString(2), u64(r, 3), r.getInt(4), r.getString(5), r.getInt(6), u64(r, 7), r.getInt(8)),
                sender, U(id), U(generation));
        require(c != null, NOT_FOUND);
        return c;
    }
    public ImportMailRecipientsResponse importRecipients(String caller, ImportMailRecipientsRequest in, Deadline d) throws SQLException {
        require(sender(caller) == in.getSenderKind() && in.getCampaignId() != 0 && in.getManifestGeneration() != 0
                        && in.getPlayerIdsCount() > 0 && in.getPlayerIdsCount() <= 1000,
                INVALID);
        var recipients = new TreeSet<Long>(Long::compareUnsigned);
        recipients.addAll(in.getPlayerIdsList());
        require(!recipients.contains(0L), INVALID);
        StringBuilder text = new StringBuilder();
        recipients.forEach(id -> text.append(Long.toUnsignedString(id)).append('\n'));
        String hash = sha(text.toString());
        return sql.tx(d, s -> {
            var c = campaign(s, in.getSenderKind(), in.getCampaignId(), in.getManifestGeneration());
            require(c.producer.equals(caller) && Integer.compareUnsigned(in.getShardIndex(), c.shards) < 0, INVALID);
            var old = s.one("SELECT shard_hash FROM mail_campaign_shard WHERE sender_kind=? AND campaign_id=? AND manifest_generation=? "
                            + "AND shard_index=?",
                    r -> r.getString(1), in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()), in.getShardIndex());
            if (old != null) {
                require(old.equals(hash), CONFLICT);
                return ImportMailRecipientsResponse.newBuilder().setImportedCount(recipients.size()).setDuplicated(true).build();
            }
            require(c.state == 1, CONFLICT);
            require(s.count("SELECT COUNT(*) FROM mail_campaign_recipient WHERE sender_kind=? AND campaign_id=? AND manifest_generation=?",
                            in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()))
                                    + recipients.size()
                            <= c.expected,
                    INVALID);
            for (long player : recipients) {
                require(s.count("SELECT COUNT(*) FROM mail_campaign_recipient WHERE sender_kind=? AND campaign_id=? AND "
                                + "manifest_generation=? AND player_id=?",
                                in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()), U(player))
                                == 0,
                        CONFLICT);
                s.update("INSERT INTO mail_campaign_recipient (sender_kind,campaign_id,manifest_generation,player_id,shard_index) VALUES "
                         + "(?,?,?,?,?)",
                        in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()), U(player), in.getShardIndex());
            }
            s.update("INSERT INTO mail_campaign_shard (sender_kind,campaign_id,manifest_generation,shard_index,shard_hash,recipient_count) "
                     + "VALUES (?,?,?,?,?,?)",
                    in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()), in.getShardIndex(), hash, recipients.size());
            return ImportMailRecipientsResponse.newBuilder().setImportedCount(recipients.size()).build();
        });
    }
    public SendMailResponse seal(String caller, SealMailCampaignRequest in, Deadline d) throws SQLException {
        require(sender(caller) == in.getSenderKind() && in.getCampaignId() != 0 && in.getManifestGeneration() != 0, INVALID);
        return sql.tx(d, s -> {
            var c = campaign(s, in.getSenderKind(), in.getCampaignId(), in.getManifestGeneration());
            require(c.producer.equals(caller), INVALID);
            if (c.state == 2)
                return SendMailResponse.newBuilder().setMailId(c.mail).setDuplicated(true).build();
            require(c.expire > clock.getAsLong(), EXPIRED);
            var shards = s.list("SELECT shard_index,shard_hash,recipient_count FROM mail_campaign_shard WHERE sender_kind=? AND "
                                + "campaign_id=? AND manifest_generation=? ORDER BY shard_index",
                    r
                    -> r.getInt(1) + ":" + r.getString(2) + ":" + r.getInt(3),
                    in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()));
            require(shards.size() == c.shards, CONFLICT);
            StringBuilder manifest = new StringBuilder("v1\n");
            long count = 0;
            for (int i = 0; i < shards.size(); i++) {
                String value = shards.get(i);
                require(value.startsWith(i + ":"), CONFLICT);
                count += Long.parseLong(value.substring(value.lastIndexOf(':') + 1));
                manifest.append(value).append('\n');
            }
            require(count == c.expected && sha(manifest.toString()).equals(c.hash)
                            && s.count("SELECT COUNT(*) FROM mail_campaign_recipient WHERE sender_kind=? AND campaign_id=? AND "
                                       + "manifest_generation=?",
                                       in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()))
                                    == count,
                    CONFLICT);
            s.update("UPDATE mail_message SET kind=?,sent_ms=? WHERE mail_id=? AND kind=0", c.kind, clock.getAsLong(), U(c.mail));
            s.update("UPDATE mail_campaign SET state=2,sealed_ms=? WHERE sender_kind=? AND campaign_id=? AND manifest_generation=?",
                    clock.getAsLong(), in.getSenderKind(), U(in.getCampaignId()), U(in.getManifestGeneration()));
            return SendMailResponse.newBuilder().setMailId(c.mail).build();
        });
    }
    public ListMailsResponse list(long player, ListMailsRequest in, Deadline d) throws SQLException {
        require(player != 0, INVALID);
        int limit = in.getLimit() == 0 ? 50 : Math.min(100, in.getLimit());
        require(limit > 0, INVALID);
        long now = clock.getAsLong();
        return sql.snapshot(d, s -> {
            String base = " FROM mail_message m LEFT JOIN mail_state t ON t.player_id=? AND t.mail_id=m.mail_id WHERE " + visible()
                    + " AND COALESCE(t.deleted_ms,0)=0 AND (m.expire_ms>? OR t.claim_state NOT IN (0,1,3))";
            var result = ListMailsResponse.newBuilder().setServerNowMs(now);
            var counts = s.one("SELECT COALESCE(SUM(COALESCE(t.read_ms,0)=0),0),COALESCE(SUM(m.expire_ms>" + now
                            + " AND COALESCE(t.claim_state,IF(LENGTH(m.attachments)>0,1,0))=1),0)" + base,
                    r -> new int[] {r.getInt(1), r.getInt(2)}, args(player, now));
            result.setUnreadCount(counts[0]).setClaimableCount(counts[1]);
            var values = new ArrayList<>(Arrays.asList(args(player, now)));
            String query = "SELECT " + cols() + "," + stateCols() + base;
            if (in.getBeforeMailId() != 0) {
                query += " AND m.mail_id<?";
                values.add(U(in.getBeforeMailId()));
            }
            query += " ORDER BY m.mail_id DESC LIMIT ?";
            values.add(limit + 1);
            var views = s.list(query, r -> view(mail(r), state(r, 15)), values.toArray());
            for (int i = 0; i < Math.min(limit, views.size()); i++) result.addMails(views.get(i));
            if (views.size() > limit)
                result.setNextBeforeMailId(views.get(limit - 1).getMailId());
            return result.build();
        });
    }
    private record Locked(Mail mail, State state) {}
    private Locked materialize(AuthorityJdbc.Session s, long player, long id, long now) throws SQLException {
        var m = s.one("SELECT " + cols() + " FROM mail_message m WHERE m.mail_id=? AND " + visible(), MailStore::mail, U(id), U(player),
                U(player), U(player));
        require(m != null, NOT_FOUND);
        var state = s.one("SELECT read_ms,deleted_ms,claim_state,current_op_id,claim_version,reason_tip_id FROM mail_state WHERE "
                          + "player_id=? AND mail_id=? FOR UPDATE",
                r -> state(r, 1), U(player), U(id));
        if (state == null) {
            require(m.expire > now, EXPIRED);
            state = new State(0, 0, m.attachments.length == 0 ? 0 : 1, 0, 0, 0);
            s.update("INSERT INTO mail_state "
                     + "(player_id,mail_id,read_ms,deleted_ms,claim_state,current_op_id,claim_version,reason_tip_id,updated_ms) VALUES "
                     + "(?,?,0,0,?,0,0,0,?)",
                    U(player), U(id), state.claim, now);
        }
        require(state.deleted == 0, NOT_FOUND);
        return new Locked(m, state);
    }
    public ReadMailResponse read(long player, ReadMailRequest in, Deadline d) throws SQLException {
        require(player != 0 && in.getMailId() != 0, INVALID);
        ensure(d, player);
        return sql.tx(d, s -> {
            lock(s, player);
            var v = materialize(s, player, in.getMailId(), clock.getAsLong());
            require(v.mail.expire > clock.getAsLong() || (v.state.claim != 0 && v.state.claim != 1 && v.state.claim != 3), EXPIRED);
            s.update("UPDATE mail_state SET read_ms=IF(read_ms=0,?,read_ms),updated_ms=? WHERE player_id=? AND mail_id=?",
                    clock.getAsLong(), clock.getAsLong(), U(player), U(in.getMailId()));
            return ReadMailResponse.newBuilder()
                    .setMail(view(v.mail, new State(clock.getAsLong(), 0, v.state.claim, v.state.op, v.state.version, v.state.reason)))
                    .build();
        });
    }
    public DeleteMailsResponse delete(long player, List<Long> ids, boolean clear, Deadline d) throws SQLException {
        require(player != 0 && (clear || (!ids.isEmpty() && ids.size() <= 100)), INVALID);
        ensure(d, player);
        long now = clock.getAsLong();
        return sql.tx(d, s -> {
            lock(s, player);
            var out = DeleteMailsResponse.newBuilder();
            var targets = clear ? s.list("SELECT m.mail_id FROM mail_message m JOIN mail_state t ON t.mail_id=m.mail_id AND t.player_id=? "
                                         + "WHERE t.read_ms>0 AND t.deleted_ms=0 AND "
                                                  + visible() + " ORDER BY m.mail_id LIMIT 1000",
                                          r -> u64(r, 1), U(player), U(player), U(player), U(player))
                                : ids;
            for (long id : new LinkedHashSet<>(targets)) {
                try {
                    var v = materialize(s, player, id, now);
                    if ((v.state.claim != 0 && v.state.claim != 1 && v.state.claim != 3) || (v.state.claim == 1 && v.mail.expire > now)) {
                        out.addProtectedMailIds(id);
                        continue;
                    }
                    s.update("UPDATE mail_state SET deleted_ms=?,updated_ms=? WHERE player_id=? AND mail_id=?", now, now, U(player), U(id));
                    out.addDeletedMailIds(id);
                } catch (Reject reject) {
                    if (reject.tip != NOT_FOUND && reject.tip != EXPIRED)
                        throw reject;
                }
            }
            return out.build();
        });
    }
    public MailActionResult claim(long player, long id, Deadline d) throws SQLException {
        require(player != 0 && id != 0, INVALID);
        ensure(d, player);
        long op = ids.next(d), now = clock.getAsLong();
        return sql.tx(d, s -> {
            lock(s, player);
            var v = materialize(s, player, id, now);
            var result = MailActionResult.newBuilder().setMailId(id).setClaimStateValue(v.state.claim).setAssetOpId(v.state.op);
            if (v.state.claim != 1)
                return result.build();
            require(v.mail.expire > now, EXPIRED);
            require(v.state.version != -1L, CONFLICT);
            s.update("INSERT IGNORE INTO mail_player_op_seq (player_id,stream,next_seq,updated_ms,epoch) VALUES (?,5,1,?,?)", U(player),
                    now, now);
            long[] seq = s.one("SELECT next_seq,epoch FROM mail_player_op_seq WHERE player_id=? AND stream=5 FOR UPDATE",
                    r -> new long[] {u64(r, 1), u64(r, 2)}, U(player));
            require(seq != null && seq[0] != 0 && seq[0] != -1L, CONFLICT);
            long[] pending = s.one("SELECT COUNT(*),COALESCE(MIN(seq),0) FROM mail_asset_op WHERE player_id=? AND stream=5 AND "
                                   + "stream_epoch=? AND status IN (1,4)",
                    r -> new long[] {r.getLong(1), u64(r, 2)}, U(player), U(seq[1]));
            require(pending[0] < 16 && (pending[0] == 0 || Long.compareUnsigned(seq[0] - pending[1], 512) <= 0), PENDING);
            // SYSTEM_CREDIT是mail唯一所有者；原附件→原操作的映射和状态推进同事务。
            byte[] bundle = MailGrants.bundle(attachments(v.mail), itemExists).toByteArray();
            s.update("INSERT INTO mail_asset_op "
                     + "(op_id,player_id,stream,seq,stream_epoch,mail_id,claim_version,status,payload,tx_type,attempts,next_attempt_ms,"
                     + "lease_until_ms,lease_token,last_outcome,last_reason,durable,reason_tip_id,created_ms,updated_ms,last_send_ms,"
                     + "resolved_by,resolve_reason) VALUES (?,?,5,?,?,?,?,1,?,2,0,?,0,0,0,0,0,0,?,?,0,'','')",
                    U(op), U(player), U(seq[0]), U(seq[1]), U(id), U(v.state.version + 1), bundle, now, now, now);
            s.update("UPDATE mail_player_op_seq SET next_seq=?,updated_ms=? WHERE player_id=? AND stream=5", U(seq[0] + 1), now, U(player));
            require(s.update("UPDATE mail_state SET "
                             + "claim_state=2,current_op_id=?,claim_version=?,reason_tip_id=0,read_ms=IF(read_ms=0,?,read_ms),updated_ms=? "
                             + "WHERE player_id=? AND mail_id=? AND claim_state=1 AND claim_version=?",
                            U(op), U(v.state.version + 1), now, now, U(player), U(id), U(v.state.version))
                            == 1,
                    CONFLICT);
            return result.setClaimStateValue(2).setAssetOpId(op).setErrorMessage(TipInfoMessage.newBuilder().setId(PENDING)).build();
        });
    }
    public record Operation(long id, long player, long mail, long version, long seq, long epoch, byte[] bundle, long token) {
        public AssetOpRequest request() throws SQLException {
            try {
                return AssetOpRequest.newBuilder()
                        .setPlayerId(player)
                        .setStream(AssetStream.ASSET_STREAM_SYSTEM_CREDIT)
                        .setSeq(seq)
                        .setStreamEpoch(epoch)
                        .setCorrelationId(id)
                        .setTxType(2)
                        .setBundle(AssetBundle.parseFrom(bundle))
                        .build();
            } catch (Exception e) {
                throw new SQLException("邮件操作载荷无法解码", e);
            }
        }
    }
    public List<Long> due(Deadline d, long now) throws SQLException {
        return sql.read(d,
                s
                -> s.list("SELECT op_id FROM mail_asset_op WHERE status=1 AND next_attempt_ms<=? AND lease_until_ms<? ORDER BY "
                          + "next_attempt_ms,op_id LIMIT 32",
                        r -> u64(r, 1), now, now));
    }
    public Operation lease(Deadline d, long id, long now, long token) throws SQLException {
        return sql.tx(d, s -> {
            if (s.update("UPDATE mail_asset_op SET lease_until_ms=?,lease_token=?,last_send_ms=? WHERE op_id=? AND status=1 AND "
                         + "lease_until_ms<?",
                        now + 10000, U(token), now, U(id), now)
                    != 1)
                return null;
            return s.one(
                    "SELECT op_id,player_id,mail_id,claim_version,seq,stream_epoch,payload,lease_token FROM mail_asset_op WHERE op_id=?",
                    r -> new Operation(u64(r, 1), u64(r, 2), u64(r, 3), u64(r, 4), u64(r, 5), u64(r, 6), r.getBytes(7), u64(r, 8)), U(id));
        });
    }
    /** 仓储再次按独立SQL账本分类；缺证据/窗外/旧纪元一律重排，不能凭在线durable终结。 */
    public void settle(Deadline d, Operation op, PersistedAssetLedger ledger) throws SQLException {
        var proof = ledger == null ? null : ledger.outcomeOf(5, op.epoch, op.seq);
        int outcome = proof == null || !proof.conclusive() ? 0 : proof.partial() ? 4 : proof.state() == AssetSeqState.APPLIED ? 1 : 2;
        int reason = proof == null ? 0 : proof.rejectionReason();
        long now = clock.getAsLong();
        sql.tx(d, s -> {
            lock(s, op.player);
            var state = s.one("SELECT current_op_id,claim_version,claim_state FROM mail_state WHERE player_id=? AND mail_id=? FOR UPDATE",
                    r -> new long[] {u64(r, 1), u64(r, 2), r.getLong(3)}, U(op.player), U(op.mail));
            var lease = s.one("SELECT status,lease_token FROM mail_asset_op WHERE op_id=? FOR UPDATE",
                    r -> new long[] {r.getLong(1), u64(r, 2)}, U(op.id));
            if (lease == null || lease[0] != 1 || lease[1] != op.token)
                return null;
            if (outcome == 0) {
                s.update("UPDATE mail_asset_op SET attempts=attempts+1,next_attempt_ms=?,lease_until_ms=0,updated_ms=? WHERE op_id=?",
                        now + 2000, now, U(op.id));
                return null;
            }
            require(state != null && state[0] == op.id && state[1] == op.version && state[2] == 2, CONFLICT);
            int claim = outcome == 1 ? 3 : outcome == 4 ? 4 : 1, status = outcome == 1 ? 2 : outcome == 4 ? 4 : 3;
            require(s.update("UPDATE mail_state SET claim_state=?,reason_tip_id=?,updated_ms=? WHERE player_id=? AND mail_id=? AND "
                             + "current_op_id=? AND claim_version=? AND claim_state=2",
                            claim, outcome == 4 ? MANUAL : reason, now, U(op.player), U(op.mail), U(op.id), U(op.version))
                            == 1,
                    CONFLICT);
            s.update("UPDATE mail_asset_op SET status=?,durable=1,last_outcome=?,last_reason=?,lease_until_ms=0,updated_ms=? WHERE op_id=?",
                    status, outcome == 4 ? 1 : outcome, reason, now, U(op.id));
            return null;
        });
    }
}
