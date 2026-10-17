package com.game.guild.store;

import static com.game.discovery.sql.AuthorityJdbc.u64;

import com.game.api.proto.AssetBundle;
import com.game.common.deadline.Deadline;
import com.game.common.time.GameDay;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.guild.activity.ActivityRules;
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
    private final ConfigTables tables;
    private final LongSupplier ids;
    private final GuildStore.ZoneFence fence;
    public GuildActivityStore(AuthorityJdbc sql, ConfigTables tables, LongSupplier ids) {
        this(sql, tables, ids, GuildStore.ZoneFence.OPEN);
    }
    public GuildActivityStore(AuthorityJdbc sql, ConfigTables tables, LongSupplier ids, GuildStore.ZoneFence fence) {
        this.sql = sql;
        this.tables = tables;
        this.ids = ids;
        this.fence = fence;
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
        return memberTx(player, d, (s, m) -> {
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
                                && old.getStateValue() == 1)
                            return old;
                        else
                            throw new Rejected(GuildTips.TRIAL_TEAM_INVALID, "busy", Long.toUnsignedString(p));
                    }
                }
                if (p == player && slot[2] > now)
                    throw new Rejected(GuildTips.TRIAL_INVITE_COOLDOWN, Long.toString((slot[2] - now + 999) / 1000));
            }
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
                    u64(lobbyId), u64(m.guild), row.getId(), view.toByteArray(), new byte[0], now);
            for (long p : sorted)
                s.update("UPDATE guild_activity_slot SET lobby_id=?,expires_ms=?,cooldown_ms=IF(player_id=?, ?,cooldown_ms) WHERE "
                                + "player_id=?",
                        u64(lobbyId), view.getExpireAtMs(), u64(player), now + rule.getTrialInviteCooldownSeconds() * 1000L, u64(p));
            return view;
        });
    }
    public GuildTrialLobbyView respond(long player, RespondGuildTrialInviteRequest request, long now, Deadline d) throws SQLException {
        return memberTx(player, d, (s, m) -> {
            // G锁串行本帮邀请/退出；读名单后按无符号主键顺序锁所有成员，再锁房间。
            var peek = lobby(s, request.getLobbyId(), false);
            Long guild = s.one("SELECT guild_id FROM guild_trial_lobby WHERE lobby_id=?", r -> u64(r, 1), u64(request.getLobbyId()));
            if (peek == null || guild == null || guild != m.guild || !peek.getMemberPlayerIdsList().contains(player))
                throw new Rejected(GuildTips.TRIAL_INVITE_EXPIRED);
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
                if (view.getAcceptedPlayerIdsList().contains(player))
                    return view;
                throw new Rejected(GuildTips.TRIAL_INVITE_EXPIRED);
            }
            if (view.getExpireAtMs() <= now)
                return end(s, view, GuildTips.TRIAL_INVITE_EXPIRED);
            if (!request.getAccept())
                return end(s, view, GuildTips.TRIAL_INVITE_DECLINED, Long.toUnsignedString(player));
            row(3, row.getId(), now);
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
                                      .build();
                var start = StartActivityBattleRequest.newBuilder()
                                    .setRequestId(view.getLobbyId())
                                    .setBattleConfigId(row.getDungeonId())
                                    .addAllMemberPlayerIds(view.getMemberPlayerIdsList())
                                    .setActivityContext(context)
                                    .build();
                next.setStateValue(2);
                saveLobby(s, next.build(), now, start.toByteArray());
                for (long p : sorted)
                    s.update("UPDATE guild_activity_slot SET expires_ms=? WHERE player_id=? AND lobby_id=?", Long.MAX_VALUE, u64(p),
                            u64(view.getLobbyId()));
            } else
                saveLobby(s, next.build(), 0, new byte[0]);
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
        return sql.read(d,
                s
                -> s.list("SELECT lobby_id FROM guild_trial_lobby WHERE state=2 AND next_attempt_ms<=? AND lease_until_ms<=? ORDER BY "
                                + "lobby_id LIMIT 32",
                        r -> u64(r, 1), now, now));
    }
    public Launch leaseLaunch(long lobby, long now, Deadline d) throws SQLException {
        long token = id();
        return sql.tx(d, s -> {
            if (s.update("UPDATE guild_trial_lobby SET lease_token=?,lease_until_ms=? WHERE lobby_id=? AND state=2 AND next_attempt_ms<=? "
                                + "AND lease_until_ms<=?",
                        u64(token), now + 10000, u64(lobby), now, now)
                    != 1)
                return null;
            return s.one("SELECT guild_id,request_proto FROM guild_trial_lobby WHERE lobby_id=?", r -> {
                try {
                    return new Launch(lobby, u64(r, 1), token, StartActivityBattleRequest.parseFrom(r.getBytes(2)));
                } catch (Exception e) {
                    throw new SQLException("开战意图损坏", e);
                }
            }, u64(lobby));
        });
    }
    public void completeLaunch(Launch launch, StartActivityBattleResponse response, long now, Deadline d) throws SQLException {
        sql.tx(d, s -> {
            // G先于T，与结算保持顺序。帮会已解散仍允许终结邀请，不能改写match事务。
            s.one("SELECT guild_id FROM guild WHERE guild_id=? FOR UPDATE", r -> u64(r, 1), u64(launch.guild));
            Long token = s.one("SELECT lease_token FROM guild_trial_lobby WHERE lobby_id=? FOR UPDATE", r -> u64(r, 1), u64(launch.lobby));
            if (token == null || token != launch.token)
                return null;
            var view = lobby(s, launch.lobby, false);
            if (view == null || view.getStateValue() != 2)
                return null;
            if (response == null || response.getRejectValue() != 0 && (response.getRejectValue() < 1 || response.getRejectValue() > 4)) {
                s.update("UPDATE guild_trial_lobby SET next_attempt_ms=?,lease_until_ms=0 WHERE lobby_id=? AND lease_token=?", now + 1000,
                        u64(launch.lobby), u64(launch.token));
                return null;
            }
            if (response.getRejectValue() != 0) {
                int rejection = response.getRejectValue();
                String reason = switch (rejection) {
                    case 2 -> "offline";
                    case 3 -> "in_battle";
                    case 4 -> "not_ready";
                    default -> "invalid";
                };
                end(s, view, rejection >= 1 && rejection <= 4 ? GuildTips.TRIAL_TEAM_INVALID : GuildTips.TRIAL_SERVICE_BUSY, reason,
                        Long.toUnsignedString(response.getOffenderPlayerId()));
                return null;
            }
            if (response.getBattleId() == 0)
                throw new SQLException("match成功缺battle_id");
            byte[] context = launch.request.getActivityContext().toByteArray();
            s.update("INSERT IGNORE INTO "
                            + "guild_trial_battle(battle_id,guild_id,activity_id,context_proto,state,created_ms,result_hash,result_proto) "
                            + "VALUES(?,?,?,?,1,?,?,?)",
                    u64(response.getBattleId()), u64(launch.guild), launch.request.getActivityContext().getActivityId(), context, now,
                    new byte[0], new byte[0]);
            byte[] stored = s.one(
                    "SELECT context_proto FROM guild_trial_battle WHERE battle_id=?", r -> r.getBytes(1), u64(response.getBattleId()));
            if (!Arrays.equals(stored, context))
                throw new SQLException("对局上下文冲突");
            var done = view.toBuilder().setStateValue(3).setBattleId(response.getBattleId()).build();
            saveLobby(s, done, 0, launch.request.toByteArray());
            release(s, view);
            return null;
        });
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
        if (event.getBattleId() == 0 || context.getGuildId() == 0 || context.getActivityId() == 0 || context.getPeriodKey() == 0
                || context.getGuildPeriodKey() == 0 || event.getMatchMode() != MatchMode.MATCH_MODE_PVE_TEAM_VALUE)
            throw new InvalidResult("历练战报上下文不完整");
        byte[] raw = event.toByteArray();
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (Exception e) {
            throw new SQLException(e);
        }
        byte[][] settled = sql.read(d,
                s
                -> s.one("SELECT context_proto,result_hash FROM guild_trial_battle WHERE battle_id=? AND state=2",
                        r -> new byte[][] {r.getBytes(1), r.getBytes(2)}, u64(event.getBattleId())));
        if (settled != null) {
            if (!Arrays.equals(settled[0], context.toByteArray()) || !Arrays.equals(settled[1], digest))
                throw new InvalidResult("已结算历练的重复结果变更");
            return;
        }
        sql.tx(d, s -> {
            Long guild = s.one("SELECT guild_id FROM guild WHERE guild_id=? FOR UPDATE", r -> u64(r, 1), u64(context.getGuildId()));
            if (guild != null)
                fence(s.one("SELECT zone_id FROM guild WHERE guild_id=?", r -> r.getInt(1), u64(guild)));
            if (guild == null)
                return null;
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

            // 多局同活动时按完整上下文与战报名单逐一匹配，不能把另一邀请借来发奖。
            var candidates = s.list("SELECT request_proto FROM guild_trial_lobby WHERE guild_id=? AND activity_id=? AND battle_id=?",
                    r -> r.getBytes(1), u64(context.getGuildId()), context.getActivityId(), u64(event.getBattleId()));
            StartActivityBattleRequest start = null;
            Set<Long> team = new HashSet<>();
            int teamZeroCount = 0;
            for (var t : event.getTeamsList())
                if (t.getTeamIndex() == 0) {
                    teamZeroCount++;
                    for (long p : t.getPlayerIdsList())
                        if (p == 0 || !team.add(p))
                            throw new InvalidResult("历练战报己方名单重复或为0");
                }
            if (teamZeroCount != 1)
                throw new InvalidResult("历练战报缺少唯一己方队伍");
            for (byte[] bytes : candidates) try {
                    var q = StartActivityBattleRequest.parseFrom(bytes);
                    if (q.getActivityContext().equals(context) && new HashSet<>(q.getMemberPlayerIdsList()).equals(team)) {
                        if (start != null)
                            throw new InvalidResult("历练上下文多义");
                        start = q;
                    }
                } catch (com.google.protobuf.InvalidProtocolBufferException bad) {
                    throw new SQLException(bad);
                }
            if (start == null)
                throw new InvalidResult("历练结果没有匹配的已授权开战名单");
            if (start.getBattleConfigId() != event.getBattleConfigId() || event.getOutcomeValue() == 1 && event.getWinnerTeamIndex() != 0)
                throw new InvalidResult("历练战报副本或胜方冲突");
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
            return null;
        });
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
            boolean queued = sql.tx(d, s -> {
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
    public void poison(String topic, int partition, long offset, byte[] payload, long now, Deadline d) throws SQLException {
        sql.tx(d, s -> {
            s.update("INSERT IGNORE INTO guild_activity_result_poison(partition_id,offset_id,payload,topic,created_ms) VALUES(?,?,?,?,?)",
                    partition, u64(offset), payload, topic, now);
            return null;
        });
    }
}
