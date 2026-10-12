package com.game.discovery;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * {@link RedisKeys} 全部生成方法的固定样例与缺省命名空间（{@code game}）下的金样字面量。缺省执行（{@link RedisKeysGoldenTest}）断言整串逐字节相同；
 * namespace 执行（{@link RedisKeysNamespaceTest}）断言只有前缀换成了 {@code <ns>:}、其余部分（节点类型、hash tag、字段名）与金样相同。
 *
 * <p>新增生成方法必须在这里补一行：{@link #publicStringMethods()} 与样例覆盖的方法名集合不一致时金样测试失败。
 */
final class RedisKeysSamples {

    /** 缺省命名空间的前缀（金样里每一条都以它开头）。 */
    static final String DEFAULT_PREFIX = "game:";

    /** 一条样例：方法名、按固定参数生成键、缺省命名空间下的完整字面量。 */
    record Sample(String method, Supplier<String> key, String golden) {
    }

    private RedisKeysSamples() {
    }

    static List<Sample> all() {
        return List.of(
                new Sample("nodeId", () -> RedisKeys.nodeId("scene", 1, 2), "game:node-id:scene:1:2"),
                new Sample("nodeIdEpoch", () -> RedisKeys.nodeIdEpoch("scene", 1, 2), "game:node-id-epoch:scene:1:2"),
                new Sample("nodeDirectory", () -> RedisKeys.nodeDirectory("gate", 3), "game:nodes:gate:3"),
                new Sample("ownerTakeoverTopic", RedisKeys::ownerTakeoverTopic, "game:owner-takeover"),
                new Sample("presence", () -> RedisKeys.presence(-1L), "game:presence:18446744073709551615"),
                new Sample("playerLocation", () -> RedisKeys.playerLocation(42), "game:location:42"),
                new Sample("gateDraining", () -> RedisKeys.gateDraining(1, 2), "game:gate-draining:1:2"),
                new Sample("gateDrained", () -> RedisKeys.gateDrained(1, 2), "game:gate-drained:1:2"),
                new Sample("friendList", () -> RedisKeys.friendList(7), "game:friend:{7}:list"),
                new Sample("friendPending", () -> RedisKeys.friendPending(7), "game:friend:{7}:req"),
                // 代次键由数据键推出：样例参数传真键
                new Sample("cacheGeneration", () -> RedisKeys.cacheGeneration(RedisKeys.guildSnapshot(8)), "game:guild:{g:8}:snap:gen"),
                new Sample("friendCacheGeneration", () -> RedisKeys.friendCacheGeneration(RedisKeys.friendList(7)), "game:friend:{7}:list:gen"),
                new Sample("friendRequestQuota", () -> RedisKeys.friendRequestQuota(7), "game:friend:{7}:quota"),
                new Sample("friendDirectoryQuota", () -> RedisKeys.friendDirectoryQuota(7), "game:friend:{7}:dir-quota"),
                new Sample("presencePrefix", RedisKeys::presencePrefix, "game:presence:"),
                new Sample("chatWorldLog", RedisKeys::chatWorldLog, "game:chat:{world}:log"),
                new Sample("chatPrivateLog", () -> RedisKeys.chatPrivateLog(9, 3), "game:chat:{p:3:9}:log"),
                new Sample("chatPrivateLog", () -> RedisKeys.chatPrivateLog(-1L, 1), "game:chat:{p:1:18446744073709551615}:log"),
                new Sample("chatRequestId", () -> RedisKeys.chatRequestId(5, "r1"), "game:chat:{req:5}:r1"),
                new Sample("chatRateLimit", () -> RedisKeys.chatRateLimit(5), "game:chat:{rl:5}"),
                new Sample("teamRecord", () -> RedisKeys.teamRecord(11), "game:{team}:rec:11"),
                new Sample("teamInfo", () -> RedisKeys.teamInfo(11), "game:{team}:info:11"),
                new Sample("teamPlayer", () -> RedisKeys.teamPlayer(5), "game:{team}:player:5"),
                new Sample("teamInvite", () -> RedisKeys.teamInvite(5), "game:{team}:invite:5"),
                new Sample("guildSnapshot", () -> RedisKeys.guildSnapshot(8), "game:guild:{g:8}:snap"),
                new Sample("guildOfPlayer", () -> RedisKeys.guildOfPlayer(5), "game:guild:{p:5}:gid"),
                new Sample("guildApplyPush", () -> RedisKeys.guildApplyPush(8, 5), "game:guild:{g:8}:apply-push:5"),
                new Sample("guildRankAll", RedisKeys::guildRankAll, "game:guild:{rank}:all"),
                new Sample("guildRankZone", () -> RedisKeys.guildRankZone(2), "game:guild:{rank}:zone:2"),
                new Sample("guildRankZones", RedisKeys::guildRankZones, "game:guild:{rank}:zones"),
                new Sample("guildRankLock", RedisKeys::guildRankLock, "game:guild:{rank}:lock"),
                new Sample("guildRankTmpAll", () -> RedisKeys.guildRankTmpAll("t1"), "game:guild:{rank}:tmp:t1:all"),
                new Sample("guildRankTmpZone", () -> RedisKeys.guildRankTmpZone("t1", 2), "game:guild:{rank}:tmp:t1:zone:2"),
                new Sample("worldChannels", () -> RedisKeys.worldChannels(1), "game:world:{z:1}:ch"),
                new Sample("worldDesired", () -> RedisKeys.worldDesired(1), "game:world:{z:1}:desired"),
                new Sample("worldCooldown", () -> RedisKeys.worldCooldown(1), "game:world:{z:1}:cooldown"),
                new Sample("worldPlanVersion", () -> RedisKeys.worldPlanVersion(1), "game:world:{z:1}:ver"),
                new Sample("worldLeader", () -> RedisKeys.worldLeader(1), "game:world:{z:1}:leader"),
                new Sample("worldReservations", () -> RedisKeys.worldReservations(1, 77), "game:world:{z:1}:resv:77"),
                new Sample("worldZones", RedisKeys::worldZones, "game:world:zones"),
                new Sample("killSwitch", RedisKeys::killSwitch, "game:killswitch"),
                new Sample("loginQueue", () -> RedisKeys.loginQueue(1), "game:login-queue:1"),
                new Sample("loginQueueAdmitted", () -> RedisKeys.loginQueueAdmitted(1), "game:login-queue-admitted:1"),
                new Sample("loginQueueAdmit", () -> RedisKeys.loginQueueAdmit("q1"), "game:login-queue-admit:q1"),
                new Sample("loginQueueAdmit", () -> RedisKeys.loginQueueAdmit(""), "game:login-queue-admit:"),
                new Sample("loginQueueMeta", () -> RedisKeys.loginQueueMeta("q1"), "game:login-queue-meta:q1"),
                new Sample("loginQueueMeta", () -> RedisKeys.loginQueueMeta(""), "game:login-queue-meta:"),
                new Sample("rateLimitZone", () -> RedisKeys.rateLimitZone(1), "game:rl:zone:1"),
                new Sample("rateLimitIp", () -> RedisKeys.rateLimitIp("1.2.3.4"), "game:rl:ip:1.2.3.4"),
                new Sample("rateLimitCooldown", () -> RedisKeys.rateLimitCooldown("login", "abc"), "game:rl:cd:login:abc"),
                new Sample("loginQueueDispatcherLock", RedisKeys::loginQueueDispatcherLock, "game:login-queue-dispatcher"),
                new Sample("gatePushTopic", () -> RedisKeys.gatePushTopic(1, 2), "game:gate-push:1:2"),
                new Sample("gainBlocks", () -> RedisKeys.gainBlocks(RedisKeys.GAIN_BLOCK_CURRENCY), "game:gain-block:currency"),
                new Sample("gainBlocks", () -> RedisKeys.gainBlocks(RedisKeys.GAIN_BLOCK_ITEM), "game:gain-block:item"),
                new Sample("gainBlockChangedTopic", RedisKeys::gainBlockChangedTopic, "game:gain-block-changed"),
                new Sample("loginAccessToken", () -> RedisKeys.loginAccessToken("tok"), "game:login:access:tok"),
                new Sample("loginRefreshToken", () -> RedisKeys.loginRefreshToken("tok"), "game:login:refresh:tok"),
                new Sample("loginAccountRefresh", () -> RedisKeys.loginAccountRefresh("acc"), "game:login:account-refresh:acc"),
                new Sample("loginDevices", () -> RedisKeys.loginDevices("acc"), "game:login:devices:acc"),
                new Sample("loginDeviceSession", () -> RedisKeys.loginDeviceSession("s1"), "game:login:device-session:s1"),
                new Sample("battleLock", () -> RedisKeys.battleLock(42), "game:battle:{42}:lock"),
                new Sample("battleSettlements", () -> RedisKeys.battleSettlements(42), "game:battle:{42}:settlement"),
                new Sample("battleSettled", () -> RedisKeys.battleSettled(42, 9), "game:battle:{42}:settled:9"),
                new Sample("battleActivityResult", () -> RedisKeys.battleActivityResult(9), "game:battle:activity-result:9"),
                new Sample("matchQueueIndex", RedisKeys::matchQueueIndex, "game:{match}:index"),
                new Sample("matchQueue", () -> RedisKeys.matchQueue(1, -1), "game:{match}:queue:1:4294967295"),
                new Sample("matchRank", () -> RedisKeys.matchRank(1, 2), "game:{match}:rank:1:2"),
                new Sample("matchQueueLock", () -> RedisKeys.matchQueueLock(1, 2), "game:{match}:lock:1:2"),
                new Sample("matchTicket", () -> RedisKeys.matchTicket(5), "game:{match}:ticket:5"),
                new Sample("matchPopMarker", () -> RedisKeys.matchPopMarker("p1"), "game:{match}:pop:p1"),
                new Sample("matchRequeueMarker", () -> RedisKeys.matchRequeueMarker("r1"), "game:{match}:requeue:r1"),
                new Sample("matchChallenge", () -> RedisKeys.matchChallenge(3), "game:{match}:challenge:3"),
                new Sample("matchChallengeTarget", () -> RedisKeys.matchChallengeTarget(5), "game:{match}:challenge-target:5"),
                new Sample("matchChallengeDone", () -> RedisKeys.matchChallengeDone(3), "game:{match}:challenge-done:3"),
                new Sample("matchBattlePlacement", () -> RedisKeys.matchBattlePlacement(9), "game:{match}:battle:9"),
                new Sample("matchWatching", () -> RedisKeys.matchWatching(5), "game:{match}:watching:5"),
                new Sample("matchWatchable", RedisKeys::matchWatchable, "game:{match}:watchable"));
    }

    /** 样例覆盖到的方法名。 */
    static Set<String> sampledMethods() {
        Set<String> names = new TreeSet<>();
        all().forEach(s -> names.add(s.method()));
        return names;
    }

    /** {@link RedisKeys} 里全部返回 String 的 public static 方法名（生成键 / 频道的方法）。 */
    static Set<String> publicStringMethods() {
        Set<String> names = new TreeSet<>();
        for (Method m : RedisKeys.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class) {
                names.add(m.getName());
            }
        }
        return names;
    }
}
