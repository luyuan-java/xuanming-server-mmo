package com.game.data.snapshot;

import com.game.audit.proto.AssetKind;
import com.game.audit.proto.TransactionReason;
import com.game.data.ops.OpsException;
import com.game.data.query.AuditViews;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogQuery;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.CurrencyDebtState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.http.HttpStatus;

/**
 * 结构化快照差异（data-ops-spec §3.6；对应 98 / 116，也是以后回档 dry-run 的主体）：某玩家的一份快照 vs <b>已落盘</b>的当前状态。
 *
 * <ul>
 *   <li><b>选快照</b>：按号时必须属于该玩家，否则 404 {@code snapshot_not_found}（同基线 resolveSnapshot）；按时刻取 {@code atMs}（含）之前最近的一份，
 *       原因白名单 {@link SnapshotCauses#POINT_IN_TIME_SOURCES}（不含安全快照）。</li>
 *   <li><b>「当前」= 已落盘状态</b>（D19）：应答带 {@code online}（归属是否被持有）、{@code persistedAtMs}、{@code ownerReleased}。</li>
 *   <li>输出：行级（等级 / 场景 / 坐标）、货币（逐币种 + 欠款 + 封禁币种）、物品（按配置聚合 + 逐实例）、宝宝（按 pet_id 增减）、
 *       其余段（facing / attribute / mission / vitals 与根上的未知字段，{@link StateDiff} 路径级、至多 500 条）、
 *       转移证据（只作提示，不自动裁剪回档内容，D9）、账本差集（{@link LedgerDiff}）。</li>
 *   <li><b>证据完整性</b>：流水有保留期（{@code xm.data.retention.transaction-log}），GM / 安全快照缺省永久保留，快照可能比证据活得久。
 *       快照时刻早于「现在 − 流水保留期」时，快照之后的那一段流水可能已被清掉——查不到流水不能当「没转移」：
 *       顶层 {@code evidenceComplete=false}，只认正面证据（仍能看到的转移照样判 {@code restorable=false}），其余一律
 *       {@code restorable=null}（未知），物品证据记 {@code INCOMPLETE}。</li>
 * </ul>
 * 只读，线程安全。
 */
public final class SnapshotDiffService {

    /** 转移证据只查这么多个「只在快照里」的物品实例（每个一次 uuid 索引查询）。 */
    static final int MAX_EVIDENCE_ITEMS = 200;
    /** 每个实例 / 币种至多看这么多条之后的流水。 */
    static final int EVIDENCE_ROWS = 20;
    /** 转移类原因（交易、邮件、拍卖、帮会捐献 / 商店 / 活动奖励，data-ops-spec §3.6 第 6 项）。 */
    static final List<Integer> TRANSFER_REASONS = List.of(TransactionReason.TX_TRADE_VALUE,
            TransactionReason.TX_MAIL_ATTACHMENT_VALUE, TransactionReason.TX_AUCTION_SELL_VALUE,
            TransactionReason.TX_AUCTION_BUY_VALUE, TransactionReason.TX_GUILD_DONATE_VALUE,
            TransactionReason.TX_GUILD_SHOP_VALUE, TransactionReason.TX_GUILD_ACTIVITY_REWARD_VALUE);

    private final PersistedPlayerMapper players;
    private final PlayerSnapshotMapper snapshots;
    private final TransactionLogQueryService txlog;
    private final Clock clock;
    /** 资产流水保留期（{@code xm.data.retention.transaction-log}）；0 = 永久保留，证据永远完整。 */
    private final Duration transactionLogRetention;

    public SnapshotDiffService(PersistedPlayerMapper players, PlayerSnapshotMapper snapshots,
                               TransactionLogQueryService txlog, Clock clock, Duration transactionLogRetention) {
        if (transactionLogRetention == null || transactionLogRetention.isNegative()) {
            throw new IllegalArgumentException("transactionLogRetention 非法：" + transactionLogRetention);
        }
        this.players = players;
        this.snapshots = snapshots;
        this.txlog = txlog;
        this.clock = clock;
        this.transactionLogRetention = transactionLogRetention;
    }

