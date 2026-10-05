package com.game.scenemanager.world;

import com.game.api.proto.WorldChannel;
import com.game.discovery.world.ReservationCandidate;
import com.game.discovery.world.ReservationPick;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlan;
import com.game.discovery.world.WorldPlanBatch;
import com.game.discovery.world.WorldPlanOp;
import com.game.discovery.world.WorldPlanSnapshot;
import com.game.discovery.world.WorldPlanWriteResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 内存版 {@link WorldChannelStore}（单 zone 语义按 zone 分开存）：写入同 Lua 的「令牌 + 版本号 CAS、整批或不写」，
 * 预占同 Lua 的「负载 = 目录人数 + 别人的未到期预占，严格最小，撤掉本玩家在别处的预占」。时间 {@link #nowMs} 由测试推进。
 * 可注入故障与强制写入结局。线程安全（synchronized），够单测用。
 */
public final class FakeWorldChannelStore implements WorldChannelStore {

    public static final class Zone {
        public long version;
        public final TreeMap<Long, WorldChannel> channels = new TreeMap<>(Long::compareUnsigned);
        public final TreeMap<Integer, Integer> desired = new TreeMap<>(Integer::compareUnsigned);
        public final TreeMap<Integer, Long> cooldown = new TreeMap<>(Integer::compareUnsigned);
        public String leader;
        /** scene_id → (player_id → 到期毫秒)。 */
        public final Map<Long, Map<Long, Long>> reservations = new HashMap<>();
    }

    public long nowMs = 1_800_000_000_000L;
    public final Map<Integer, Zone> zones = new TreeMap<>(Integer::compareUnsigned);
    public final TreeSet<Integer> registered = new TreeSet<>(Integer::compareUnsigned);
    public final List<WorldPlanBatch> writes = new ArrayList<>();
    public RuntimeException snapshotFailure;
    public RuntimeException reserveFailure;
    public RuntimeException zonesFailure;
    public RuntimeException writeFailure;
    /** 下一次写入不执行、直接回这个结局（用一次就清掉）。 */
    public WorldPlanWriteResult forcedWriteResult;
    public int snapshots;

    public synchronized Zone zone(int zoneId) {
        return zones.computeIfAbsent(zoneId, z -> new Zone());
    }

    @Override
    public synchronized long planVersion(int zoneId) {
        return zone(zoneId).version;
    }

    @Override
    public synchronized WorldPlan readPlan(int zoneId) {
        Zone z = zone(zoneId);
        return new WorldPlan(z.version, new ArrayList<>(z.channels.values()));
    }

    @Override
    public synchronized WorldPlanSnapshot snapshot(int zoneId) {
        snapshots++;
        if (snapshotFailure != null) {
            throw snapshotFailure;
        }
        Zone z = zone(zoneId);
        return new WorldPlanSnapshot(z.version, nowMs, z.channels, z.desired, z.cooldown);
    }

    @Override
    public synchronized WorldPlanWriteResult write(int zoneId, String leaderToken, WorldPlanBatch batch) {
        writes.add(batch);
        if (writeFailure != null) {
            throw writeFailure;
        }
        if (forcedWriteResult != null) {
            WorldPlanWriteResult forced = forcedWriteResult;
            forcedWriteResult = null;
            return forced;
        }
        Zone z = zone(zoneId);
        if (!leaderToken.equals(z.leader)) {
            return WorldPlanWriteResult.FENCED;
        }
        if (z.version != batch.expectedVersion()) {
            return WorldPlanWriteResult.CONFLICT;
        }
        for (WorldPlanOp op : batch.ops()) {
            switch (op) {
                case WorldPlanOp.PutChannel put -> z.channels.put(put.channel().getSceneId(), put.channel());
                case WorldPlanOp.RemoveChannel remove -> z.channels.remove(remove.sceneId());
                case WorldPlanOp.SetDesired set -> z.desired.put(set.sceneConfigId(), set.count());
                case WorldPlanOp.SeedDesired seed -> z.desired.putIfAbsent(seed.sceneConfigId(), seed.count());
                case WorldPlanOp.RemoveDesired remove -> z.desired.remove(remove.sceneConfigId());
                case WorldPlanOp.SetCooldown set -> z.cooldown.put(set.sceneConfigId(), set.untilMs());
                case WorldPlanOp.RemoveCooldown remove -> z.cooldown.remove(remove.sceneConfigId());
            }
        }
        z.version = batch.writtenVersion();
        return WorldPlanWriteResult.written(z.version);
    }

    @Override
    public synchronized boolean tryAcquireLeader(int zoneId, String token, Duration ttl) {
        Zone z = zone(zoneId);
        if (z.leader == null || z.leader.equals(token)) {
            z.leader = token;
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean renewLeader(int zoneId, String token, Duration ttl) {
        return token.equals(zone(zoneId).leader);
    }

    @Override
    public synchronized boolean releaseLeader(int zoneId, String token) {
        Zone z = zone(zoneId);
        if (token.equals(z.leader)) {
            z.leader = null;
            return true;
        }
        return false;
    }

    @Override
    public synchronized ReservationPick reserve(int zoneId, List<ReservationCandidate> candidates, long playerId,
                                                Duration ttl) {
        if (reserveFailure != null) {
            throw reserveFailure;
        }
        Zone z = zone(zoneId);
        int best = -1;
        long load = 0;
        for (int i = 0; i < candidates.size(); i++) {
            Map<Long, Long> resv = live(z, candidates.get(i).sceneId());
            long l = Integer.toUnsignedLong(candidates.get(i).directoryPlayers()) + resv.size()
                    - (resv.containsKey(playerId) ? 1 : 0);
            if (best < 0 || l < load) {
                best = i;
                load = l;
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (i != best) {
                live(z, candidates.get(i).sceneId()).remove(playerId);
            }
        }
        live(z, candidates.get(best).sceneId()).put(playerId, nowMs + ttl.toMillis());
        return new ReservationPick(best, load);
    }

    @Override
    public synchronized void reserveScene(int zoneId, long sceneId, long playerId, Duration ttl) {
        if (reserveFailure != null) {
            throw reserveFailure;
        }
        live(zone(zoneId), sceneId).put(playerId, nowMs + ttl.toMillis());
    }

    @Override
    public synchronized boolean releaseReservation(int zoneId, long sceneId, long playerId) {
        return live(zone(zoneId), sceneId).remove(playerId) != null;
    }

    @Override
    public synchronized List<Long> countReservations(int zoneId, List<Long> sceneIds) {
        List<Long> out = new ArrayList<>();
        for (long sceneId : sceneIds) {
            out.add((long) live(zone(zoneId), sceneId).size());
        }
        return out;
    }

    @Override
    public synchronized void registerZone(int zoneId) {
        registered.add(zoneId);
    }

    @Override
    public synchronized List<Integer> zones() {
        if (zonesFailure != null) {
            throw zonesFailure;
        }
        return new ArrayList<>(registered);
    }

    /** 某场景未到期的预占（顺手清掉到期的）。 */
    private Map<Long, Long> live(Zone z, long sceneId) {
        Map<Long, Long> resv = z.reservations.computeIfAbsent(sceneId, s -> new LinkedHashMap<>());
        resv.values().removeIf(until -> until <= nowMs);
        return resv;
    }

    public synchronized int reservationCount(int zoneId, long sceneId) {
        return live(zone(zoneId), sceneId).size();
    }
}
