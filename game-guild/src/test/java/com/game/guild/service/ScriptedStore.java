package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildTableRules.ApplicationRules;
import com.game.guild.store.GuildData;
import com.game.guild.store.GuildStore;
import com.game.guild.store.TxOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 服务单测用的存储替身：读走内存表（{@link #guilds} / {@link #playerGuild} / {@link #pending}……），写走每个用例自己给的脚本；
 * 没给脚本的写一律 {@link AssertionError}（「不该碰库」本身就是断言）。每次调用把方法名与关键参数写进 {@code journal}。
 */
public final class ScriptedStore implements GuildStore {

    private final Consumer<String> journal;

    // ---- 读 ----
    final Map<Long, GuildData> guilds = new HashMap<>();
    /** 玩家 → 帮会（M5）；不在表里 = 0。 */
    final Map<Long, Long> playerGuild = new HashMap<>();
    /** 帮会 → 有效待审数（A13）。 */
    final Map<Long, Long> pending = new HashMap<>();
    List<ApplicationRow> myApplications = List.of();
    List<ApplicantRow> applicants = List.of();
    /** 非 null 时所有读都抛它（依赖故障）。 */
    public volatile RuntimeException readFailure;
    /** 非 null 时 countLiveApplications 抛它。 */
    volatile RuntimeException pendingFailure;

    // ---- 写脚本 ----
    Function<GuildData, TxOutcome<Created>> create;
    BiFunction<Long, Integer, TxOutcome<RoleChanged>> setRole;
    Function<Long, TxOutcome<Kicked>> kick;
    Function<Long, TxOutcome<Transferred>> transfer;
    Function<Long, TxOutcome<Left>> leave;
    Function<Long, TxOutcome<Applied>> apply;
    Function<Long, TxOutcome<Void>> cancel;
    BiFunction<Long, Boolean, TxOutcome<Reviewed>> review;
    Function<Long, TxOutcome<Disbanded>> disband;
    BiFunction<Long, String, TxOutcome<AnnouncementUpdated>> announce;

    // ---- 记下的入参 ----
    volatile int lastApplyZone = -1;
    volatile int lastReviewZone = -1;
    volatile ApplicationRules lastRules;
    volatile OfficerCaps lastCaps;
    volatile ZoneFence lastFence;
    volatile int lastListLimit = -1;
    final List<Long> createdGuilds = new ArrayList<>();

    ScriptedStore(Consumer<String> journal) {
        this.journal = journal;
    }

    private static <T> T script(T script, String name) {
        if (script == null) {
            throw new AssertionError("不该调用存储写 " + name);
        }
        return script;
    }

    @Override
    public TxOutcome<Created> createGuild(GuildData guild, Deadline deadline) {
        journal.accept("store.create " + Long.toUnsignedString(guild.guildId()));
        createdGuilds.add(guild.guildId());
        return script(create, "createGuild").apply(guild);
    }

    @Override
    public TxOutcome<RoleChanged> setMemberRole(long guildId, long actorId, long targetId, int role, OfficerCaps caps,
                                                Deadline deadline) {
        journal.accept("store.setRole g=" + guildId + " actor=" + actorId + " target=" + targetId + " role=" + role);
        lastCaps = caps;
        return script(setRole, "setMemberRole").apply(targetId, role);
    }

    @Override
    public TxOutcome<Kicked> kickMember(long guildId, long actorId, long targetId, long nowMs, Deadline deadline) {
        journal.accept("store.kick g=" + guildId + " actor=" + actorId + " target=" + targetId);
        return script(kick, "kickMember").apply(targetId);
    }

    @Override
    public TxOutcome<Transferred> transferLeader(long guildId, long actorId, long targetId, OfficerCaps caps,
                                                 Deadline deadline) {
        journal.accept("store.transfer g=" + guildId + " actor=" + actorId + " target=" + targetId);
        lastCaps = caps;
        return script(transfer, "transferLeader").apply(targetId);
    }

    @Override
    public TxOutcome<Left> leaveGuild(long guildId, long playerId, long nowMs, Deadline deadline) {
        journal.accept("store.leave g=" + guildId + " p=" + playerId);
        return script(leave, "leaveGuild").apply(guildId);
    }

    @Override
    public TxOutcome<Applied> applyToGuild(long guildId, long playerId, int requiredZone, long nowMs, ApplicationRules rules,
                                           Deadline deadline) {
        journal.accept("store.apply g=" + guildId + " p=" + playerId + " zone=" + requiredZone);
        lastApplyZone = requiredZone;
        lastRules = rules;
        return script(apply, "applyToGuild").apply(guildId);
    }

    @Override
    public TxOutcome<Void> cancelApplication(long guildId, long playerId, long nowMs, Deadline deadline) {
        journal.accept("store.cancel g=" + guildId + " p=" + playerId);
        return script(cancel, "cancelApplication").apply(guildId);
    }

    @Override
    public TxOutcome<Reviewed> reviewApplication(long guildId, long actorId, long applicantId, boolean approve,
                                                 int applicantZone, long nowMs, Deadline deadline) {
        journal.accept("store.review g=" + guildId + " actor=" + actorId + " applicant=" + applicantId + " approve="
                + approve + " zone=" + applicantZone);
        lastReviewZone = applicantZone;
        return script(review, "reviewApplication").apply(applicantId, approve);
    }

    @Override
    public TxOutcome<Disbanded> disbandGuild(long guildId, long actorId, long nowMs, ZoneFence fence, Deadline deadline) {
        journal.accept("store.disband g=" + guildId + " actor=" + actorId);
        lastFence = fence;
        return script(disband, "disbandGuild").apply(guildId);
    }

    @Override
    public TxOutcome<AnnouncementUpdated> updateAnnouncement(long guildId, long playerId, String announcement,
                                                             Deadline deadline) {
        journal.accept("store.announce g=" + guildId + " p=" + playerId);
        return script(announce, "updateAnnouncement").apply(guildId, announcement);
    }

    // ================================================================ 读

    private void maybeFail(String what) {
        journal.accept("store." + what);
        RuntimeException e = readFailure;
        if (e != null) {
            throw e;
        }
    }

    @Override
    public Optional<GuildData> loadGuild(long guildId, Deadline deadline) {
        maybeFail("loadGuild " + guildId);
        return Optional.ofNullable(guilds.get(guildId));
    }

    @Override
    public long playerGuildId(long playerId, Deadline deadline) {
        maybeFail("playerGuildId " + playerId);
        java.util.function.LongUnaryOperator hook = playerGuildHook;
        return hook != null ? hook.applyAsLong(playerId) : playerGuild.getOrDefault(playerId, 0L);
    }

    /** 非 null 时 M5 改由它回答（模拟两次读之间成员关系变了）。 */
    volatile java.util.function.LongUnaryOperator playerGuildHook;

    @Override
    public OptionalInt memberRole(long guildId, long playerId, Deadline deadline) {
        maybeFail("memberRole " + guildId + " " + playerId);
        GuildData g = guilds.get(guildId);
        GuildData.Member m = g == null ? null : g.member(playerId);
        return m == null ? OptionalInt.empty() : OptionalInt.of(m.role());
    }

    @Override
    public OptionalInt guildZoneId(long guildId, Deadline deadline) {
        maybeFail("guildZoneId " + guildId);
        GuildData g = guilds.get(guildId);
        return g == null ? OptionalInt.empty() : OptionalInt.of(g.zoneId());
    }

    @Override
    public long countLiveApplications(long guildId, long nowMs, Deadline deadline) {
        journal.accept("store.countLive " + guildId);
        RuntimeException e = pendingFailure;
        if (e != null) {
            throw e;
        }
        return pending.getOrDefault(guildId, 0L);
    }

    @Override
    public List<ApplicationRow> listMyApplications(long playerId, long nowMs, int limit, Deadline deadline) {
        maybeFail("listMy " + playerId);
        lastListLimit = limit;
        return myApplications;
    }

    @Override
    public List<ApplicantRow> listApplicants(long guildId, long nowMs, int limit, Deadline deadline) {
        maybeFail("listApplicants " + guildId);
        lastListLimit = limit;
        return applicants;
    }

    @Override
    public List<GuildScore> allGuildScores(Deadline deadline) {
        throw new AssertionError("服务单测不重建排行");
    }

    @Override
    public void scanGuildScores(Deadline deadline, java.util.function.Consumer<GuildScore> sink) {
        throw new AssertionError("服务单测不重建排行");
    }

    @Override
    public Optional<GuildScore> guildScore(long guildId, Deadline deadline) {
        throw new AssertionError("服务单测不读单帮分数");
    }

    // ================================================================ 造数据

    /** 造一个帮：帮主 role 3，其余成员 role 0（之后可用 {@link #withRole} 改）；成员按无符号升序。 */
    public static GuildData guild(long guildId, int zoneId, long leader, long... others) {
        List<Long> ids = new ArrayList<>();
        ids.add(leader);
        for (long o : others) {
            ids.add(o);
        }
        ids.sort(Long::compareUnsigned);
        List<GuildData.Member> members = new ArrayList<>();
        for (long id : ids) {
            members.add(new GuildData.Member(id, id == leader ? 3 : 0, 1000, 1000, 0, 0));
        }
        return new GuildData(guildId, "帮" + guildId, leader, 1, "", 1000, 30, zoneId, 0, 0, members);
    }

    /** 同一个帮，把 {@code playerId} 的 role 改掉。 */
    static GuildData withRole(GuildData g, long playerId, int role) {
        List<GuildData.Member> members = new ArrayList<>();
        for (GuildData.Member m : g.members()) {
            members.add(m.playerId() == playerId
                    ? new GuildData.Member(m.playerId(), role, m.joinTimeMs(), m.lastActiveMs(), m.contributionTotal(),
                    m.contributionBalance())
                    : m);
        }
        return new GuildData(g.guildId(), g.name(), g.leaderId(), g.level(), g.announcement(), g.createTimeMs(),
                g.maxMembers(), g.zoneId(), g.score(), g.funds(), members);
    }

    /** 登记一个帮，并把全部成员的 M5 映射指向它。 */
    public void put(GuildData g) {
        guilds.put(g.guildId(), g);
        for (GuildData.Member m : g.members()) {
            playerGuild.put(m.playerId(), g.guildId());
        }
    }
}
