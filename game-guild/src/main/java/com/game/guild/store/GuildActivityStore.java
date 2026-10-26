package com.game.guild.store;

import static com.game.discovery.sql.AuthorityJdbc.u64;

import com.game.api.proto.AssetBundle;
import com.game.common.deadline.Deadline;
import com.game.common.time.GameDay;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.guild.activity.ActivityRules;
import com.game.guild.activity.GuildTrialCancellation;
import com.game.api.proto.SessionContext;
import com.game.guild.rules.GuildTips;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.BattleActivityContext;
import com.game.proto.TransactionType;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActivityKind;
import com.game.proto.guild.*;
import com.game.proto.match.*;
import com.game.table.*;
import com.google.protobuf.ByteString;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** 活动SQL唯一权威。G/M→Q→O/C→进度→战斗/奖励；不修改匹配占用或玩家资产。 */
public final class GuildActivityStore {
    public static final class InvalidResult extends SQLException {
        public InvalidResult(String reason) {
            super(reason);
        }
    }
    /** 尚未证明完整终局：只能保留原 ACTIVE 重试，不能按确定性结算失败隔离销账。 */
    public static final class UnprovenResult extends SQLException {
        public UnprovenResult(String reason) {
            super(reason);
        }
    }
    public static final class Rejected extends RuntimeException {
        public final int tip;
        public final List<String> parameters;
        public Rejected(int tip, String... parameters) {
            super("帮会活动拒绝");
            this.tip = tip;
            this.parameters = List.of(parameters);
        }
    }
    public record Member(long guild, int level, int zone, long joined) {}
    public record Launch(long lobby, long guild, long token, StartActivityBattleRequest request) {}
    public record Battle(long id, long guild, byte[] context, long created) {}
    private record Progress(int count, boolean reached, boolean funded) {}
    private final AuthorityJdbc sql;
    private final AuthorityJdbc baseSql;
    private final SessionContext actor;
    private final ConfigTables tables;
    private final LongSupplier ids;
    private final GuildStore.ZoneFence fence;
    public GuildActivityStore(AuthorityJdbc sql, ConfigTables tables, LongSupplier ids) {
        this(sql, tables, ids, GuildStore.ZoneFence.OPEN);
    }
    public GuildActivityStore(AuthorityJdbc sql, ConfigTables tables, LongSupplier ids, GuildStore.ZoneFence fence) {
        this(sql,tables,ids,fence,null);
    }
    private GuildActivityStore(AuthorityJdbc baseSql, ConfigTables tables, LongSupplier ids, GuildStore.ZoneFence fence, SessionContext actor) {
        this.baseSql = baseSql;
        this.actor = actor;
        this.sql = actor == null ? baseSql : baseSql.forActor(actor.getPlayerId(),actor.getAccount(),actor.getOwnerRevision());
        this.tables = tables;
        this.ids = ids;
        this.fence = fence;
    }
    public GuildActivityStore forActor(com.game.api.proto.SessionContext actor) {
        TrialLaunchAuthority.identity(actor,actor.getPlayerId());
        return new GuildActivityStore(baseSql,tables,ids,fence,actor);
    }
    private void fence(int zone) {
        try {
            if (fence.merging(zone))
                throw new Rejected(GuildTips.ZONE_MERGING);
        } catch (Rejected rejected) {
            throw rejected;
        } catch (Exception unreadable) {
            throw new Rejected(GuildTips.ZONE_MERGING);
        }
    }
    private long id() {
        try {
            long id = ids.getAsLong();
            if (id == 0)
                throw new IllegalStateException("活动发号器返回0");
            return id;
        } catch (RuntimeException unavailable) {
            throw new Rejected(GuildTips.ID_GEN_UNAVAILABLE);
        }
    }
    @FunctionalInterface
    private interface MemberWork<T> {
        T apply(AuthorityJdbc.Session s, Member member) throws SQLException;
    }
    private <T> T memberTx(long player, Deadline d, MemberWork<T> work) throws SQLException {
        Long guild = sql.read(d, s -> s.one("SELECT guild_id FROM guild_member WHERE player_id=?", r -> u64(r, 1), u64(player)));
        if (guild == null)
            throw new Rejected(GuildTips.NOT_IN_GUILD);
        return sql.tx(d, s -> {
            Member m = lockMember(s, guild, player);
            return work.apply(s, m);
        });
    }
    private <T> T trialMemberTx(long player, Collection<Long> roster, Deadline d, MemberWork<T> work) throws SQLException {
        TrialLaunchAuthority.identity(actor,player);
        Long guild = baseSql.read(d,s -> s.one("SELECT guild_id FROM guild_member WHERE player_id=?",r -> u64(r,1),u64(player)));
        if (guild == null) throw new Rejected(GuildTips.NOT_IN_GUILD);
        return baseSql.tx(d,s -> {
            TrialLaunchAuthority.lockPlayers(s,roster,actor); // 全员P先于G，不能先用单actor绑定事务。
            return work.apply(s,lockMember(s,guild,player));
        });
    }
    private Member lockMember(AuthorityJdbc.Session s, long guild, long player) throws SQLException {
        int[] g =
                s.one("SELECT level,zone_id FROM guild WHERE guild_id=? FOR UPDATE", r -> new int[] {r.getInt(1), r.getInt(2)}, u64(guild));
        if (g == null)
            throw new Rejected(GuildTips.NOT_IN_GUILD);
        fence(g[1]);
        Long join = s.one("SELECT join_time_ms FROM guild_member FORCE INDEX(PRIMARY) WHERE guild_id=? AND player_id=? FOR UPDATE",
                r -> u64(r, 1), u64(guild), u64(player));
        if (join == null)
            throw new Rejected(GuildTips.NOT_IN_GUILD);
        return new Member(guild, g[0], g[1], join);
    }
    public Member member(long player, Deadline d) throws SQLException {
        Member m = sql.read(d,
                s
                -> s.one("SELECT m.guild_id,g.level,g.zone_id,m.join_time_ms FROM guild_member m JOIN guild g ON g.guild_id=m.guild_id "
                                + "WHERE m.player_id=?",
                        r -> new Member(u64(r, 1), r.getInt(2), r.getInt(3), u64(r, 4)), u64(player)));
        if (m == null)
            throw new Rejected(GuildTips.NOT_IN_GUILD);
        return m;
    }
    private GuildActivityTable row(int type, int activity, long now) {
        return ActivityRules.pickForWrite(tables.guildActivity().all(), type, activity, now)
                .orElseThrow(() -> new Rejected(GuildTips.ACTIVITY_NOT_OPEN));
    }
    private void eligible(Member m, GuildActivityTable row, long now) {
        if (m.level < row.getMinGuildLevel())
            throw new Rejected(GuildTips.ACTIVITY_LEVEL_TOO_LOW);
        int hours = tables.guildRule().find(1).orElseThrow().getActivityJoinMinHours();
        if (!ActivityRules.joinedLongEnough(m.joined, now, hours))
            throw new Rejected(GuildTips.ACTIVITY_JOIN_TOO_RECENT, Integer.toString(hours));
    }
    private int used(AuthorityJdbc.Session s, long player, int activity, int period) throws SQLException {
        Integer count =
                s.one("SELECT used_count FROM guild_daily_counter WHERE player_id=? AND counter_kind=3 AND ref_id=? AND period_key=?",
                        r -> r.getInt(1), u64(player), activity, period);
        return count == null ? 0 : count;
    }
    private Progress progress(AuthorityJdbc.Session s, long guild, GuildActivityTable row, int period) throws SQLException {
        Progress p = s.one("SELECT progress,threshold_reached,funds_granted FROM guild_activity_progress WHERE guild_id=? AND "
                        + "activity_id=? AND period_key=?",
                r -> new Progress(r.getInt(1), r.getBoolean(2), r.getBoolean(3)), u64(guild), row.getId(), period);
        return p == null ? new Progress(0, false, false) : p;
    }
    private void saveProgress(AuthorityJdbc.Session s, long guild, GuildActivityTable row, int period, Progress p) throws SQLException {
        s.update("INSERT INTO guild_activity_progress(guild_id,activity_id,period_key,progress,threshold_reached,funds_granted) "
                        + "VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                        + "progress=VALUES(progress),threshold_reached=VALUES(threshold_reached),funds_granted=VALUES(funds_granted)",
                u64(guild), row.getId(), period, p.count, p.reached, p.funded);
    }
    private GuildJdbc jdbc(AuthorityJdbc.Session s, Deadline d) throws SQLException {
        return new GuildJdbc(s.connection(), d, 3);
    }
    private void credit(AuthorityJdbc.Session s, Deadline d, long guild, long player, GuildActivityTable row, int day, long now)
            throws SQLException {
        var t = jdbc(s, d);
        // Q在仍有M锁时建出；后续离帮不删除它，奖励待办可安全单独锁Q继续入队。
        JdbcEconomyStore.ensureSeqRowTx(t, player, 2, now);
        t.one("SELECT player_id FROM guild_player_op_seq WHERE player_id=? AND stream=2 FOR UPDATE", r -> GuildJdbc.u64(r, 1), player);
        if (JdbcEconomyStore.upsertCounterWithLimit(t, player, 3, row.getId(), day, 1, row.getDailyLimit(), now))
            throw new Rejected(GuildTips.ACTIVITY_ALREADY_CLAIMED);
        if (row.getPersonalContribution() != 0) {
            int n = s.update("UPDATE guild_member FORCE INDEX(PRIMARY) SET "
                            + "contribution_total=contribution_total+?,contribution_balance=contribution_balance+? WHERE guild_id=? AND "
                            + "player_id=? AND contribution_total<=18446744073709551615-? AND contribution_balance<=18446744073709551615-?",
                    u64(row.getPersonalContribution()), u64(row.getPersonalContribution()), u64(guild), u64(player),
                    u64(row.getPersonalContribution()), u64(row.getPersonalContribution()));
            if (n != 1)
                throw new SQLException("活动帮贡溢出或成员丢失");
        }
        AssetBundle bundle = ActivityRules.buildRewardBundle(row.getRewardId(), key -> tables.reward().find(key).orElse(null));
        if (bundle.getItemsCount() > 0)
            s.update("INSERT INTO "
                            + "guild_activity_reward_owed(reward_id,player_id,guild_id,activity_id,period_key,payload,status,op_id,created_"
                              + "ms) "
                            + "VALUES(?,?,?,?,?,?,0,0,?)",
                    u64(id()), u64(player), u64(guild), row.getId(), day, bundle.toByteArray(), now);
    }
    private void funds(AuthorityJdbc.Session s, long guild, long amount) throws SQLException {
        if (amount != 0
                && s.update("UPDATE guild SET funds=funds+? WHERE guild_id=? AND funds<=18446744073709551615-?", u64(amount), u64(guild),
                           u64(amount))
                        != 1)
            throw new SQLException("活动资金溢出或帮会丢失");
    }
    public GuildActivityView participate(long player, long expectedGuild, int type, int activity, int observedOnline, long now, Deadline d)
            throws SQLException {
        GuildActivityTable row = row(type, activity, now);
        return memberTx(player, d, (s, m) -> {
            if (m.guild != expectedGuild)
                throw new Rejected(GuildTips.NOT_IN_GUILD);
            eligible(m, row, now);
            int period = ActivityRules.guildPeriodKey(row, now);
            Progress p = progress(s, m.guild, row, period);
            int threshold =
                    type == 2 ? ActivityRules.reunionThreshold(row, tables.guildRule().find(1).orElseThrow()) : row.getGuildThreshold();
            if (type == 2 && !p.reached && observedOnline < threshold)
                throw new Rejected(GuildTips.ACTIVITY_THRESHOLD_NOT_REACHED, Integer.toString(observedOnline), Integer.toString(threshold));
            credit(s, d, m.guild, player, row, GameDay.dayKey(now), now);
            int count = type == 1 ? Math.addExact(p.count, 1) : p.count;
            boolean reached = p.reached || type == 2 || count >= threshold, grant = reached && !p.funded && row.getGuildFunds() != 0;
            if (grant)
                funds(s, m.guild, row.getGuildFunds());
            saveProgress(s, m.guild, row, period, new Progress(count, reached, p.funded || grant));
            return viewsIn(s, player, now).stream().filter(v -> v.getActivityId() == activity).findFirst().orElseThrow();
        });
    }
    private GuildTrialLobbyView decode(byte[] bytes) throws SQLException {
        try {
            return GuildTrialLobbyView.parseFrom(bytes);
        } catch (Exception e) {
            throw new SQLException("历练房间损坏", e);
        }
    }
    private GuildTrialLobbyView lobby(AuthorityJdbc.Session s, long id, boolean lock) throws SQLException {
        return s.one("SELECT view_proto FROM guild_trial_lobby WHERE lobby_id=?" + (lock ? " FOR UPDATE" : ""),
                r -> decode(r.getBytes(1)), u64(id));
    }
    private void saveLobby(AuthorityJdbc.Session s, GuildTrialLobbyView view, long next, byte[] request) throws SQLException {
        s.update("UPDATE guild_trial_lobby SET state=?,view_proto=?,next_attempt_ms=?,request_proto=?,battle_id=? WHERE lobby_id=?",
                view.getStateValue(), view.toByteArray(), next, request, u64(view.getBattleId()), u64(view.getLobbyId()));
    }
    private void release(AuthorityJdbc.Session s, GuildTrialLobbyView view) throws SQLException {
        // 保留最后房间供断线补拉结束原因；仅PENDING/LAUNCHING阻止新邀请。
        for (long p : view.getMemberPlayerIdsList())
            s.update("UPDATE guild_activity_slot SET expires_ms=0 WHERE player_id=? AND lobby_id=?", u64(p), u64(view.getLobbyId()));
    }
    /** 已持原身份P/G锁并写入SQL终局后关闭同一展示投影；TTL不能赋予释放资格。 */
    private void closeTerminalLobby(AuthorityJdbc.Session s, GuildTrialLaunchOriginal original, long battle,
                                    GuildTrialCancellation.Verified cancellation) throws SQLException {
        var terminal = TrialLaunchAuthority.load(s, original.getRequestId(), true);
        if (terminal == null || terminal.getState() != 3 || terminal.getBattleId() != battle
                || !TrialLaunchAuthority.original(terminal).equals(original))
            throw new SQLException("历练大厅关闭缺少同一原launch的SQL终局");
        TrialLaunchAuthority.verifyProjection(s, original, false);
        var start = TrialLaunchAuthority.start(original);
        if (cancellation != null) {
            if (!cancellation.terminal() || !cancellation.original().equals(start)
                    || cancellation.response().getBattleId() != battle
                    || !Arrays.equals(terminal.getCancelReceipt().toByteArray(), cancellation.response().toByteArray()))
                throw new SecurityException("历练大厅关闭缺少原签名取消收据");
        } else {
            if (battle == 0) throw new SQLException("历练大厅关闭缺少原battle_id");
            Object[] ended = s.one("SELECT state,context_proto FROM guild_trial_battle WHERE battle_id=? FOR UPDATE",
                    r -> new Object[] {r.getInt(1), r.getBytes(2)}, u64(battle));
            if (ended == null || (Integer) ended[0] != 2 && (Integer) ended[0] != 4
                    || !Arrays.equals((byte[]) ended[1], start.getActivityContext().toByteArray()))
                throw new SQLException("历练大厅关闭缺少同一原battle的SQL终局");
        }
        Object[] projection = s.one("SELECT guild_id,activity_id,state,view_proto,battle_id"
                        + " FROM guild_trial_lobby WHERE lobby_id=? FOR UPDATE",
                r -> new Object[] {u64(r, 1), r.getInt(2), r.getInt(3), r.getBytes(4), u64(r, 5)},
                u64(original.getRequestId()));
        if (projection != null) {
            var view = decode((byte[]) projection[3]);
            var expected = original.getRosterList().stream().map(role -> role.getPlayerId())
                    .collect(java.util.stream.Collectors.toSet());
            var actual = new HashSet<>(view.getMemberPlayerIdsList());
            if ((Long) projection[0] != original.getGuildId() || (Integer) projection[1] != original.getActivityId()
                    || (Integer) projection[2] != view.getStateValue() || view.getStateValue() < 1 || view.getStateValue() > 4
                    || view.getLobbyId() != original.getRequestId() || view.getInitiatorPlayerId() != original.getInitiatorPlayerId()
                    || actual.size() != view.getMemberPlayerIdsCount() || !actual.equals(expected)
                    || (Long) projection[4] != view.getBattleId() || view.getBattleId() != 0 && view.getBattleId() != battle)
                throw new SQLException("历练终局大厅投影与永久原件冲突");
            var closed = view.toBuilder().setStateValue(4).setBattleId(battle);
            if (cancellation != null) closed.setEndTipId(GuildTips.TRIAL_SERVICE_BUSY);
            saveLobby(s, closed.build(), 0, start.toByteArray());
        }
        // 房间投影可清理；仅退休仍指向原request的slot，绝不覆盖此后建立的新房间。
        for (var identity : original.getRosterList()) {
            if (projection == null)
                s.update("UPDATE guild_activity_slot SET lobby_id=0,expires_ms=0 WHERE player_id=? AND lobby_id=?",
                        u64(identity.getPlayerId()), u64(original.getRequestId()));
            else
                s.update("UPDATE guild_activity_slot SET expires_ms=0 WHERE player_id=? AND lobby_id=?",
                        u64(identity.getPlayerId()), u64(original.getRequestId()));
        }
    }

