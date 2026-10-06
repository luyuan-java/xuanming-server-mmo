package com.game.battle.testing;

import com.game.battle.outbox.SettlementOutbox;
import com.game.discovery.battle.BattleRedis;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 结算发件箱的假 Redis 端口（scene-battle-spec §13.5）：带一个内存模型，照 §7.2 的三段脚本推进——
 * <ul>
 *   <li>{@code STORE_SETTLEMENT}：这一局的已销账墓碑在 → 不写、回 {@link BattleRedis#STORE_ALREADY_SETTLED}；否则写字段、回该玩家的字段数；</li>
 *   <li>探测：这一局的字段在不在；</li>
 *   <li>{@code ACK_IF_SUPERSEDED}：字段不在 → 3；锁不在 → 0；锁是本局 → 1；锁被别的局持有 → 删字段、留墓碑、回 2。</li>
 * </ul>
 * scene 一侧的动作由测试直接推进模型：{@link #sceneAck}（销账：删字段、放本局的锁、<b>无条件留墓碑</b>）、{@link #lock}。
 * 每类调用的完成时机与方式由 {@link #stores} / {@link #probes} / {@link #supersedes} 编排（见 {@link Scripted}）；全部调用进 {@link CallJournal}，
 * 落库的结局另记一条 {@code store-done:…}（它一定排在调用方看到结局之前）。线程安全。
 */
public final class FakeSettlementStore implements SettlementOutbox.Store {

    public final Scripted<Long> stores = new Scripted<>();
    public final Scripted<Boolean> probes = new Scripted<>();
    public final Scripted<Long> supersedes = new Scripted<>();

    private final CallJournal journal;
    /** player → (battle → 落库的字节)。 */
    private final Map<Long, Map<Long, byte[]>> records = new HashMap<>();
    /** 已销账墓碑：{@code <pid>/<bid>}。 */
    private final Set<String> tombstones = new HashSet<>();
    /** player → 持锁的 battle。 */
    private final Map<Long, Long> locks = new HashMap<>();
    /** 每次落库调用传入的字节（不看模型写没写进去）：{@code <pid>/<bid>} → 最近一次。 */
    private final Map<String, byte[]> offered = new HashMap<>();

    public FakeSettlementStore(CallJournal journal) {
        this.journal = journal;
    }

    /** {@code <pid>/<bid>}（无符号十进制），日志条目与模型键共用。 */
    public static String key(long playerId, long battleId) {
        return Long.toUnsignedString(playerId) + "/" + Long.toUnsignedString(battleId);
    }

    // ------------------------------------------------------------------ 端口

    @Override
    public CompletableFuture<Long> store(long playerId, long battleId, byte[] payload) {
        String key = key(playerId, battleId);
        byte[] copy = payload.clone();
        synchronized (this) {
            offered.put(key, copy);
        }
        journal.add("store:" + key);
        CompletableFuture<Long> future = stores.invoke("store:" + key, () -> applyStore(playerId, battleId, copy));
        return future == null ? null
                : future.whenComplete((fields, error) -> journal.add("store-done:" + key + "=" + (error != null ? "error" : fields)));
    }

    @Override
    public CompletableFuture<Boolean> exists(long playerId, long battleId) {
        String key = key(playerId, battleId);
        journal.add("exists:" + key);
        return probes.invoke("exists:" + key, () -> hasRecord(playerId, battleId));
    }

    @Override
    public CompletableFuture<Long> ackIfSuperseded(long playerId, long battleId) {
        String key = key(playerId, battleId);
        journal.add("superseded?:" + key);
        return supersedes.invoke("superseded?:" + key, () -> applyAckIfSuperseded(playerId, battleId));
    }

    // ------------------------------------------------------------------ 模型（§7.2）

    /** {@code STORE_SETTLEMENT} 落地：返回值同脚本。测试也可以直接调它，模拟一条结局不明的落库<b>这时才落地</b>。 */
    public synchronized long applyStore(long playerId, long battleId, byte[] payload) {
        if (tombstones.contains(key(playerId, battleId))) {
            return BattleRedis.STORE_ALREADY_SETTLED;
        }
        Map<Long, byte[]> fields = records.computeIfAbsent(playerId, p -> new LinkedHashMap<>());
        fields.put(battleId, payload.clone());
        return fields.size();
    }

    private synchronized long applyAckIfSuperseded(long playerId, long battleId) {
        if (!hasRecord(playerId, battleId)) {
            return 3;
        }
        Long holder = locks.get(playerId);
        if (holder == null) {
            return 0;
        }
        if (holder == battleId) {
            return 1;
        }
        records.get(playerId).remove(battleId);
        tombstones.add(key(playerId, battleId));
        return 2;
    }

    /** scene 的 {@code ACK}：删本局字段（位 1）、放本局的锁（位 2）、无条件留墓碑。返回位掩码。 */
    public synchronized long sceneAck(long playerId, long battleId) {
        long bits = 0;
        Map<Long, byte[]> fields = records.get(playerId);
        if (fields != null && fields.remove(battleId) != null) {
            bits |= 1;
        }
        Long holder = locks.get(playerId);
        if (holder != null && holder == battleId) {
            locks.remove(playerId);
            bits |= 2;
        }
        tombstones.add(key(playerId, battleId));
        return bits;
    }

    /** 玩家的战斗锁现在被 {@code battleId} 这一局持有。 */
    public synchronized void lock(long playerId, long battleId) {
        locks.put(playerId, battleId);
    }

    public synchronized boolean hasRecord(long playerId, long battleId) {
        Map<Long, byte[]> fields = records.get(playerId);
        return fields != null && fields.containsKey(battleId);
    }

    /** 模型里这一局的记录字节；没有为 null。 */
    public synchronized byte[] record(long playerId, long battleId) {
        Map<Long, byte[]> fields = records.get(playerId);
        byte[] bytes = fields == null ? null : fields.get(battleId);
        return bytes == null ? null : bytes.clone();
    }

    /** 模型里该玩家的待结算字段数。 */
    public synchronized int fieldCount(long playerId) {
        Map<Long, byte[]> fields = records.get(playerId);
        return fields == null ? 0 : fields.size();
    }

    public synchronized boolean hasTombstone(long playerId, long battleId) {
        return tombstones.contains(key(playerId, battleId));
    }

    /** 最近一次落库调用传入的字节（不看有没有写进模型）；没调过为 null。 */
    public synchronized byte[] offered(long playerId, long battleId) {
        byte[] bytes = offered.get(key(playerId, battleId));
        return bytes == null ? null : bytes.clone();
    }
}
