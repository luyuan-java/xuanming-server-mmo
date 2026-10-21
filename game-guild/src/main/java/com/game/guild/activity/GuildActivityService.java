package com.game.guild.activity;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildTips;
import com.game.guild.store.GuildActivityStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.*;
import java.sql.SQLException;
import java.util.*;
import java.util.function.LongSupplier;

/** 客户端五活动入口。身份来自gate；依赖故障抛给既有派发层，业务拒绝保留in-band契约。 */
public final class GuildActivityService {
    @FunctionalInterface
    public interface Presence {
        Set<Long> online(Collection<Long> players, Deadline deadline);
    }
    @FunctionalInterface
    public interface Locks {
        Map<Long, Boolean> inBattle(Collection<Long> players, Deadline deadline);
    }
    @FunctionalInterface
    public interface Effects {
        void committed(long guild, long actor, Collection<Long> players);
    }
    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException;
    }
    private final GuildActivityStore store;
    private final Presence presence;
    private final Locks locks;
    private final Effects effects;
    private final LongSupplier now;
    private final boolean enabled;
    public GuildActivityService(
            GuildActivityStore store, Presence presence, Locks locks, Effects effects, LongSupplier now, boolean enabled) {
        this.store = store;
        this.presence = presence;
        this.locks = locks;
        this.effects = effects;
        this.now = now;
        this.enabled = enabled;
    }
    public GuildActivityService forActor(com.game.api.proto.SessionContext actor) {
        return new GuildActivityService(store.forActor(actor), presence, locks, effects, now, enabled);
    }
    private <T> T run(Work<T> work, java.util.function.Function<TipInfoMessage, T> reject) {
        try {
            if (!enabled)
                throw new GuildActivityStore.Rejected(GuildTips.ACTIVITY_NOT_OPEN);
            return work.run();
        } catch (GuildActivityStore.Rejected rejected) {
            return reject.apply(TipInfoMessage.newBuilder().setId(rejected.tip).addAllParameters(rejected.parameters).build());
        } catch (SQLException failure) {
            throw new IllegalStateException("帮会活动依赖未确认，原SQL意图保留", failure);
        }
    }
    private GuildActivityView view(long player, int activity, long at, Deadline d) throws SQLException {
        return store.views(player, at, d)
                .stream()
                .filter(v -> v.getActivityId() == activity)
                .findFirst()
                .orElse(GuildActivityView.getDefaultInstance());
    }
    private void changed(long guild, long player) {
        try {
            effects.committed(guild, player, store.members(guild, Deadline.after(500)));
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(GuildActivityService.class)
                    .warn("活动已提交，提醒未确认 failure={}", failure.getClass().getSimpleName());
        }
    }
    public GetGuildActivitiesResponse list(long player, GetGuildActivitiesRequest request, Deadline d) {
        return run(() -> {
            long at = now.getAsLong();
            var views = store.views(player, at, d);
            var response = GetGuildActivitiesResponse.newBuilder();
            for (var v : views) {
                if (v.getTypeValue() == 2 && v.getStateValue() == 3 && !v.getThresholdReached()) {
                    long guild = store.member(player, d).guild();
                    int online = presence.online(store.qualifiedMembers(guild, at, d), d).size();
                    var observed = v.toBuilder().setProgress(online);
                    if (v.getBlockedTipId() == GuildTips.ACTIVITY_THRESHOLD_NOT_REACHED && online >= v.getGuildThreshold())
                        observed.clearBlockedTipId();
                    response.addActivities(observed);
                } else
                    response.addActivities(v);
            }
            return response.build();
        }, tip -> GetGuildActivitiesResponse.newBuilder().setErrorMessage(tip).build());
    }
    public LightGuildLanternResponse lantern(long player, LightGuildLanternRequest request, Deadline d) {
        return run(() -> {
            long at = now.getAsLong(), guild = store.member(player, d).guild();
            var committed = store.participate(player, guild, 1, request.getActivityId(), 0, at, d);
            changed(guild, player);
            return LightGuildLanternResponse.newBuilder().setActivity(committed).build();
        }, tip -> LightGuildLanternResponse.newBuilder().setErrorMessage(tip).build());
    }
    public ClaimGuildReunionResponse reunion(long player, ClaimGuildReunionRequest request, Deadline d) {
        return run(() -> {
            long at = now.getAsLong(), guild = store.member(player, d).guild();
            var qualified = store.qualifiedMembers(guild, at, d);
            int online = presence.online(qualified, d).size();
            var committed = store.participate(player, guild, 2, request.getActivityId(), online, at, d);
            changed(guild, player);
            return ClaimGuildReunionResponse.newBuilder().setActivity(committed).build();
        }, tip -> ClaimGuildReunionResponse.newBuilder().setErrorMessage(tip).build());
    }
    private void rosterReady(Collection<Long> players, Deadline d) {
        var online = presence.online(players, d);
        var inBattle = locks.inBattle(players, d);
        for (long p : players) {
            if (!online.contains(p))
                throw new GuildActivityStore.Rejected(GuildTips.TRIAL_TEAM_INVALID, "offline", Long.toUnsignedString(p));
            if (Boolean.TRUE.equals(inBattle.get(p)))
                throw new GuildActivityStore.Rejected(GuildTips.TRIAL_TEAM_INVALID, "in_battle", Long.toUnsignedString(p));
        }
    }
    public StartGuildTrialResponse start(long player, StartGuildTrialRequest request, Deadline d) {
        return run(() -> {
            long at = now.getAsLong(), guild = store.member(player, d).guild();
            var ids = request.getMemberPlayerIdsList();
            if (ids.size() < 2 || ids.size() > 5 || ids.contains(0L) || !ids.contains(player) || new HashSet<>(ids).size() != ids.size())
                throw new GuildActivityStore.Rejected(GuildTips.TRIAL_TEAM_INVALID, "invalid", "0");
            var before = view(player, request.getActivityId(), at, d);
            rosterReady(ids, d);
            var lobby = store.start(player, guild, request, at, d);
            changed(guild, player);
            return StartGuildTrialResponse.newBuilder().setActivity(before.toBuilder().setTrialLobby(lobby)).build();
        }, tip -> StartGuildTrialResponse.newBuilder().setErrorMessage(tip).build());
    }
    public RespondGuildTrialInviteResponse respond(long player, RespondGuildTrialInviteRequest request, Deadline d) {
        return run(() -> {
            long at = now.getAsLong(), guild = store.member(player, d).guild();
            var activity = store.views(player, at, d)
                                   .stream()
                                   .filter(v -> v.getTypeValue() == 3)
                                   .findFirst()
                                   .orElse(GuildActivityView.getDefaultInstance());
            if (request.getAccept())
                rosterReady(List.of(player), d);
            var lobby = store.respond(player, request, at, d);
            changed(guild, player);
            return RespondGuildTrialInviteResponse.newBuilder().setActivity(activity.toBuilder().setTrialLobby(lobby)).build();
        }, tip -> RespondGuildTrialInviteResponse.newBuilder().setErrorMessage(tip).build());
    }
}
