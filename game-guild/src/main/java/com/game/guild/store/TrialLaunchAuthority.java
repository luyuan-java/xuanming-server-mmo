package com.game.guild.store;

import static com.game.discovery.sql.AuthorityJdbc.u64;

import com.game.api.proto.SessionContext;
import com.game.common.player.PlayerSessionAuthority;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.guild.*;
import com.game.proto.match.StartActivityBattleRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import java.io.*;
import java.security.*;
import java.sql.SQLException;
import java.util.*;

/** 历练私有同意与永久 launch 原件；所有变更由调用方同一事务持全员 P 后执行。 */
final class TrialLaunchAuthority {
    private TrialLaunchAuthority() {}

    private static boolean validIdentity(GuildTrialRoleIdentity identity) {
        return identity.getPlayerId() != 0 && !identity.getAccount().isEmpty()
                && identity.getAccount().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 256
                && identity.getOwnerRevision() > 0;
    }

    static byte[] bytes(Message message) throws SQLException {
        byte[] raw = new byte[message.getSerializedSize()];
        var output = CodedOutputStream.newInstance(raw);
        output.useDeterministicSerialization();
        try { message.writeTo(output); output.checkNoSpaceLeft(); return raw; }
        catch (IOException failure) { throw new SQLException("历练原件序列化失败", failure); }
    }
    static byte[] digest(byte[] raw) throws SQLException {
        try { return MessageDigest.getInstance("SHA-256").digest(raw); }
        catch (GeneralSecurityException failure) { throw new SQLException("历练原件摘要失败", failure); }
    }
    static GuildTrialRoleIdentity identity(SessionContext actor, long player) {
        if (actor == null || actor.getPlayerId() != player || player == 0 || actor.getAccount().isEmpty()
                || actor.getAccount().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 || actor.getOwnerRevision() <= 0)
            throw new SecurityException("历练缺少本人原会话同意");
        return GuildTrialRoleIdentity.newBuilder().setPlayerId(player).setAccount(actor.getAccount())
                .setOwnerRevision(actor.getOwnerRevision()).build();
    }
    static List<Long> ordered(Collection<Long> players) {
        var out = new ArrayList<>(new HashSet<>(players));
        if (out.contains(0L) || out.size() > 5) throw new SecurityException("历练角色名单非法");
        out.sort(Long::compareUnsigned);
        return out;
    }
    static void lockPlayers(AuthorityJdbc.Session sql, Collection<Long> players, SessionContext actor) throws SQLException {
        if (actor == null || !players.contains(actor.getPlayerId())) throw new SecurityException("历练缺少原会话主体");
        com.game.common.player.PlayerRouteAuthority.lock(sql.connection(),ordered(players),3);
        for (long player : ordered(players)) {
            if (player == actor.getPlayerId()) {
                PlayerSessionAuthority.lock(sql.connection(), player, actor.getAccount(), actor.getOwnerRevision(), 3);
            } else {
                Long listing = sql.one("SELECT trade_listing_id FROM player WHERE player_id=? FOR UPDATE", r -> r.getLong(1), u64(player));
                if (listing == null || listing != 0) throw new SecurityException("历练成员不存在或被冻结");
            }
        }
    }
    static void lockOwners(AuthorityJdbc.Session sql, GuildTrialLaunchOriginal original) throws SQLException {
        var identities = new HashMap<Long,GuildTrialRoleIdentity>();
        for (var identity : original.getRosterList()) identities.put(identity.getPlayerId(), identity);
        com.game.common.player.PlayerRouteAuthority.lock(sql.connection(),ordered(identities.keySet()),3);
        for (long player : ordered(identities.keySet())) {
            var identity = identities.get(player);
            PlayerSessionAuthority.lock(sql.connection(), player, identity.getAccount(), identity.getOwnerRevision(), 3);
        }
    }
    static Map<Long,GuildTrialRoleIdentity> consents(byte[] raw, long lobby) throws SQLException {
        if (raw == null || raw.length > 65536) throw new SQLException("历练同意原件非法");
        var out = new LinkedHashMap<Long,GuildTrialRoleIdentity>();
        try (var input = new ByteArrayInputStream(raw)) {
            GuildTrialLobbyConsent value;
            while ((value = GuildTrialLobbyConsent.parseDelimitedFrom(input)) != null) {
                var identity = value.getIdentity();
                if (value.getLobbyId() != lobby || !validIdentity(identity)
                        || out.putIfAbsent(identity.getPlayerId(), identity) != null || out.size() > 5)
                    throw new SQLException("历练同意原件冲突");
            }
        } catch (IOException failure) { throw new SQLException("历练同意原件损坏", failure); }
        return out;
    }
    static byte[] consentBytes(long lobby, Collection<GuildTrialRoleIdentity> identities) throws SQLException {
        try (var output = new ByteArrayOutputStream()) {
            for (var identity : identities)
                GuildTrialLobbyConsent.newBuilder().setLobbyId(lobby).setIdentity(identity).build().writeDelimitedTo(output);
            return output.toByteArray();
        } catch (IOException failure) { throw new SQLException("历练同意原件写入失败", failure); }
    }
    static GuildTrialLaunchRecord load(AuthorityJdbc.Session sql, long request, boolean lock) throws SQLException {
        return sql.one("SELECT request_id,battle_id,state,original_proto,request_digest,created_ms,updated_ms,cancel_receipt"
                + " FROM guild_trial_launch WHERE request_id=?" + (lock ? " FOR UPDATE" : ""), row ->
                GuildTrialLaunchRecord.newBuilder().setRequestId(u64(row,1)).setBattleId(u64(row,2)).setState(row.getInt(3))
                    .setOriginalProto(ByteString.copyFrom(row.getBytes(4))).setRequestDigest(ByteString.copyFrom(row.getBytes(5)))
                    .setCreatedMs(u64(row,6)).setUpdatedMs(u64(row,7)).setCancelReceipt(ByteString.copyFrom(row.getBytes(8))).build(),u64(request));
    }
    static GuildTrialLaunchOriginal original(GuildTrialLaunchRecord record) throws SQLException {
        if (record == null || record.getRequestId() == 0 || record.getState() < 1 || record.getState() > 3
                || record.getState() == 2 && record.getBattleId() == 0
                || record.getOriginalProto().isEmpty() || record.getOriginalProto().size() > 65536
                || !MessageDigest.isEqual(digest(record.getOriginalProto().toByteArray()), record.getRequestDigest().toByteArray()))
            throw new SQLException("历练永久开战原件缺失或摘要冲突");
        try {
            var original = GuildTrialLaunchOriginal.parseFrom(record.getOriginalProto());
            var seen = new HashSet<Long>();
            if (original.getRequestId() != record.getRequestId() || original.getGuildId() == 0 || original.getActivityId() == 0
                    || original.getPeriodKey() == 0 || original.getGuildPeriodKey() == 0 || original.getDungeonId() == 0
                    || original.getRosterCount() < 2 || original.getRosterCount() > 5 || original.getCreatedMs() <= 0
                    || original.getCreatedMs() != record.getCreatedMs())
                throw new SQLException("历练永久开战范围非法");
            long previous=0;
            for (var identity : original.getRosterList()) {
                if (!validIdentity(identity) || !seen.add(identity.getPlayerId())
                        || previous!=0&&Long.compareUnsigned(previous,identity.getPlayerId())>=0) throw new SQLException("历练永久原身份集合未规范化");
                previous=identity.getPlayerId();
            }
            if(!seen.contains(original.getInitiatorPlayerId()))throw new SQLException("历练原发起者不在本人授权集合");
            return original;
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) { throw new SQLException("历练永久原件损坏", failure); }
    }
    static StartActivityBattleRequest start(GuildTrialLaunchOriginal original) {
        // Start沿既有战斗接缝把发起者放首位；授权来自独立initiator字段和本人原身份，不来自roster下标。
        var players=new ArrayList<Long>();players.add(original.getInitiatorPlayerId());
        original.getRosterList().stream().map(GuildTrialRoleIdentity::getPlayerId).filter(player->player!=original.getInitiatorPlayerId()).sorted(Long::compareUnsigned).forEach(players::add);
        return StartActivityBattleRequest.newBuilder().setRequestId(original.getRequestId()).setBattleConfigId(original.getDungeonId())
            .addAllMemberPlayerIds(players)
            .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                .setGuildId(original.getGuildId()).setActivityId(original.getActivityId()).setPeriodKey(original.getPeriodKey())
                .setGuildPeriodKey(original.getGuildPeriodKey()).setInitiatorPlayerId(original.getInitiatorPlayerId())
                .setLaunchRequestId(original.getRequestId())).build();
    }
    static GuildTrialLaunchOriginal prepare(AuthorityJdbc.Session sql, StartActivityBattleRequest start,
            Map<Long,GuildTrialRoleIdentity> consents, long now) throws SQLException {
        var context = start.getActivityContext();
        var players=ordered(start.getMemberPlayerIdsList());
        if(players.size()!=start.getMemberPlayerIdsCount()||players.size()<2||!players.contains(context.getInitiatorPlayerId()))throw new SQLException("历练原开战缺少唯一授权集合或独立发起者");
        var builder = GuildTrialLaunchOriginal.newBuilder().setRequestId(start.getRequestId()).setGuildId(context.getGuildId())
            .setActivityId(context.getActivityId()).setPeriodKey(context.getPeriodKey()).setGuildPeriodKey(context.getGuildPeriodKey())
            .setInitiatorPlayerId(context.getInitiatorPlayerId()).setDungeonId(start.getBattleConfigId()).setCreatedMs(now);
        for (long player : players) {
            var identity = consents.get(player);
            if (identity == null) throw new SecurityException("历练缺少成员本人原同意");
            builder.addRoster(identity);
        }
        var saved = load(sql,start.getRequestId(),true);
        if (saved != null) builder.setCreatedMs(saved.getCreatedMs());
        var original = builder.build();
        lockOwners(sql,original); // P已在事务首按全员升序取得，此处核对各同意原件。
        if (saved != null) {
            if (saved.getState() == 3 || !original(saved).equals(original)) throw new SQLException("历练开战幂等原件变更");
            verifyProjection(sql,original,true);
            return original;
        }
        com.game.common.player.MatchEntryAuthority.requireFree(sql.connection(),players,"",0,3);
        byte[] raw = bytes(original);
        sql.update("INSERT INTO guild_trial_launch(request_id,battle_id,state,original_proto,request_digest,created_ms,updated_ms,cancel_receipt) VALUES(?,0,1,?,?,?,?,?)",
            u64(original.getRequestId()),raw,digest(raw),now,now,new byte[0]);
        for (var identity : original.getRosterList())
            sql.update("INSERT INTO guild_trial_launch_role(player_id,request_id,account,owner_revision,state) VALUES(?,?,?,?,1)",
                u64(identity.getPlayerId()),u64(original.getRequestId()),identity.getAccount(),u64(identity.getOwnerRevision()));
        return original;
    }
    static void verifyProjection(AuthorityJdbc.Session sql, GuildTrialLaunchOriginal original, boolean active) throws SQLException {
        for (long player : ordered(original.getRosterList().stream().map(GuildTrialRoleIdentity::getPlayerId).toList())) {
            var expected = original.getRosterList().stream().filter(v -> v.getPlayerId() == player).findFirst().orElseThrow();
            Object[] projection = sql.one("SELECT account,owner_revision,state FROM guild_trial_launch_role WHERE player_id=? AND request_id=? FOR UPDATE",
                row -> new Object[]{row.getString(1),u64(row,2),row.getInt(3)},u64(player),u64(original.getRequestId()));
            if (projection == null || !expected.getAccount().equals(projection[0]) || expected.getOwnerRevision() != (Long)projection[1]
                    || (Integer)projection[2] != (active ? 1 : 2)) throw new SQLException("历练角色投影原件丢失或冲突");
        }
    }
    static void update(AuthorityJdbc.Session sql, GuildTrialLaunchOriginal original, long battle, boolean terminal,
            byte[] receipt, long now) throws SQLException {
        var saved = load(sql,original.getRequestId(),true);
        if (!original(saved).equals(original) || saved.getBattleId() != 0 && saved.getBattleId() != battle)
            throw new SQLException("历练对局绑定原件冲突");
        if (saved.getState() == 3) {
            if (!terminal) throw new SQLException("历练已终局，不再恢复ACTIVE");
            verifyProjection(sql,original,false);
            return;
        }
        verifyProjection(sql,original,true);
        sql.update("UPDATE guild_trial_launch SET battle_id=?,state=?,updated_ms=?,cancel_receipt=? WHERE request_id=?",
            u64(battle),terminal ? 3 : 2,now,receipt,u64(original.getRequestId()));
        if (terminal) for (var identity : original.getRosterList())
            sql.update("UPDATE guild_trial_launch_role SET state=2 WHERE player_id=? AND request_id=? AND state=1",
                u64(identity.getPlayerId()),u64(original.getRequestId()));
    }
}