    /** 快照号与时刻二选一。 */
    public Map<String, Object> diff(long playerId, Long snapshotId, Long atMs) {
        if ((snapshotId == null) == (atMs == null)) {
            throw OpsException.badRequest("snapshot 与 atMs 二选一");
        }
        PlayerSnapshotEntry snapshot = resolve(playerId, snapshotId, atMs);
        PersistedPlayer current = players.find(playerId);
        if (current == null) {
            throw new OpsException(HttpStatus.NOT_FOUND, OpsException.PLAYER_NOT_FOUND,
                    "玩家不存在：" + Long.toUnsignedString(playerId));
        }
        PlayerState s = parse(snapshot.getPlayerState(), "snapshot");
        PlayerState c = parse(current.stateBytes(), "current");
        long now = clock.millis();
        long evidenceFloorMs = evidenceFloorMs(now, transactionLogRetention);
        boolean evidenceComplete = transactionLogRetention.isZero() || snapshot.getTimeMs() >= evidenceFloorMs;

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> snapshotView = AuditViews.snapshotMeta(snapshot);
        snapshotView.put("stateBytes", snapshot.getPlayerState().length);
        out.put("snapshot", snapshotView);
        Map<String, Object> currentView = new LinkedHashMap<>();
        currentView.put("persistedAtMs", current.persistedAtMs());
        currentView.put("savedEpoch", Long.toUnsignedString(current.savedEpoch()));
        currentView.put("hasState", current.hasState());
        currentView.put("ownerReleased", current.getOwnerReleased() != 0);
        currentView.put("online", current.ownerHeld(now));
        currentView.put("zoneId", Integer.toUnsignedLong(current.getZoneId()));
        out.put("current", currentView);
        out.put("evidenceComplete", evidenceComplete);
        out.put("evidenceFloorMs", evidenceFloorMs);
        out.put("row", rowDiff(snapshot, current));
        out.put("currency", currencyDiff(playerId, snapshot.getTimeMs(), evidenceComplete, s.getCurrency(),
                c.getCurrency()));
        out.put("items", itemDiff(playerId, snapshot.getTimeMs(), evidenceComplete, s, c));
        out.put("pets", petDiff(s, c));
        out.put("sections", sectionDiff(s, c));
        out.put("ledger", ledgerView(LedgerDiff.compare(s, c)));
        String caveat = "转移证据只作提示、不自动裁剪回档内容；入包流水的 itemUuid 是写到的第一个实例，堆叠物品的证据只是近似；"
                + "「当前」是已落盘状态，online=true 时可能落后内存至多一个存盘周期";
        if (!evidenceComplete) {
            caveat += "；资产流水保留期 " + transactionLogRetention + "，早于 evidenceFloorMs=" + evidenceFloorMs
                    + " 的流水可能已被清理，快照（timeMs=" + snapshot.getTimeMs() + "）之后的转移证据不完整："
                    + "查不到转移不代表没转移，restorable=null 表示未知、物品证据记 INCOMPLETE";
        }
        out.put("caveat", caveat);
        return out;
    }

    /**
     * 转移证据的下界：早于它的流水可能已被保留期清理（{@code DataNode.purgeExpired} 按 {@code now − 保留期} 删）。
     * 保留期为 0（永久）时为 0——任何快照的证据都完整。
     */
    static long evidenceFloorMs(long nowMs, Duration transactionLogRetention) {
        return transactionLogRetention.isZero() ? 0 : nowMs - transactionLogRetention.toMillis();
    }

    private PlayerSnapshotEntry resolve(long playerId, Long snapshotId, Long atMs) {
        if (snapshotId != null) {
            PlayerSnapshotEntry e = snapshots.findById(snapshotId);
            if (e == null || e.getPlayerId() != playerId) {
                // 属于别的玩家当作找不到（同基线 snapshot_logic.go:271-289）
                throw new OpsException(HttpStatus.NOT_FOUND, OpsException.SNAPSHOT_NOT_FOUND,
                        "该玩家没有快照 " + Long.toUnsignedString(snapshotId));
            }
            return e;
        }
        PlayerSnapshotEntry meta = snapshots.findLatestAtOrBefore(playerId, atMs, SnapshotCauses.POINT_IN_TIME_SOURCES);
        PlayerSnapshotEntry e = meta == null ? null : snapshots.findById(meta.getSnapshotId());
        if (e == null) {
            throw new OpsException(HttpStatus.NOT_FOUND, OpsException.SNAPSHOT_NOT_FOUND,
                    "该玩家在 " + atMs + " 之前没有可用作时间点的快照（不含安全快照）");
        }
        return e;
    }

