package com.game.discovery;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * {@link RedisKeys} 全部生成方法的固定样例与缺省命名空间（{@code game}）下的金样字面量。{@link RedisKeysGoldenTest} 用 {@code game} 的实例断言整串逐字节相同；
 * {@link RedisKeysNamespaceTest} 用 {@code nstest} 的实例断言只有前缀换成了 {@code <ns>:}、其余部分（节点类型、hash tag、字段名）与金样相同。
 *
 * <p>新增生成方法必须在这里补一行：{@link #publicStringMethods()} 与样例覆盖的方法名集合不一致时金样测试失败。
 */
final class RedisKeysSamples {

    /** 缺省命名空间的前缀（金样里每一条都以它开头）。 */
    static final String DEFAULT_PREFIX = "game:";

    /** 一条样例：方法名、对给定实例按固定参数生成键、缺省命名空间下的完整字面量。 */
    record Sample(String method, Function<RedisKeys, String> key, String golden) {
    }

    private RedisKeysSamples() {
    }

    static List<Sample> all() {
        return List.of(
                new Sample("prefix", RedisKeys::prefix, "game:"),
                new Sample("nodeId", k -> k.nodeId("scene", 1, 2), "game:node-id:scene:1:2"),
                new Sample("nodeIdEpoch", k -> k.nodeIdEpoch("scene", 1, 2), "game:node-id-epoch:scene:1:2"),
                new Sample("nodeDirectory", k -> k.nodeDirectory("gate", 3), "game:nodes:gate:3"),
                new Sample("ownerTakeoverTopic", RedisKeys::ownerTakeoverTopic, "game:owner-takeover"),
                new Sample("presence", k -> k.presence(-1L), "game:presence:18446744073709551615"),
                new Sample("playerLocation", k -> k.playerLocation(42), "game:location:42"),
                new Sample("gateDraining", k -> k.gateDraining(1, 2), "game:gate-draining:1:2"),
                new Sample("gateDrained", k -> k.gateDrained(1, 2), "game:gate-drained:1:2"),
                new Sample("friendList", k -> k.friendList(7), "game:friend:{7}:list"),
                new Sample("friendPending", k -> k.friendPending(7), "game:friend:{7}:req"),
                // 代次键由数据键推出：样例参数传真键
                new Sample("cacheGeneration", k -> k.cacheGeneration(k.guildSnapshot(8)), "game:guild:{g:8}:snap:gen"),
                new Sample("friendCacheGeneration", k -> k.friendCacheGeneration(k.friendList(7)), "game:friend:{7}:list:gen"),
                new Sample("friendRequestQuota", k -> k.friendRequestQuota(7), "game:friend:{7}:quota"),
                new Sample("friendDirectoryQuota", k -> k.friendDirectoryQuota(7), "game:friend:{7}:dir-quota"),
                new Sample("presencePrefix", RedisKeys::presencePrefix, "game:presence:"),
                new Sample("chatWorldLog", RedisKeys::chatWorldLog, "game:chat:{world}:log"),
                new Sample("chatPrivateLog", k -> k.chatPrivateLog(9, 3), "game:chat:{p:3:9}:log"),
                new Sample("chatPrivateLog", k -> k.chatPrivateLog(-1L, 1), "game:chat:{p:1:18446744073709551615}:log"),
                new Sample("chatRequestId", k -> k.chatRequestId(5, "r1"), "game:chat:{req:5}:r1"),
                new Sample("chatRateLimit", k -> k.chatRateLimit(5), "game:chat:{rl:5}"),
                new Sample("teamRecord", k -> k.teamRecord(11), "game:{team}:rec:11"),
                new Sample("teamInfo", k -> k.teamInfo(11), "game:{team}:info:11"),
                new Sample("teamPlayer", k -> k.teamPlayer(5), "game:{team}:player:5"),
                new Sample("teamInvite", k -> k.teamInvite(5), "game:{team}:invite:5"),
                new Sample("guildSnapshot", k -> k.guildSnapshot(8), "game:guild:{g:8}:snap"),
                new Sample("guildOfPlayer", k -> k.guildOfPlayer(5), "game:guild:{p:5}:gid"),
                new Sample("guildApplyPush", k -> k.guildApplyPush(8, 5), "game:guild:{g:8}:apply-push:5"),
                new Sample("guildApplyQuota", k -> k.guildApplyQuota(-1L), "game:guild:{apply:18446744073709551615}"),
                new Sample("guildRankAll", RedisKeys::guildRankAll, "game:guild:{rank}:all"),
                new Sample("guildRankZone", k -> k.guildRankZone(2), "game:guild:{rank}:zone:2"),
                new Sample("guildRankZones", RedisKeys::guildRankZones, "game:guild:{rank}:zones"),
                new Sample("guildRankLock", RedisKeys::guildRankLock, "game:guild:{rank}:lock"),
                new Sample("guildRankTmpAll", k -> k.guildRankTmpAll("t1"), "game:guild:{rank}:tmp:t1:all"),
                new Sample("guildRankTmpZone", k -> k.guildRankTmpZone("t1", 2), "game:guild:{rank}:tmp:t1:zone:2"),
                new Sample("worldChannels", k -> k.worldChannels(1), "game:world:{z:1}:ch"),
                new Sample("worldDesired", k -> k.worldDesired(1), "game:world:{z:1}:desired"),
                new Sample("worldCooldown", k -> k.worldCooldown(1), "game:world:{z:1}:cooldown"),
                new Sample("worldPlanVersion", k -> k.worldPlanVersion(1), "game:world:{z:1}:ver"),
                new Sample("worldLeader", k -> k.worldLeader(1), "game:world:{z:1}:leader"),
                new Sample("worldReservations", k -> k.worldReservations(1, 77), "game:world:{z:1}:resv:77"),
                new Sample("worldZones", RedisKeys::worldZones, "game:world:zones"),
                new Sample("inboxMaintenanceLeader", RedisKeys::inboxMaintenanceLeader, "game:inbox:leader"),
                new Sample("killSwitch", RedisKeys::killSwitch, "game:killswitch"),
                new Sample("loginQueue", k -> k.loginQueue(1), "game:login-queue:1"),
                new Sample("loginQueueAdmitted", k -> k.loginQueueAdmitted(1), "game:login-queue-admitted:1"),
                new Sample("loginQueueAdmit", k -> k.loginQueueAdmit("q1"), "game:login-queue-admit:q1"),
                new Sample("loginQueueAdmit", k -> k.loginQueueAdmit(""), "game:login-queue-admit:"),
                new Sample("loginQueueMeta", k -> k.loginQueueMeta("q1"), "game:login-queue-meta:q1"),
                new Sample("loginQueueMeta", k -> k.loginQueueMeta(""), "game:login-queue-meta:"),
                new Sample("rateLimitZone", k -> k.rateLimitZone(1), "game:rl:zone:1"),
                new Sample("rateLimitIp", k -> k.rateLimitIp("1.2.3.4"), "game:rl:ip:1.2.3.4"),
                new Sample("rateLimitCooldown", k -> k.rateLimitCooldown("login", "abc"), "game:rl:cd:login:abc"),
                new Sample("loginQueueDispatcherLock", RedisKeys::loginQueueDispatcherLock, "game:login-queue-dispatcher"),
                new Sample("gatePushTopic", k -> k.gatePushTopic(1, 2), "game:gate-push:1:2"),
                new Sample("gainBlocks", k -> k.gainBlocks(RedisKeys.GAIN_BLOCK_CURRENCY), "game:gain-block:currency"),
                new Sample("gainBlocks", k -> k.gainBlocks(RedisKeys.GAIN_BLOCK_ITEM), "game:gain-block:item"),
                new Sample("gainBlockChangedTopic", RedisKeys::gainBlockChangedTopic, "game:gain-block-changed"),
                new Sample("loginAccessToken", k -> k.loginAccessToken("fam", "tok"), "game:login:access:{fam}:tok"),
                new Sample("loginPasswordFailure", k -> k.loginPasswordFailure("known", "0".repeat(64)), "game:login:password-failure-v1:{known:" + "0".repeat(64) + "}"),
                new Sample("loginRefreshToken", k -> k.loginRefreshToken("fam", "tok"), "game:login:refresh:{fam}:tok"),
                new Sample("loginRefreshRotated", k -> k.loginRefreshRotated("fam", "tok"), "game:login:refresh-rotated:{fam}:tok"),
                new Sample("loginRefreshFamily", k -> k.loginRefreshFamily("fam"), "game:login:refresh-family:{fam}"),
                new Sample("loginAccountRefresh", k -> k.loginAccountRefresh("acc"), "game:login:account-refresh:acc"),
                new Sample("loginDevices", k -> k.loginDevices("acc"), "game:login:devices:acc"),
                new Sample("loginDeviceSession", k -> k.loginDeviceSession("s1"), "game:login:device-session:s1"),
                new Sample("battleLock", k -> k.battleLock(42), "game:battle:{42}:lock"),
                new Sample("battleSettlements", k -> k.battleSettlements(42), "game:battle:{42}:settlement"),
                new Sample("battleSettled", k -> k.battleSettled(42, 9), "game:battle:{42}:settled:9"),
                new Sample("battleActivityResult", k -> k.battleActivityResult(9), "game:battle:activity-result:9"),
                new Sample("matchQueueIndex", RedisKeys::matchQueueIndex, "game:{match}:index"),
                new Sample("matchQueue", k -> k.matchQueue(1, -1), "game:{match}:queue:1:4294967295"),
                new Sample("matchRank", k -> k.matchRank(1, 2), "game:{match}:rank:1:2"),
                new Sample("matchQueueLock", k -> k.matchQueueLock(1, 2), "game:{match}:lock:1:2"),
                new Sample("matchTicket", k -> k.matchTicket(5), "game:{match}:ticket:5"),
                new Sample("matchPopMarker", k -> k.matchPopMarker("p1"), "game:{match}:pop:p1"),
                new Sample("matchRequeueMarker", k -> k.matchRequeueMarker("r1"), "game:{match}:requeue:r1"),
                new Sample("matchChallenge", k -> k.matchChallenge(3), "game:{match}:challenge:3"),
                new Sample("matchChallengeTarget", k -> k.matchChallengeTarget(5), "game:{match}:challenge-target:5"),
                new Sample("matchChallengeDone", k -> k.matchChallengeDone(3), "game:{match}:challenge-done:3"),
                new Sample("matchBattlePlacement", k -> k.matchBattlePlacement(9), "game:{match}:battle:9"),
                new Sample("matchWatching", k -> k.matchWatching(5), "game:{match}:watching:5"),
                new Sample("matchWatchable", RedisKeys::matchWatchable, "game:{match}:watchable"));
    }

    /** 样例覆盖到的方法名。 */
    static Set<String> sampledMethods() {
        Set<String> names = new TreeSet<>();
        all().forEach(s -> names.add(s.method()));
        return names;
    }

    /** {@link RedisKeys} 里全部返回 String 的 public 实例方法名（生成键 / 频道的方法，加上 {@code prefix()}；不含 {@code toString}）。 */
    static Set<String> publicStringMethods() {
        Set<String> names = new TreeSet<>();
        for (Method m : RedisKeys.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class
                    && !m.getName().equals("toString")) {
                names.add(m.getName());
            }
        }
        return names;
    }
}
