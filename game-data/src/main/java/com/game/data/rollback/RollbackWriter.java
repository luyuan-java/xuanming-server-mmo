package com.game.data.rollback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.audit.proto.AssetKind;
import com.game.audit.proto.TransactionReason;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobStore;
import com.game.data.rollback.RestoreBuilder.CurrencyChange;
import com.game.data.rollback.RestoreBuilder.ItemChange;
import com.game.data.rollback.RestoreBuilder.Restored;
import com.game.data.snapshot.PlayerSnapshotRow;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogMapper;
import com.game.data.txlog.TransactionLogRow;
import com.game.player.store.OwnerState;
import com.game.player.store.PlayerMapper;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 一个玩家的回档写事务（data-ops-spec §4.8）。<b>一个 MySQL 事务</b>里：
 * <ol>
 *   <li>加锁读归属（{@code PlayerMapper.selectOwnerForUpdate}）：不是 (E', 未释放) → 回滚，{@code fence_lost}；</li>
 *   <li>读现档原字节（作为安全快照的内容）与钉住的快照（执行前被保留期删了 → {@code snapshot_gone}）；</li>
 *   <li>安全快照：cause = PRE_ROLLBACK（1001），operator，note = {@code job:<id>}，{@code time_ms} = 现档 {@code player_state.updated_at}（内容时刻）；</li>
 *   <li>带围栏覆盖写（{@code PlayerStore.saveStateHeld}：REQUIRED 传播加入本事务；0 行 = 失去围栏，抛异常整体回滚）；</li>
 *   <li>回档流水 TX_ROLLBACK_RESTORE(16)（§4.10，D11），号取自 SCENE_GUID 雪花，{@code correlation_id} = 作业号；</li>
 *   <li>作业明细 PLANNED → RESTORED：必须恰好改到 1 行。</li>
 * </ol>
 * 四件事要么都在、要么都不在（不变量 I2；基线安全快照与覆盖写是两次调用，D4）。MyBatis 与 pbmysql 用同一条 Spring 事务绑定的连接（§7.5）。
 */
public final class RollbackWriter {

    /** 一次写的结局（RESTORED 之外都没写任何东西）。 */
    public static final String RESTORED = "RESTORED";
    public static final String FENCE_LOST = "fence_lost";
    public static final String SNAPSHOT_GONE = "snapshot_gone";
    public static final String STATE_INVALID = "state_invalid";
    /** SECTIONS 选了 assets，而快照与现档有本版本不认识、且两边不同的顶层字段（{@link RestoreBuilder.UnknownSectionsException}）。 */
    public static final String UNKNOWN_SECTIONS = "unknown_sections";
    public static final String ID_UNAVAILABLE = "id_unavailable";

    /** 写事务里抛出、让整个事务回滚的「围栏没过」。 */
    static final class FenceLostException extends RuntimeException {
        FenceLostException(String message) {
            super(message);
        }
    }

    private final PlayerStore store;
    private final PlayerMapper owners;
    private final PersistedPlayerMapper players;
    private final PlayerSnapshotMapper snapshots;
    private final TransactionLogMapper txlog;
    private final OpsJobStore jobs;
    private final OpsIds ids;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public RollbackWriter(PlayerStore store, PlayerMapper owners, PersistedPlayerMapper players,
                          PlayerSnapshotMapper snapshots, TransactionLogMapper txlog, OpsJobStore jobs, OpsIds ids,
                          TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.store = store;
        this.owners = owners;
        this.players = players;
        this.snapshots = snapshots;
        this.txlog = txlog;
        this.jobs = jobs;
        this.ids = ids;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 写一名玩家。返回结局（{@link #RESTORED} 或某个没写的原因）；访问库出错抛异常（事务已回滚，什么也没写）。
     *
     * @param snapshotId 计划时钉住的快照
     * @param epoch      我们持有的 epoch E'
     */
    public String write(long jobId, String operator, long playerId, long snapshotId, long epoch,
                        Set<RollbackSection> sections) {
        try {
            return tx.execute(status -> {
                OwnerState owner = owners.selectOwnerForUpdate(playerId);
                if (owner == null || owner.ownerEpoch() != epoch || owner.released()) {
                    status.setRollbackOnly();
                    return FENCE_LOST;
                }
                PersistedPlayer current = players.find(playerId);
                PlayerSnapshotEntry snapshot = snapshots.findById(snapshotId);
                if (current == null || snapshot == null || snapshot.getPlayerId() != playerId) {
                    status.setRollbackOnly();
                    return current == null ? FENCE_LOST : SNAPSHOT_GONE;
                }
                Restored restored;
                try {
                    restored = RestoreBuilder.build(snapshot, current, sections);
                } catch (RestoreBuilder.UnknownSectionsException e) {
                    status.setRollbackOnly();
                    return UNKNOWN_SECTIONS;
                } catch (RestoreBuilder.StateInvalidException e) {
                    status.setRollbackOnly();
                    return STATE_INVALID;
                }
                long now = clock.millis();
                OptionalLong preId = ids.tryNext();
                List<TransactionLogRow> rows = new ArrayList<>();
                if (preId.isEmpty() || !txlogRows(restored, playerId, current.getZoneId(), jobId, snapshotId,
                        preId.getAsLong(), now, rows)) {
                    status.setRollbackOnly();
                    return ID_UNAVAILABLE;
                }
                // 3 安全快照：被覆盖之前的已落盘状态（内容时刻 = player_state.updated_at）
                PlayerSnapshotRow pre = new PlayerSnapshotRow(preId.getAsLong(), playerId, current.persistedAtMs(),
                        SnapshotCauses.PRE_ROLLBACK, current.getZoneId(), current.savedEpoch(), current.getLevel(),
                        current.getSceneConfigId(), current.getPosX(), current.getPosY(), current.getPosZ(),
                        current.stateBytes());
                if (snapshots.insertDirect(pre, now, operator, "job:" + Long.toUnsignedString(jobId)) != 1) {
                    throw new IllegalStateException("安全快照插入没有恰好影响 1 行");
                }
                // 4 带围栏覆盖写（加入本事务）
                PlayerRow row = new PlayerRow();
                row.setPlayerId(playerId);
                row.setOwnerEpoch(epoch);
                row.setLevel(restored.level());
                row.setSceneConfigId(restored.sceneConfigId());
                row.setPosX(restored.posX());
                row.setPosY(restored.posY());
                row.setPosZ(restored.posZ());
                if (!store.saveStateHeld(row, restored.state())) {
                    throw new FenceLostException("带围栏覆盖写影响 0 行 player=" + Long.toUnsignedString(playerId));
                }
                // 5 回档流水
                if (!rows.isEmpty() && txlog.insertDirectAll(rows, now) != rows.size()) {
                    throw new IllegalStateException("回档流水插入行数不符");
                }
                // 6 明细：恰好 1 行
                Map<String, Object> detail = new LinkedHashMap<>(restored.detail());
                detail.put("preSnapshotId", Long.toUnsignedString(preId.getAsLong()));
                detail.put("txlogRows", rows.size());
                if (!jobs.finishPlayer(jobId, playerId, RESTORED, epoch, preId.getAsLong(), toJson(detail), now)) {
                    throw new IllegalStateException("作业明细 PLANNED → RESTORED 没有恰好改到 1 行");
                }
                return RESTORED;
            });
        } catch (FenceLostException e) {
            return FENCE_LOST;
        }
    }

    /** 回档流水（§4.10）：每个余额有变化的币种一行、每个数量有变化的物品实例一行；宝宝没有流水种类，只进明细摘要。号发不出返回 false。 */
    private boolean txlogRows(Restored r, long playerId, int zoneId, long jobId, long snapshotId, long preId, long now,
                              List<TransactionLogRow> out) {
        if (!r.assetsRestored() || !r.currentValid()) {
            return true;
        }
        String extra = "{\"job\":\"" + Long.toUnsignedString(jobId) + "\",\"snapshot\":\"" + Long.toUnsignedString(snapshotId)
                + "\",\"pre\":\"" + Long.toUnsignedString(preId) + "\"}";
        for (CurrencyChange c : r.currency()) {
            OptionalLong txId = ids.tryNext();
            if (txId.isEmpty()) {
                return false;
            }
            long delta = signedDelta(c.before(), c.after());
            out.add(new TransactionLogRow(txId.getAsLong(), now, TransactionReason.TX_ROLLBACK_RESTORE_VALUE,
                    AssetKind.ASSET_CURRENCY_VALUE, delta < 0 ? playerId : 0, delta > 0 ? playerId : 0,
                    c.currencyType(), delta, c.before(), c.after(), 0, 0, 0, jobId, extra, zoneId));
        }
        for (ItemChange i : r.items()) {
            OptionalLong txId = ids.tryNext();
            if (txId.isEmpty()) {
                return false;
            }
            boolean gained = i.after() > i.before();
            long qty = gained ? i.after() - i.before() : i.before() - i.after();
            out.add(new TransactionLogRow(txId.getAsLong(), now, TransactionReason.TX_ROLLBACK_RESTORE_VALUE,
                    AssetKind.ASSET_ITEM_VALUE, gained ? 0 : playerId, gained ? playerId : 0, 0, 0, 0, 0, i.itemUuid(),
                    i.configId(), (int) qty, jobId, extra, zoneId));
        }
        return true;
    }

    /** after − before（两者都是 uint64 的位模式），夹到 int64 范围。 */
    static long signedDelta(long before, long after) {
        BigInteger d = new BigInteger(Long.toUnsignedString(after)).subtract(new BigInteger(Long.toUnsignedString(before)));
        if (d.bitLength() > 63) {
            return d.signum() > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return d.longValue();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