    private GuildTrialLobbyView end(AuthorityJdbc.Session s, GuildTrialLobbyView view, int tip, String... parameters) throws SQLException {
        var ended =
                view.toBuilder().setStateValue(4).setEndTipId(tip).clearEndParameters().addAllEndParameters(List.of(parameters)).build();
        saveLobby(s, ended, 0, new byte[0]);
        release(s, view);
        return ended;
    }
    public GuildTrialLobbyView start(long player, StartGuildTrialRequest request, long now, Deadline d) throws SQLException {
        return start(player, member(player, d).guild, request, now, d);
    }
    public GuildTrialLobbyView start(long player, long expectedGuild, StartGuildTrialRequest request, long now, Deadline d)
            throws SQLException {
        var row = row(3, request.getActivityId(), now);
        var roster = new ArrayList<>(request.getMemberPlayerIdsList());
        if (roster.size() < row.getTeamSizeMin() || roster.size() > row.getTeamSizeMax() || new HashSet<>(roster).size() != roster.size()
                || roster.contains(0L) || !roster.remove(player))
            throw new Rejected(GuildTips.TRIAL_TEAM_INVALID, "invalid", "0");
        roster.addFirst(player);
        long lobbyId = id();
        var rule = tables.guildRule().find(1).orElseThrow();
        var consent = TrialLaunchAuthority.identity(actor,player);
        return trialMemberTx(player, roster, d, (s, m) -> {
            if (m.guild != expectedGuild)
                throw new Rejected(GuildTips.NOT_IN_GUILD);
            eligible(m, row, now);
            var sorted = new ArrayList<>(roster);
            sorted.sort(Long::compareUnsigned);
            for (long p : sorted) {
                Member member = lockMember(s, m.guild, p);
                eligible(member, row, now);
            }
            if (used(s, player, row.getId(), GameDay.dayKey(now)) >= row.getDailyLimit())
                throw new Rejected(GuildTips.ACTIVITY_ALREADY_CLAIMED);
            for (long p : sorted) {
                s.update("INSERT IGNORE INTO guild_activity_slot(player_id,lobby_id,expires_ms,cooldown_ms) VALUES(?,0,0,0)", u64(p));
                long[] slot = s.one("SELECT lobby_id,expires_ms,cooldown_ms FROM guild_activity_slot WHERE player_id=? FOR UPDATE",
                        r -> new long[] {u64(r, 1), u64(r, 2), u64(r, 3)}, u64(p));
                if (slot[0] != 0) {
                    var old = lobby(s, slot[0], false);
                    long[] oldScope = s.one("SELECT guild_id,activity_id FROM guild_trial_lobby WHERE lobby_id=?",
                            r -> new long[] {u64(r, 1), Integer.toUnsignedLong(r.getInt(2))}, u64(slot[0]));
                    if (old != null && old.getStateValue() <= 2) {
                        if (old.getStateValue() == 1 && old.getExpireAtMs() <= now)
                            end(s, old, GuildTips.TRIAL_INVITE_EXPIRED);
                        else if (oldScope != null && oldScope[0] == m.guild && oldScope[1] == Integer.toUnsignedLong(row.getId())
                                && p == player && old.getInitiatorPlayerId() == player && old.getMemberPlayerIdsList().equals(roster)
                                && old.getStateValue() == 1) {
                            var originals = TrialLaunchAuthority.consents(s.one("SELECT request_proto FROM guild_trial_lobby WHERE lobby_id=?",
                                    r -> r.getBytes(1),u64(old.getLobbyId())),old.getLobbyId());
                            if (!consent.equals(originals.get(player))) throw new SecurityException("历练发起人原同意变更");
                            return old;
                        }
                        else
                            throw new Rejected(GuildTips.TRIAL_TEAM_INVALID, "busy", Long.toUnsignedString(p));
                    }
                }
                if (p == player && slot[2] > now)
                    throw new Rejected(GuildTips.TRIAL_INVITE_COOLDOWN, Long.toString((slot[2] - now + 999) / 1000));
            }
            // 私有大厅从首次邀请就占完整名单；同P与Match/Team提交互斥，旧回执已在上方先返回。
            com.game.common.player.MatchEntryAuthority.requireFree(s.connection(),roster,"",0,3);
            var view = GuildTrialLobbyView.newBuilder()
                               .setLobbyId(lobbyId)
                               .setInitiatorPlayerId(player)
                               .addAllMemberPlayerIds(roster)
                               .addAcceptedPlayerIds(player)
                               .setStateValue(1)
                               .setExpireAtMs(now + rule.getTrialInviteTtlSeconds() * 1000L)
                               .build();
            s.update("INSERT INTO "
                            + "guild_trial_lobby(lobby_id,guild_id,activity_id,state,view_proto,request_proto,next_attempt_ms,lease_token,"
                              + "lease_"
                            + "until_ms,created_ms,battle_id) VALUES(?,?,?,1,?,?,0,0,0,?,0)",
                    u64(lobbyId), u64(m.guild), row.getId(), view.toByteArray(),
                    TrialLaunchAuthority.consentBytes(lobbyId,List.of(consent)), now);
            for (long p : sorted)
                s.update("UPDATE guild_activity_slot SET lobby_id=?,expires_ms=?,cooldown_ms=IF(player_id=?, ?,cooldown_ms) WHERE "
                                + "player_id=?",
                        u64(lobbyId), view.getExpireAtMs(), u64(player), now + rule.getTrialInviteCooldownSeconds() * 1000L, u64(p));
            return view;
        });
    }
    public GuildTrialLobbyView respond(long player, RespondGuildTrialInviteRequest request, long now, Deadline d) throws SQLException {
        var expected = baseSql.read(d,s -> lobby(s,request.getLobbyId(),false));
        if (expected == null || !expected.getMemberPlayerIdsList().contains(player)) throw new Rejected(GuildTips.TRIAL_INVITE_EXPIRED);
        return trialMemberTx(player, expected.getMemberPlayerIdsList(), d, (s, m) -> {
            // G锁串行本帮邀请/退出；读名单后按无符号主键顺序锁所有成员，再锁房间。
            var peek = lobby(s, request.getLobbyId(), false);
            Long guild = s.one("SELECT guild_id FROM guild_trial_lobby WHERE lobby_id=?", r -> u64(r, 1), u64(request.getLobbyId()));
            if (peek == null || guild == null || guild != m.guild || !peek.getMemberPlayerIdsList().contains(player))
                throw new Rejected(GuildTips.TRIAL_INVITE_EXPIRED);
            if (!peek.getMemberPlayerIdsList().equals(expected.getMemberPlayerIdsList())) throw new SQLException("历练房间名单已变更");
            var sorted = new ArrayList<>(peek.getMemberPlayerIdsList());
            sorted.sort(Long::compareUnsigned);
            GuildActivityTable row = tables.guildActivity()
                                             .find(s.one("SELECT activity_id FROM guild_trial_lobby WHERE lobby_id=?",
                                                     r -> r.getInt(1), u64(request.getLobbyId())))
                                             .orElseThrow();
            for (long p : sorted) {
                Member member = lockMember(s, m.guild, p);
                if (request.getAccept())
                    eligible(member, row, now);
            }
            var view = lobby(s, request.getLobbyId(), true);
            if (view.getStateValue() != 1) {
                if (view.getAcceptedPlayerIdsList().contains(player)) {
                    var record = TrialLaunchAuthority.load(s,view.getLobbyId(),false);
                    if (record == null && view.getStateValue() == 4) return view;
                    var launchOriginal = TrialLaunchAuthority.original(record);
                    if (!launchOriginal.getRosterList().contains(TrialLaunchAuthority.identity(actor,player)))
                        throw new SecurityException("历练已提交原同意不符");
                    return view;
                }
                throw new Rejected(GuildTips.TRIAL_INVITE_EXPIRED);
            }
            if (view.getExpireAtMs() <= now)
                return end(s, view, GuildTips.TRIAL_INVITE_EXPIRED);
            if (!request.getAccept())
                return end(s, view, GuildTips.TRIAL_INVITE_DECLINED, Long.toUnsignedString(player));
            row(3, row.getId(), now);
            byte[] pending = s.one("SELECT request_proto FROM guild_trial_lobby WHERE lobby_id=?", r -> r.getBytes(1),u64(view.getLobbyId()));
            var consents = TrialLaunchAuthority.consents(pending,view.getLobbyId());
            var consent = TrialLaunchAuthority.identity(actor,player);
            var oldConsent = consents.putIfAbsent(player,consent);
            if (oldConsent != null && !oldConsent.equals(consent)) throw new SecurityException("历练本人原同意不可替换");
            var next = view.toBuilder();
            if (!view.getAcceptedPlayerIdsList().contains(player))
                next.addAcceptedPlayerIds(player);
            if (next.getAcceptedPlayerIdsCount() == next.getMemberPlayerIdsCount()) {
                if (used(s, view.getInitiatorPlayerId(), row.getId(), GameDay.dayKey(now)) >= row.getDailyLimit())
                    return end(s, view, GuildTips.ACTIVITY_ALREADY_CLAIMED);
                var context = BattleActivityContext.newBuilder()
                                      .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                                      .setGuildId(m.guild)
                                      .setActivityId(row.getId())
                                      .setPeriodKey(GameDay.dayKey(now))
                                      .setGuildPeriodKey(ActivityRules.guildPeriodKey(row, now))
                                      .setInitiatorPlayerId(view.getInitiatorPlayerId())
                                      .setLaunchRequestId(view.getLobbyId())
                                      .build();
                var start = StartActivityBattleRequest.newBuilder()
                                    .setRequestId(view.getLobbyId())
                                    .setBattleConfigId(row.getDungeonId())
                                    .addAllMemberPlayerIds(view.getMemberPlayerIdsList())
                                    .setActivityContext(context)
                                    .build();
                var original = TrialLaunchAuthority.prepare(s,start,consents,now);
                if (!TrialLaunchAuthority.start(original).equals(start)) throw new SQLException("历练开战请求与同意原件不符");
                next.setStateValue(2);
                saveLobby(s, next.build(), now, start.toByteArray());
                for (long p : sorted)
                    s.update("UPDATE guild_activity_slot SET expires_ms=? WHERE player_id=? AND lobby_id=?", Long.MAX_VALUE, u64(p),
                            u64(view.getLobbyId()));
            } else
                saveLobby(s, next.build(), 0, TrialLaunchAuthority.consentBytes(view.getLobbyId(),consents.values()));
            return next.build();
        });
    }
    public List<GuildActivityView> views(long player, long now, Deadline d) throws SQLException {
        return sql.snapshot(d, s -> viewsIn(s, player, now));
    }
    private List<GuildActivityView> viewsIn(AuthorityJdbc.Session s, long player, long now) throws SQLException {
        Member m = s.one("SELECT m.guild_id,g.level,g.zone_id,m.join_time_ms FROM guild_member m JOIN guild g ON g.guild_id=m.guild_id "
                        + "WHERE m.player_id=?",
                r -> new Member(u64(r, 1), r.getInt(2), r.getInt(3), u64(r, 4)), u64(player));
        if (m == null)
            throw new Rejected(GuildTips.NOT_IN_GUILD);
        var out = new ArrayList<GuildActivityView>();
        for (var row : ActivityRules.selectVisible(tables.guildActivity().all(), now)) {
            Progress p = progress(s, m.guild, row, ActivityRules.guildPeriodKey(row, now));
            int used = used(s, player, row.getId(), GameDay.dayKey(now));
            var input = new ActivityRules.ViewInput(now, tables.guildRule().find(1).orElseThrow(), m.level, m.joined, used,
                    new ActivityRules.Progress(p.count, p.reached ? now : 0, p.funded), 0, 0, 0, 0, 0, null, false);
            var view = ActivityRules.buildView(row, input, key -> tables.reward().find(key).orElse(null)).toBuilder();
            view.setMyPendingRewardCount(
                    (int) s.count("SELECT COUNT(*) FROM guild_activity_reward_owed WHERE player_id=? AND activity_id=? AND status=0",
                            u64(player), row.getId())
                    + (int) s.count("SELECT COUNT(*) FROM guild_asset_op WHERE player_id=? AND kind=3 AND ref_id=? AND status=1",
                            u64(player), row.getId()));
            Integer pendingReason = s.one("SELECT last_reason FROM guild_asset_op WHERE player_id=? AND stream=2 AND kind=3 "
                            + "AND ref_id=? AND status=1 ORDER BY stream_epoch,seq LIMIT 1",
                    r -> r.getInt(1), u64(player), row.getId());
            if (pendingReason != null)
                view.setMyPendingReasonTipId(pendingReason);
            Integer rejectedReason = s.one("SELECT reason_tip_id FROM guild_asset_op WHERE player_id=? AND stream=2 AND kind=3 "
                            + "AND ref_id=? AND status=? AND next_attempt_ms>=? ORDER BY next_attempt_ms DESC,op_id DESC LIMIT 1",
                    r
                    -> r.getInt(1),
                    u64(player), row.getId(), GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED_VALUE,
                    Math.max(0, now - Duration.ofHours(24).toMillis()));
            if (rejectedReason != null)
                view.setMyLastRewardRejectTipId(rejectedReason);
            if (row.getType() == 3) {
                Long lid = s.one("SELECT a.lobby_id FROM guild_activity_slot a JOIN guild_trial_lobby l ON l.lobby_id=a.lobby_id "
                                + "WHERE a.player_id=? AND l.guild_id=? AND l.activity_id=?",
                        r -> u64(r, 1), u64(player), u64(m.guild), row.getId());
                if (lid != null && lid != 0) {
                    var lb = lobby(s, lid, false);
                    if (lb != null) {
                        if (lb.getStateValue() == 1 && lb.getExpireAtMs() <= now)
                            lb = lb.toBuilder().setStateValue(4).setEndTipId(GuildTips.TRIAL_INVITE_EXPIRED).build();
                        view.setTrialLobby(lb);
                        if (lb.getBattleId() != 0
                                && s.count("SELECT COUNT(*) FROM guild_trial_battle WHERE battle_id=? AND state=1", u64(lb.getBattleId()))
                                        == 1)
                            view.setMyTrialBattleId(lb.getBattleId());
                    }
                }
            }
            out.add(view.build());
        }
        return out;
    }
    public List<Long> members(long guild, Deadline d) throws SQLException {
        return sql.read(
                d, s -> s.list("SELECT player_id FROM guild_member WHERE guild_id=? ORDER BY player_id", r -> u64(r, 1), u64(guild)));
    }
    public List<Long> qualifiedMembers(long guild, long now, Deadline d) throws SQLException {
        int hours = tables.guildRule().find(1).orElseThrow().getActivityJoinMinHours();
        return sql
                .read(d,
                        s
                        -> s.list("SELECT player_id,join_time_ms FROM guild_member WHERE guild_id=? ORDER BY player_id",
                                r -> new long[] {u64(r, 1), u64(r, 2)}, u64(guild)))
                .stream()
                .filter(r -> ActivityRules.joinedLongEnough(r[1], now, hours))
                .map(r -> r[0])
                .toList();
    }
    public List<Long> launches(Deadline d, long now) throws SQLException {
        return baseSql.read(d,
                s
                -> s.list("SELECT t.request_id FROM guild_trial_launch t LEFT JOIN guild_trial_lobby l ON l.lobby_id=t.request_id "
                                + "WHERE t.state=1 AND (l.lobby_id IS NULL OR (l.state=2 AND l.next_attempt_ms<=? AND l.lease_until_ms<=?)) ORDER BY "
                                + "t.updated_ms,t.request_id LIMIT 32",
                        r -> u64(r, 1), now, now));
    }
    public Launch leaseLaunch(long lobby, long now, Deadline d) throws SQLException {
        var record = baseSql.read(d,s -> TrialLaunchAuthority.load(s,lobby,false));
        if (record == null || record.getState() != 1) return null;
        var original = TrialLaunchAuthority.original(record);
        long token = id();
        return baseSql.tx(d, s -> {
            TrialLaunchAuthority.lockOwners(s,original);
            s.one("SELECT guild_id FROM guild WHERE guild_id=? FOR UPDATE",r -> u64(r,1),u64(original.getGuildId()));
            var saved = TrialLaunchAuthority.load(s,lobby,true);
            if (saved == null || saved.getState() != 1 || !original.equals(TrialLaunchAuthority.original(saved))) return null;
            // 房间只是客户端投影；丢失时只凭永久原件恢复，不从当前账号、成员或配置补造同意。
            var restored = GuildTrialLobbyView.newBuilder().setLobbyId(lobby).setInitiatorPlayerId(original.getInitiatorPlayerId())
                    .addAllMemberPlayerIds(TrialLaunchAuthority.start(original).getMemberPlayerIdsList())
                    .addAllAcceptedPlayerIds(TrialLaunchAuthority.start(original).getMemberPlayerIdsList()).setStateValue(2)
                    .setExpireAtMs(original.getCreatedMs()).build();
            s.update("INSERT IGNORE INTO guild_trial_lobby(lobby_id,guild_id,activity_id,state,view_proto,request_proto,next_attempt_ms,lease_token,lease_until_ms,created_ms,battle_id) VALUES(?,?,?,2,?,?,0,0,0,?,0)",
                    u64(lobby),u64(original.getGuildId()),original.getActivityId(),restored.toByteArray(),
                    TrialLaunchAuthority.start(original).toByteArray(),u64(original.getCreatedMs()));
            if (s.update("UPDATE guild_trial_lobby SET lease_token=?,lease_until_ms=? WHERE lobby_id=? AND state=2 AND next_attempt_ms<=? "
                                + "AND lease_until_ms<=?",
                        u64(token), now + 10000, u64(lobby), now, now)
                    != 1)
                return null;
            s.update("UPDATE guild_trial_launch SET updated_ms=? WHERE request_id=? AND state=1",now,u64(lobby));
            return s.one("SELECT guild_id,request_proto FROM guild_trial_lobby WHERE lobby_id=?", r -> {
                try {
                    var request = StartActivityBattleRequest.parseFrom(r.getBytes(2));
                    if (u64(r,1) != original.getGuildId() || !request.equals(TrialLaunchAuthority.start(original)))
                        throw new SQLException("历练重放请求与永久原件不符");
                    return new Launch(lobby, u64(r, 1), token, request);
                } catch (Exception e) {
                    throw new SQLException("开战意图损坏", e);
                }
            }, u64(lobby));
        });
    }
    private GuildTrialLaunchOriginal launchOriginal(long request, Deadline d) throws SQLException {
        return baseSql.read(d,s -> TrialLaunchAuthority.original(TrialLaunchAuthority.load(s,request,false)));
    }
    private void bindBattle(AuthorityJdbc.Session s, GuildTrialLaunchOriginal original, long battle, long now) throws SQLException {
        if (battle == 0) throw new SQLException("历练成功缺battle_id");
        byte[] context = TrialLaunchAuthority.start(original).getActivityContext().toByteArray();
        s.update("INSERT IGNORE INTO guild_trial_battle(battle_id,guild_id,activity_id,context_proto,state,created_ms,result_hash,result_proto) VALUES(?,?,?,?,1,?,?,?)",
                u64(battle),u64(original.getGuildId()),original.getActivityId(),context,now,new byte[0],new byte[0]);
        byte[] stored = s.one("SELECT context_proto FROM guild_trial_battle WHERE battle_id=? FOR UPDATE",r -> r.getBytes(1),u64(battle));
        if (!Arrays.equals(stored,context)) throw new SQLException("历练对局上下文冲突");
        TrialLaunchAuthority.update(s,original,battle,false,new byte[0],now);
        var view = lobby(s,original.getRequestId(),true);
        if (view != null) {
            saveLobby(s,view.toBuilder().setStateValue(3).setBattleId(battle).build(),0,TrialLaunchAuthority.start(original).toByteArray());
            release(s,view);
        }
    }
    public void completeLaunch(Launch launch, StartActivityBattleResponse response, long now, Deadline d) throws SQLException {
        var original = launchOriginal(launch.lobby,d);
        if (!launch.request.equals(TrialLaunchAuthority.start(original))) throw new SQLException("历练回执原件不符");
        baseSql.tx(d,s -> {
            TrialLaunchAuthority.lockOwners(s,original);
            s.one("SELECT guild_id FROM guild WHERE guild_id=? FOR UPDATE",r -> u64(r,1),u64(original.getGuildId()));
            var record = TrialLaunchAuthority.load(s,launch.lobby,true);
            if (record == null || record.getState() == 3) return null;
            Long token = s.one("SELECT lease_token FROM guild_trial_lobby WHERE lobby_id=? FOR UPDATE",r -> u64(r,1),u64(launch.lobby));
            if (token == null || token != launch.token) return null;
            if (response == null || response.getRejectValue() != 0 || response.getBattleId() == 0) {
                // 普通拒答和未签formation_state都不证明安全；保留PREPARED/ACTIVE等签名Cancel。
                s.update("UPDATE guild_trial_lobby SET next_attempt_ms=?,lease_until_ms=0 WHERE lobby_id=?",now+1000,u64(launch.lobby));
                s.update("UPDATE guild_trial_launch SET updated_ms=? WHERE request_id=?",now,u64(launch.lobby));
                return null;
            }
            bindBattle(s,original,response.getBattleId(),now);
            return null;
        });
    }
    public void completeCancellation(Launch launch, GuildTrialCancellation.Verified proof, long now, Deadline d) throws SQLException {
        var original = launchOriginal(launch.lobby,d);
        if (proof == null || !proof.original().equals(TrialLaunchAuthority.start(original)) || !launch.request.equals(proof.original()))
            throw new SecurityException("历练取消证明未绑定永久原请求");
        baseSql.tx(d,s -> {
            TrialLaunchAuthority.lockOwners(s,original);
            s.one("SELECT guild_id FROM guild WHERE guild_id=? FOR UPDATE",r -> u64(r,1),u64(original.getGuildId()));
            var record = TrialLaunchAuthority.load(s,launch.lobby,true);
            if (record == null || !original.equals(TrialLaunchAuthority.original(record))) throw new SQLException("历练取消原件冲突");
            if (record.getState() == 3) return null;
            var reply = proof.response();
            if (!proof.terminal()) {
                if (reply.getFormationState() == 5)
                    throw new SQLException("历练取消回执与COMPLETE终局冲突，等待原战报结算");
                if (reply.getBattleId() != 0) bindBattle(s,original,reply.getBattleId(),now);
                return null;
            }
            TrialLaunchAuthority.update(s,original,reply.getBattleId(),true,reply.toByteArray(),now);
            if (reply.getBattleId() != 0)
                s.update("UPDATE guild_trial_battle SET state=4 WHERE battle_id=? AND state IN(1,3)",u64(reply.getBattleId()));
            closeTerminalLobby(s, original, reply.getBattleId(), proof);
            return null;
        });
    }
    public void deferLaunch(long request, long now, Deadline d) throws SQLException {
        baseSql.tx(d,s -> {
            s.update("UPDATE guild_trial_launch SET updated_ms=? WHERE request_id=? AND state=1",now,u64(request));
            s.update("UPDATE guild_trial_lobby SET next_attempt_ms=?,lease_until_ms=0 WHERE lobby_id=? AND state=2",now+1000,u64(request));
            return null;
        });
    }
    public Launch battleLaunch(long battle, Deadline d) throws SQLException {
        byte[] raw = baseSql.read(d,s -> s.one("SELECT context_proto FROM guild_trial_battle WHERE battle_id=?",r -> r.getBytes(1),u64(battle)));
        if (raw == null) throw new SQLException("历练巡检对局缺失");
        try {
            var context = BattleActivityContext.parseFrom(raw);
            var original = launchOriginal(context.getLaunchRequestId(),d);
            if (!TrialLaunchAuthority.start(original).getActivityContext().equals(context)) throw new SQLException("历练巡检原件不符");
            return new Launch(original.getRequestId(),original.getGuildId(),0,TrialLaunchAuthority.start(original));
        } catch (com.google.protobuf.InvalidProtocolBufferException bad) { throw new SQLException("历练巡检原件损坏",bad); }
    }
    public List<Battle> unsettled(Deadline d, long now) throws SQLException {
        return sql.read(d,
                s
                -> s.list("SELECT battle_id,guild_id,context_proto,created_ms FROM guild_trial_battle WHERE state IN(1,3) AND "
                                + "next_inspect_ms<=? ORDER BY next_inspect_ms,battle_id LIMIT 100",
                        r -> new Battle(u64(r, 1), u64(r, 2), r.getBytes(3), u64(r, 4)), now));
    }
    public void settle(BattleResultEvent event, long now, Deadline d) throws SQLException {
        if (event.getActivityContext().getKindValue() != 1)
            return;
        var context = event.getActivityContext();
        var original = terminalOriginal(event, d);
        Set<Long> team = terminalRoster(TrialLaunchAuthority.start(original), event);
        byte[] raw = event.toByteArray();
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (Exception e) {
            throw new SQLException(e);
        }
        Object[] settled = sql.read(d,
                s
                -> s.one("SELECT context_proto,result_hash,state FROM guild_trial_battle WHERE battle_id=? AND state IN(2,4)",
                        r -> new Object[] {r.getBytes(1), r.getBytes(2),r.getInt(3)}, u64(event.getBattleId())));
        if (settled != null) {
            if ((Integer)settled[2] == 4) throw new InvalidResult("历练已永久隔离，重复结果只保留审计，不重新发奖");
            if (!Arrays.equals((byte[])settled[0], context.toByteArray()) || !Arrays.equals((byte[])settled[1], digest))
                throw new InvalidResult("已结算历练的重复结果变更");
            return;
        }
        baseSql.tx(d, s -> {
            TrialLaunchAuthority.lockOwners(s,original);
            Long guild = s.one("SELECT guild_id FROM guild WHERE guild_id=? FOR UPDATE", r -> u64(r, 1), u64(context.getGuildId()));
            if (guild != null)
                fence(s.one("SELECT zone_id FROM guild WHERE guild_id=?", r -> r.getInt(1), u64(guild)));
            var launch = TrialLaunchAuthority.load(s,original.getRequestId(),true);
            if (launch == null || !TrialLaunchAuthority.original(launch).equals(original) || launch.getBattleId() != event.getBattleId())
                throw new SQLException("历练战报缺少原开战对局绑定");
            if (launch.getState() == 3) {
                var finished = s.one("SELECT result_hash FROM guild_trial_battle WHERE battle_id=? AND state=2",r -> r.getBytes(1),u64(event.getBattleId()));
                if (finished != null && Arrays.equals(finished,digest)) return null;
                throw new InvalidResult("历练原件已经终局，拒绝重新发奖");
            }
            TrialLaunchAuthority.verifyProjection(s,original,true);
            byte[][] before = s.one("SELECT context_proto,result_hash FROM guild_trial_battle WHERE battle_id=?",
                    r -> new byte[][] {r.getBytes(1), r.getBytes(2)}, u64(event.getBattleId()));
            if (before == null)
                throw new SQLException("历练开战回执尚未持久确认，保留战报等待原request_id恢复");
            if (!Arrays.equals(before[0], context.toByteArray()))
                throw new InvalidResult("历练结果跨上下文");
            if (before[1].length > 0) {
                if (!Arrays.equals(before[1], digest))
                    throw new InvalidResult("历练同局结果变更");
                return null;
            }
            if (Integer.compareUnsigned(
                        context.getPeriodKey(), GameDay.dayKey(now - Duration.ofDays(8).toMillis() + Duration.ofHours(1).toMillis()))
                    <= 0)
                throw new InvalidResult("历练战报超过安全计数保留窗口，需人工处理");
            // RPC未知时后台重投原request_id取回同battleID，确认后才允许结果关联。

            var row = tables.guildActivity().find(context.getActivityId()).orElse(null);
            if (guild != null && row != null && row.getType() == 3 && event.getOutcomeValue() == 1) {
                var players = new ArrayList<>(team);
                players.removeAll(event.getFledPlayerIdsList());
                players.remove(0L);
                players.sort(Long::compareUnsigned);
                var qualified = new ArrayList<Long>();
                for (long player : players) {
                    Long joined =
                            s.one("SELECT join_time_ms FROM guild_member FORCE INDEX(PRIMARY) WHERE guild_id=? AND player_id=? FOR UPDATE",
                                    r -> u64(r, 1), u64(guild), u64(player));
                    if (joined != null)
                        qualified.add(player);
                }
                if (!qualified.isEmpty()) {
                    // 只有完整终局已绑定原件后，确定性的奖励配置错误才允许走POISON；事务回滚全部奖励副作用。
                    try {
                        ActivityRules.buildRewardBundle(row.getRewardId(), key -> tables.reward().find(key).orElse(null));
                    } catch (IllegalStateException invalidConfig) {
                        throw new InvalidResult("历练奖励配置不可结算：" + invalidConfig.getMessage());
                    }
                }
                var t = jdbc(s, d);
                for (long player : qualified) {
                    JdbcEconomyStore.ensureSeqRowTx(t, player, 2, now);
                    t.one("SELECT player_id FROM guild_player_op_seq WHERE player_id=? AND stream=2 FOR UPDATE",
                            r -> GuildJdbc.u64(r, 1), player);
                }
                boolean rewarded = false;
                for (long player : qualified) {
                    if (used(s, player, row.getId(), context.getPeriodKey()) >= row.getDailyLimit())
                        continue;
                    credit(s, d, guild, player, row, context.getPeriodKey(), now);
                    rewarded = true;
                }
                var p = progress(s, guild, row, context.getGuildPeriodKey());
                if (rewarded && row.getGuildFunds() != 0 && p.count < row.getGuildThreshold()) {
                    funds(s, guild, row.getGuildFunds());
                    saveProgress(s, guild, row, context.getGuildPeriodKey(), new Progress(p.count + 1, false, false));
                }
            }
            s.update("INSERT INTO "
                            + "guild_trial_battle(battle_id,guild_id,activity_id,context_proto,state,created_ms,result_hash,result_proto) "
                            + "VALUES(?,?,?,?,2,?,?,?) ON DUPLICATE KEY UPDATE "
                            + "state=2,result_hash=VALUES(result_hash),result_proto=VALUES(result_proto)",
                    u64(event.getBattleId()), u64(context.getGuildId()), context.getActivityId(), context.toByteArray(), now, digest, raw);
            TrialLaunchAuthority.update(s,original,event.getBattleId(),true,new byte[0],now);
            closeTerminalLobby(s, original, event.getBattleId(), null);
            return null;
        });
    }
    private GuildTrialLaunchOriginal terminalOriginal(BattleResultEvent event, Deadline d) throws SQLException {
        if (event.getBattleId() == 0 || event.getFinishedAtMs() == 0 || event.getOutcomeValue() < 1 || event.getOutcomeValue() > 3
                || event.getMatchMode() != MatchMode.MATCH_MODE_PVE_TEAM_VALUE
                || event.getActivityContext().getKindValue() != 1 || event.getActivityContext().getLaunchRequestId() == 0)
            throw new UnprovenResult("历练战报缺少完整终局，保留原ACTIVE");
        var record = baseSql.read(d, s -> TrialLaunchAuthority.load(s, event.getActivityContext().getLaunchRequestId(), false));
        var original = TrialLaunchAuthority.original(record);
        if (record.getBattleId() != event.getBattleId())
            throw new UnprovenResult("历练战报缺少原开战对局绑定，保留原ACTIVE");
        terminalRoster(TrialLaunchAuthority.start(original), event);
        return original;
    }
    /** 纯终局核验；名单取永久原件，死亡成员仍可得奖，逃跑与死亡仅各自要求是原名单的无重复子集。 */
    static Set<Long> terminalRoster(StartActivityBattleRequest start, BattleResultEvent event) throws UnprovenResult {
        if (event.getBattleId() == 0 || event.getFinishedAtMs() == 0 || event.getOutcomeValue() < 1 || event.getOutcomeValue() > 3
                || event.getMatchMode() != MatchMode.MATCH_MODE_PVE_TEAM_VALUE || event.getActivityContext().getKindValue() != 1
                || !start.getActivityContext().equals(event.getActivityContext()) || start.getBattleConfigId() != event.getBattleConfigId()
                || event.getOutcomeValue() == 1 && event.getWinnerTeamIndex() != 0)
            throw new UnprovenResult("历练战报终局、副本或上下文不属于永久开战原件");
        Set<Long> roster = new HashSet<>(start.getMemberPlayerIdsList());
        Set<Long> team = new HashSet<>();
        int teamZeroCount = 0;
        for (var t : event.getTeamsList())
            if (t.getTeamIndex() == 0) {
                teamZeroCount++;
                for (long player : t.getPlayerIdsList())
                    if (!roster.contains(player) || player == 0 || !team.add(player))
                        throw new UnprovenResult("历练战报己方名单越界或重复");
            }
        if (teamZeroCount != 1 || !roster.equals(team))
            throw new UnprovenResult("历练战报缺少唯一完整己方名单");
        for (var subset : List.of(event.getFledPlayerIdsList(), event.getDeadPlayerIdsList())) {
            Set<Long> seen = new HashSet<>();
            for (long player : subset)
                if (!roster.contains(player) || !seen.add(player))
                    throw new UnprovenResult("历练战报逃跑或死亡名单越界或重复");
        }
        return Set.copyOf(team);
    }
    public int enqueueRewards(long now, Deadline d) throws SQLException {
        var due = sql.read(d,
                s
                -> s.list("SELECT reward_id,player_id FROM guild_activity_reward_owed WHERE status=0 AND next_attempt_ms<=? ORDER BY "
                                + "next_attempt_ms,reward_id LIMIT 100",
                        r -> new long[] {u64(r, 1), u64(r, 2)}, now));
        int count = 0;
        for (var row : due) {
            if (d.expired())
                break;
            boolean queued = baseSql.tx(d, s -> {
                // owed与过户/导入共用P首锁。冻结先赢就保留待办；这里不捕获当前账号来替换原授权。
                Long listing = s.one("SELECT trade_listing_id FROM player WHERE player_id=? FOR UPDATE",r -> u64(r,1),u64(row[1]));
                if (listing == null || listing != 0) {
                    s.update("UPDATE guild_activity_reward_owed SET next_attempt_ms=? WHERE reward_id=? AND status=0",now+1000,u64(row[0]));
                    return false;
                }
                var t = jdbc(s, d);
                t.one("SELECT player_id FROM guild_player_op_seq WHERE player_id=? AND stream=2 FOR UPDATE",
                        r -> GuildJdbc.u64(r, 1), row[1]);
                Object[] owed = s.one("SELECT guild_id,activity_id,period_key,payload FROM guild_activity_reward_owed WHERE reward_id=? "
                                + "AND status=0 FOR UPDATE",
                        r -> new Object[] {u64(r, 1), r.getInt(2), r.getInt(3), r.getBytes(4)}, u64(row[0]));
                if (owed == null)
                    return false;
                var seq = JdbcEconomyStore.allocateSeq(t, row[1], 2, now);
                if (seq == null) {
                    s.update("UPDATE guild_activity_reward_owed SET next_attempt_ms=? WHERE reward_id=? AND status=0", now + 1000,
                            u64(row[0]));
                    return false;
                }
                var op = GuildAssetOpRow.newBuilder()
                                 .setOpId(row[0])
                                 .setPlayerId(row[1])
                                 .setGuildId((Long) owed[0])
                                 .setStream(2)
                                 .setSeq(seq.seq())
                                 .setStreamEpoch(seq.epoch())
                                 .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_ACTIVITY_REWARD)
                                 .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                                 .setNextAttemptMs(now)
                                 .setPayload(ByteString.copyFrom((byte[]) owed[3]))
                                 .setRefId((Integer) owed[1])
                                 .setRefCount(1)
                                 .setPeriodKey((Integer) owed[2])
                                 .setCreatedMs(now)
                                 .setUpdatedMs(now)
                                 .setTxType(TransactionType.TX_GUILD_ACTIVITY_REWARD_VALUE)
                                 .build();
                t.update(AssetOpColumns.INSERT, AssetOpColumns.insertArgs(op));
                s.update("UPDATE guild_activity_reward_owed SET status=1,op_id=? WHERE reward_id=? AND status=0", u64(row[0]), u64(row[0]));
                return true;
            });
            if (queued)
                count++;
        }
        return count;
    }
    public void expireBattle(long id, long now, Deadline d) throws SQLException {
        sql.tx(d, s -> {
            s.update("UPDATE guild_trial_battle SET state=IF(created_ms<?,3,state),next_inspect_ms=? WHERE battle_id=? AND state IN(1,3)",
                    now - 3600000, now + 30000, u64(id));
            return null;
        });
    }
    /** 巡检在外部I/O前推进排序水位；坏记录和依赖故障不得持续霸占首批100条。 */
    public void deferBattleInspection(long id, long now, Deadline d) throws SQLException {
        baseSql.tx(d, s -> {
            s.update("UPDATE guild_trial_battle SET next_inspect_ms=? WHERE battle_id=? AND state IN(1,3)", now + 30000, u64(id));
            return null;
        });
    }
    public void poison(String topic, int partition, long offset, byte[] payload, long now, Deadline d) throws SQLException {
        sql.tx(d, s -> {
            s.update("INSERT IGNORE INTO guild_activity_result_poison(partition_id,offset_id,payload,topic,created_ms) VALUES(?,?,?,?,?)",
                    partition, u64(offset), payload, topic, now);
            return null;
        });
    }
    public void poisonBattle(long battle, String topic, int partition, long offset, byte[] payload, long now, Deadline d) throws SQLException {
        BattleResultEvent event;
        try { event = BattleResultEvent.parseFrom(payload); }
        catch (com.google.protobuf.InvalidProtocolBufferException malformed) { throw new UnprovenResult("历练隔离缺少可解析的完整终局"); }
        if (event.getBattleId() != battle) throw new UnprovenResult("历练隔离战报与待结算对局不符");
        var original = terminalOriginal(event, d);
        byte[] contextBytes = baseSql.read(d,s -> s.one("SELECT context_proto FROM guild_trial_battle WHERE battle_id=?",r -> r.getBytes(1),u64(battle)));
        if (contextBytes == null) throw new SQLException("历练隔离缺少已绑定对局，保留ACTIVE等待原开战恢复");
        BattleActivityContext context;
        try { context = BattleActivityContext.parseFrom(contextBytes); }
        catch (com.google.protobuf.InvalidProtocolBufferException bad) { throw new SQLException("历练隔离原上下文损坏",bad); }
        if (!TrialLaunchAuthority.start(original).getActivityContext().equals(context)) throw new SQLException("历练隔离原件不符");
        var observed = baseSql.read(d,s -> TrialLaunchAuthority.load(s,original.getRequestId(),false));
        if (observed != null && observed.getState() == 3) {
            // 终局投影不可复活；角色此后可能已过户，重复毒消息只追加审计，不再以当前owner补验旧授权。
            baseSql.tx(d,s -> {
                var finished = TrialLaunchAuthority.load(s,original.getRequestId(),true);
                if (finished == null || finished.getState() != 3 || finished.getBattleId() != battle
                        || !original.equals(TrialLaunchAuthority.original(finished))) throw new SQLException("历练隔离终局原件冲突");
                TrialLaunchAuthority.verifyProjection(s,original,false);
                s.update("INSERT IGNORE INTO guild_activity_result_poison(partition_id,offset_id,payload,topic,created_ms) VALUES(?,?,?,?,?)",
                        partition,u64(offset),payload,topic,now);
                return null;
            });
            return;
        }
        baseSql.tx(d,s -> {
            TrialLaunchAuthority.lockOwners(s,original);
            s.one("SELECT guild_id FROM guild WHERE guild_id=? FOR UPDATE",r -> u64(r,1),u64(original.getGuildId()));
            var launch = TrialLaunchAuthority.load(s,original.getRequestId(),true);
            if (launch == null || launch.getBattleId() != battle || !original.equals(TrialLaunchAuthority.original(launch)))
                throw new SQLException("历练隔离缺少同一原对局绑定");
            s.update("INSERT IGNORE INTO guild_activity_result_poison(partition_id,offset_id,payload,topic,created_ms) VALUES(?,?,?,?,?)",
                    partition,u64(offset),payload,topic,now);
            if (launch.getState() != 3) {
                TrialLaunchAuthority.update(s,original,battle,true,new byte[0],now);
                s.update("UPDATE guild_trial_battle SET state=4 WHERE battle_id=? AND state IN(1,3)",u64(battle));
                closeTerminalLobby(s, original, battle, null);
            }
            return null;
        });
    }
}