    private static PlayerState parse(byte[] data, String side) {
        try {
            return PlayerState.parseFrom(data == null ? new byte[0] : data);
        } catch (InvalidProtocolBufferException e) {
            throw new OpsException(HttpStatus.UNPROCESSABLE_ENTITY, OpsException.STATE_INVALID,
                    ("snapshot".equals(side) ? "快照" : "当前") + "的 player_state 解析失败：" + e.getMessage(),
                    Map.of("side", side), e);
        }
    }

    // ------------------------------------------------------------------ 行级

    private static Map<String, Object> rowDiff(PlayerSnapshotEntry s, PersistedPlayer c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", pair(Integer.toUnsignedLong(s.getLevel()), Integer.toUnsignedLong(c.getLevel())));
        out.put("sceneConfigId", pair(Integer.toUnsignedLong(s.getSceneConfigId()),
                Integer.toUnsignedLong(c.getSceneConfigId())));
        out.put("position", pair(List.of(s.getPosX(), s.getPosY(), s.getPosZ()),
                List.of(c.getPosX(), c.getPosY(), c.getPosZ())));
        return out;
    }

    private static Map<String, Object> pair(Object snapshot, Object current) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshot);
        out.put("current", current);
        out.put("changed", !snapshot.equals(current));
        return out;
    }

    // ------------------------------------------------------------------ 货币

    /**
     * 货币差异。delta 非 0 的币种带转移证据：看到转移 → {@code transferAfterSnapshot=true, restorable=false}；没看到且证据完整 →
     * {@code false / true}；没看到但证据不完整（{@code evidenceComplete=false}）→ 两者都是 {@code null}（未知，不当「没转移」）。
     */
    private Map<String, Object> currencyDiff(long playerId, long sinceMs, boolean evidenceComplete, CurrencyState s,
                                             CurrencyState c) {
        List<Map<String, Object>> balances = new ArrayList<>();
        int types = Math.max(s.getBalancesCount(), c.getBalancesCount());
        for (int type = 0; type < types; type++) {
            long sv = type < s.getBalancesCount() ? s.getBalances(type) : 0;
            long cv = type < c.getBalancesCount() ? c.getBalances(type) : 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("currencyType", type);
            row.put("snapshot", Long.toUnsignedString(sv));
            row.put("current", Long.toUnsignedString(cv));
            BigInteger delta = unsigned(cv).subtract(unsigned(sv));
            row.put("delta", delta.toString());
            if (delta.signum() != 0) {
                List<TransactionLogEntry> transfers = currencyTransfers(playerId, type, sinceMs);
                boolean seen = !transfers.isEmpty();
                row.put("transferAfterSnapshot", seen ? Boolean.TRUE : evidenceComplete ? Boolean.FALSE : null);
                row.put("transferTxIds", transfers.stream().map(e -> Long.toUnsignedString(e.getTxId())).toList());
                row.put("restorable", seen ? Boolean.FALSE : evidenceComplete ? Boolean.TRUE : null);
            }
            balances.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balances", balances);
        out.put("blockedTypes", pair(s.getBlockedTypesList(), c.getBlockedTypesList()));
        out.put("debts", debtDiff(s, c));
        return out;
    }

    /** 快照之后该玩家该币种的转移类流水（扣减方或获得方），至多 {@link #EVIDENCE_ROWS} 条。 */
    private List<TransactionLogEntry> currencyTransfers(long playerId, int currencyType, long sinceMs) {
        TransactionLogQuery base = TransactionLogQuery.builder()
                .kind(AssetKind.ASSET_CURRENCY_VALUE)
                .currencyType(currencyType)
                .reasons(TRANSFER_REASONS)
                .window(sinceMs, Long.MAX_VALUE)
                .fetch(EVIDENCE_ROWS)
                .build();
        return txlog.byPlayer(base, playerId);
    }

    private static List<Map<String, Object>> debtDiff(CurrencyState s, CurrencyState c) {
        Map<Integer, CurrencyDebtState> sd = new TreeMap<>();
        s.getDebtsList().forEach(d -> sd.put(d.getCurrencyType(), d));
        Map<Integer, CurrencyDebtState> cd = new TreeMap<>();
        c.getDebtsList().forEach(d -> cd.put(d.getCurrencyType(), d));
        TreeSet<Integer> types = new TreeSet<>(sd.keySet());
        types.addAll(cd.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int type : types) {
            CurrencyDebtState a = sd.get(type);
            CurrencyDebtState b = cd.get(type);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("currencyType", Integer.toUnsignedLong(type));
            row.put("snapshot", a == null ? null : debtView(a));
            row.put("current", b == null ? null : debtView(b));
            row.put("changed", a == null || !a.equals(b));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> debtView(CurrencyDebtState d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owed", Long.toUnsignedString(d.getOwed()));
        out.put("paid", Long.toUnsignedString(d.getPaid()));
        out.put("frozen", d.getFrozen());
        out.put("expiresAt", Long.toUnsignedString(d.getExpiresAt()));
        out.put("reason", d.getReason());
        out.put("gmOperator", d.getGmOperator());
        return out;
    }

    // ------------------------------------------------------------------ 物品

    private Map<String, Object> itemDiff(long playerId, long sinceMs, boolean evidenceComplete, PlayerState s,
                                         PlayerState c) {
        Map<Long, BagItemState> si = byUuid(s.getBag().getItemsList(), BagItemState::getItemUuid);
        Map<Long, BagItemState> ci = byUuid(c.getBag().getItemsList(), BagItemState::getItemUuid);

        Map<Long, long[]> byConfig = new TreeMap<>();
        si.values().forEach(i -> byConfig.computeIfAbsent(Integer.toUnsignedLong(i.getConfigId()), k -> new long[2])[0]
                += Integer.toUnsignedLong(i.getStackSize()));
        ci.values().forEach(i -> byConfig.computeIfAbsent(Integer.toUnsignedLong(i.getConfigId()), k -> new long[2])[1]
                += Integer.toUnsignedLong(i.getStackSize()));
        List<Map<String, Object>> configs = new ArrayList<>();
        byConfig.forEach((config, q) -> {
            if (q[0] != q[1]) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("configId", config);
                row.put("snapshot", q[0]);
                row.put("current", q[1]);
                row.put("delta", q[1] - q[0]);
                configs.add(row);
            }
        });

        List<Map<String, Object>> onlyInSnapshot = new ArrayList<>();
        List<Map<String, Object>> onlyInCurrent = new ArrayList<>();
        List<Map<String, Object>> stackChanged = new ArrayList<>();
        int evidenceChecked = 0;
        boolean evidenceTruncated = false;
        for (BagItemState item : si.values()) {
            BagItemState now = ci.get(item.getItemUuid());
            if (now == null) {
                Map<String, Object> row = itemView(item);
                if (evidenceChecked < MAX_EVIDENCE_ITEMS) {
                    evidenceChecked++;
                    Evidence ev = itemEvidence(playerId, item.getItemUuid(), sinceMs);
                    boolean transferred = TRANSFERRED.equals(ev.kind());
                    // 证据不完整时只认正面证据：看到转移仍判 TRANSFERRED；销毁 / 合并 / 无记录都可能掩盖着已清掉的转移，记 INCOMPLETE
                    row.put("evidence", transferred || evidenceComplete ? ev.kind() : INCOMPLETE);
                    row.put("evidenceTxIds", ev.txIds());
                    row.put("restorable", transferred ? Boolean.FALSE : evidenceComplete ? Boolean.TRUE : null);
                } else {
                    evidenceTruncated = true;
                }
                onlyInSnapshot.add(row);
            } else if (now.getStackSize() != item.getStackSize()) {
                Map<String, Object> row = itemView(now);
                row.put("snapshotStack", Integer.toUnsignedLong(item.getStackSize()));
                row.put("currentStack", Integer.toUnsignedLong(now.getStackSize()));
                row.put("delta", Integer.toUnsignedLong(now.getStackSize()) - Integer.toUnsignedLong(item.getStackSize()));
                stackChanged.add(row);
            }
        }
        for (BagItemState item : ci.values()) {
            if (!si.containsKey(item.getItemUuid())) {
                onlyInCurrent.add(itemView(item));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byConfig", configs);
        out.put("onlyInSnapshot", onlyInSnapshot);
        out.put("onlyInCurrent", onlyInCurrent);
        out.put("stackChanged", stackChanged);
        out.put("evidenceTruncated", evidenceTruncated);
        out.put("capacities", pair(s.getBag().getCapacitiesList(), c.getBag().getCapacitiesList()));
        return out;
    }

    static final String TRANSFERRED = "TRANSFERRED";
    static final String DESTROYED = "DESTROYED";
    static final String MERGED = "MERGED";
    static final String NO_RECORD = "NO_RECORD";
    /** 快照早于流水保留期下界、又没看到转移：查到的（或没查到的）流水不足以下结论。 */
    static final String INCOMPLETE = "INCOMPLETE";

    /**
     * 一个实例的转移证据：DESTROYED（被销毁 / 扣除，无接收方）/ MERGED（整理时并掉的空实例）/ TRANSFERRED（转给了别人）/ NO_RECORD；
     * 证据不完整时由调用方把非 TRANSFERRED 改记 INCOMPLETE。
     */
    record Evidence(String kind, List<String> txIds) {
    }

    Evidence itemEvidence(long playerId, long itemUuid, long sinceMs) {
        List<TransactionLogEntry> rows = txlog.scan(TransactionLogQuery.builder()
                .itemUuid(itemUuid)
                .window(sinceMs, Long.MAX_VALUE)
                .fetch(EVIDENCE_ROWS)
                .build(), EVIDENCE_ROWS - 1);
        String kind = NO_RECORD;
        List<String> txIds = new ArrayList<>();
        for (TransactionLogEntry e : rows) {
            if (e.getFromPlayer() != playerId) {
                continue;
            }
            txIds.add(Long.toUnsignedString(e.getTxId()));
            if (e.getToPlayer() != 0 && e.getToPlayer() != playerId) {
                kind = TRANSFERRED;
            } else if (!TRANSFERRED.equals(kind)) {
                kind = e.getReason() == TransactionReason.TX_ITEM_DESTROY_VALUE && e.getItemQuantity() == 0
                        ? MERGED : DESTROYED;
            }
        }
        return new Evidence(kind, txIds);
    }

    private static Map<String, Object> itemView(BagItemState i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemUuid", Long.toUnsignedString(i.getItemUuid()));
        out.put("configId", Integer.toUnsignedLong(i.getConfigId()));
        out.put("bag", Integer.toUnsignedLong(i.getBagType()));
        out.put("pos", Integer.toUnsignedLong(i.getPos()));
        out.put("stack", Integer.toUnsignedLong(i.getStackSize()));
        return out;
    }

    // ------------------------------------------------------------------ 宝宝

    private static Map<String, Object> petDiff(PlayerState s, PlayerState c) {
        Map<Long, PetEntry> sp = byUuid(s.getPets().getPetsList(), PetEntry::getPetId);
        Map<Long, PetEntry> cp = byUuid(c.getPets().getPetsList(), PetEntry::getPetId);
        List<Map<String, Object>> onlyInSnapshot = new ArrayList<>();
        List<Map<String, Object>> onlyInCurrent = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        sp.forEach((id, pet) -> {
            PetEntry now = cp.get(id);
            if (now == null) {
                onlyInSnapshot.add(petView(pet));
            } else if (!now.equals(pet)) {
                changed.add(Long.toUnsignedString(id));
            }
        });
        cp.forEach((id, pet) -> {
            if (!sp.containsKey(id)) {
                onlyInCurrent.add(petView(pet));
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onlyInSnapshot", onlyInSnapshot);
        out.put("onlyInCurrent", onlyInCurrent);
        out.put("changed", changed);
        out.put("activePetId", pair(Long.toUnsignedString(s.getPets().getActivePetId()),
                Long.toUnsignedString(c.getPets().getActivePetId())));
        return out;
    }

    private static Map<String, Object> petView(PetEntry p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("petId", Long.toUnsignedString(p.getPetId()));
        out.put("petTableId", Integer.toUnsignedLong(p.getPetTableId()));
        out.put("name", p.getName());
        out.put("level", Integer.toUnsignedLong(p.getLevel()));
        return out;
    }

    // ------------------------------------------------------------------ 其余段

    /**
     * 资产组（货币 / 背包 / 宝宝 / 账本）各有专门的差异（上面几节）；{@code PlayerState} 的其余字段——现在是 facing / attribute / mission /
     * vitals，以及以后新加的玩法段——一律按描述符逐个走路径级比较。按描述符遍历而不是写死段名：新加的段不会在差异里悄悄缺席。
     */
    static final Set<Integer> ASSET_GROUP_FIELDS = Set.of(PlayerState.CURRENCY_FIELD_NUMBER, PlayerState.BAG_FIELD_NUMBER,
            PlayerState.PETS_FIELD_NUMBER, PlayerState.ASSET_LEDGER_FIELD_NUMBER);

    static Map<String, Object> sectionDiff(PlayerState s, PlayerState c) {
        Map<String, Object> out = new LinkedHashMap<>();
        StateDiff diff = new StateDiff();
        for (FieldDescriptor fd : PlayerState.getDescriptor().getFields()) {
            if (!ASSET_GROUP_FIELDS.contains(fd.getNumber())) {
                section(out, diff, fd, s, c);
            }
        }
        UnknownFieldSet su = s.getUnknownFields();
        UnknownFieldSet cu = c.getUnknownFields();
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("same", su.equals(cu));
        unknown.put("snapshotBytes", su.getSerializedSize());
        unknown.put("currentBytes", cu.getSerializedSize());
        out.put("unknown", unknown);
        out.put("changesTruncated", diff.truncated());
        return out;
    }

    /** 一个段：有无（单值字段）+ 内容是否相同；不同时给路径级变化（与其余段共用 500 条上限）。 */
    private static void section(Map<String, Object> out, StateDiff diff, FieldDescriptor fd, PlayerState s,
                                PlayerState c) {
        String name = fd.getName();
        Map<String, Object> view = new LinkedHashMap<>();
        boolean hasS = fd.isRepeated() ? s.getRepeatedFieldCount(fd) > 0 : s.hasField(fd);
        boolean hasC = fd.isRepeated() ? c.getRepeatedFieldCount(fd) > 0 : c.hasField(fd);
        boolean same = hasS == hasC && s.getField(fd).equals(c.getField(fd));
        view.put("same", same);
        if (!same) {
            int before = diff.changes().size();
            if (hasS != hasC) {
                view.put("presence", pair(hasS, hasC));
            }
            if (!fd.isRepeated() && fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                diff.compare(name, (Message) s.getField(fd), (Message) c.getField(fd));
            } else {
                diff.compareField("", fd, s, c);
            }
            view.put("changes", diff.changes().subList(before, diff.changes().size()).stream().map(ch -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", ch.path());
                m.put("snapshot", ch.snapshot());
                m.put("current", ch.current());
                return m;
            }).toList());
        }
        out.put(name, view);
    }

    // ------------------------------------------------------------------ 账本

    private static Map<String, Object> ledgerView(LedgerDiff.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clean", r.clean());
        out.put("rows", r.rows().stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stream", row.stream());
            m.put("epoch", Long.toUnsignedString(row.epoch()));
            m.put("seq", Long.toUnsignedString(row.seq()));
            return m;
        }).toList());
        out.put("rowsTruncated", r.rowsTruncated());
        out.put("unprovable", r.unprovable().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stream", u.stream());
            m.put("reason", u.reason());
            return m;
        }).toList());
        return out;
    }

    // ------------------------------------------------------------------ 工具

    private static <T> Map<Long, T> byUuid(List<T> items, Function<T, Long> id) {
        Map<Long, T> out = new LinkedHashMap<>();
        for (T item : items) {
            out.putIfAbsent(id.apply(item), item);
        }
        return out;
    }

    private static BigInteger unsigned(long bits) {
        return new BigInteger(Long.toUnsignedString(bits));
    }
}
