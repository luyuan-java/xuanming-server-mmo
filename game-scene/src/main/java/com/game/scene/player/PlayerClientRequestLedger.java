package com.game.scene.player;

import com.game.player.store.state.ClientRequestLedgerState;
import com.game.player.store.state.ClientRequestRecordState;
import com.game.player.store.state.EquipmentMaterialRewardLedgerState;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端请求幂等键账本（至多 32 条）+ 装备打造防重水位 + 装备材料自选账本（对齐 C++ {@code ClientRequestLedgerComp}）。
 * 仅在场景逻辑线程读写；与背包/货币同一次玩家存档围栏提交。
 */
public final class PlayerClientRequestLedger {

    public static final int CAPACITY = 32;
    public static final int MAX_RECORDS_ON_LOAD = 256;
    public static final int DAILY_MATERIAL_LIMIT = 20;
    public static final int MAX_CLAIM_BATCH_UNITS = 20;
    public static final int MIN_CLAIM_STEP_UNITS = 5;
    public static final int BATTLE_MATERIAL_UNITS = 10;
    public static final int BONUS_MATERIAL_UNITS = 5;
    public static final int LIFETIME_MATERIAL_LIMIT = 660;
    public static final int TRADABLE_LIFETIME_LIMIT = 60;
    public static final int WEEKLY_MATERIAL_LIMIT = 150;

    /** 持久化请求种类（与 C++ {@code ClientRequestKind} 数值一一对应，永不重排）。 */
    public static final int KIND_ENHANCE_EQUIP = 1;
    public static final int KIND_BUY_MYSTERY_MERCHANT = 2;
    public static final int KIND_CREATE_ATTRIBUTE_SCHEME = 3;
    public static final int KIND_EQUIPMENT_CRAFT = 4;
    public static final int KIND_EQUIPMENT_MATERIAL_REWARD = 5;
    public static final int KIND_BUY_AFK_PASS = 6;
    public static final int KIND_SWITCH_APPEARANCE = 7;
    public static final int KIND_START_APPEARANCE_TRIAL = 8;
    public static final int KIND_CATCHUP_SEASON_SEAL = 9;
    public static final int KIND_CLAIM_TRIAL_JOURNAL_MILESTONE = 10;
    public static final int KIND_REDEEM_BOUND_ARTIFACT_VOUCHER = 11;
    public static final int KIND_SKILL_SCROLL_CRAFT = 12;
    public static final int KIND_LEARN_SKILL_BOOK = 13;
    public static final int KIND_DISMANTLE_SKILL_BOOK = 14;
    public static final int KIND_BUY_SPIRIT_SHOP = 15;

    public enum Verdict {
        NEW, REPLAY, MISMATCH
    }

    public record CheckResult(Verdict verdict, ClientRequestRecordState record) {
        static final CheckResult IS_NEW = new CheckResult(Verdict.NEW, null);
        static final CheckResult IS_MISMATCH = new CheckResult(Verdict.MISMATCH, null);
    }

    private final List<ClientRequestRecordState> records = new ArrayList<>();
    private long nextSeq;
    private long forgeRevision;
    private EquipmentMaterialRewardLedgerState materialReward = EquipmentMaterialRewardLedgerState.getDefaultInstance();
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerClientRequestLedger() {
    }

    public static PlayerClientRequestLedger empty() {
        return new PlayerClientRequestLedger();
    }

    public static PlayerClientRequestLedger restore(ClientRequestLedgerState state) {
        PlayerClientRequestLedger out = new PlayerClientRequestLedger();
        if (state == null) {
            return out;
        }
        out.records.addAll(state.getRecordsList());
        out.nextSeq = state.getNextSeq();
        out.forgeRevision = state.getForgeRevision();
        if (state.hasMaterialReward()) {
            out.materialReward = state.getMaterialReward();
        }
        out.unknownFields = state.getUnknownFields();
        return out;
    }

    /** 确定性参数指纹：取序列化字节 SHA-256 前 16 字节（调用方须先清掉 client_request_id）。 */
    public static ByteString fingerprint(Message messageWithoutRequestId) {
        return sha256Prefix16(messageWithoutRequestId.toByteArray());
    }

    public static ByteString sha256Prefix16(byte[] data) {
        try {
            byte[] full = MessageDigest.getInstance("SHA-256").digest(data);
            return ByteString.copyFrom(Arrays.copyOf(full, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isValid() {
        if (!isMaterialRewardValid(materialReward)) {
            return false;
        }
        if (records.size() > MAX_RECORDS_ON_LOAD) {
            return false;
        }
        Set<Long> keys = new HashSet<>();
        Set<Long> seqs = new HashSet<>();
        Set<Long> materialRevisions = new HashSet<>();
        Set<Long> forgeRevisions = new HashSet<>();
        long effectiveNextSeq = nextSeq == 0 ? 1L : nextSeq;
        for (ClientRequestRecordState r : records) {
            if (r.getKey() == 0 || r.getKind() == 0 || r.getSeq() == 0
                    || Long.compareUnsigned(r.getSeq(), effectiveNextSeq) >= 0
                    || !keys.add(r.getKey()) || !seqs.add(r.getSeq())) {
                return false;
            }
            if (r.getKind() == KIND_EQUIPMENT_MATERIAL_REWARD) {
                long total = Integer.toUnsignedLong(r.getCraftingMaterialCount())
                        + Integer.toUnsignedLong(r.getEnhancementStoneCount());
                if (r.getMaterialRewardRevision() == 0
                        || Long.compareUnsigned(r.getMaterialRewardRevision(), materialReward.getRevision()) > 0
                        || total == 0 || total > MAX_CLAIM_BATCH_UNITS
                        || total % MIN_CLAIM_STEP_UNITS != 0
                        || Integer.compareUnsigned(r.getCraftingTradableCount(), r.getCraftingMaterialCount()) > 0
                        || Integer.compareUnsigned(r.getEnhancementTradableCount(), r.getEnhancementStoneCount()) > 0
                        || !materialRevisions.add(r.getMaterialRewardRevision())) {
                    return false;
                }
            }
            if (r.getKind() == KIND_EQUIPMENT_CRAFT) {
                if (r.getEquipmentItemId() == 0 || r.getForgeRevision() == 0
                        || Long.compareUnsigned(r.getForgeRevision(), forgeRevision) > 0
                        || !forgeRevisions.add(r.getForgeRevision())) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isMaterialRewardValid(EquipmentMaterialRewardLedgerState m) {
        if (!m.getMigratedToV2()) {
            if (m.getLifetimeEarned() != 0 || m.getTradableEarnedTotal() != 0
                    || m.getStage1TradableConfirmed() != 0
                    || m.getAvailableTradableUnits() != 0 || m.getWeekStartsAtMs() != 0
                    || m.getEarnedThisWeek() != 0
                    || Integer.compareUnsigned(m.getEarnedToday(), DAILY_MATERIAL_LIMIT) > 0
                    || Integer.remainderUnsigned(m.getEarnedToday(), BATTLE_MATERIAL_UNITS) != 0
                    || Long.remainderUnsigned(m.getAvailableUnits(), BATTLE_MATERIAL_UNITS) != 0) {
                return false;
            }
            if (m.getDayStartsAtMs() == 0) {
                return m.getRevision() == 0 && m.getEarnedToday() == 0 && m.getAvailableUnits() == 0;
            }
            long[] day = com.game.scene.equip.EquipmentCraftService.resolveDay(m.getDayStartsAtMs());
            return m.getEarnedToday() != 0 && day != null && day[0] == m.getDayStartsAtMs();
        }
        int lifetime = m.getLifetimeEarned();
        int tradableTotal = m.getTradableEarnedTotal();
        int stage1Confirmed = m.getStage1TradableConfirmed();
        long avail = m.getAvailableUnits();
        long availTradable = m.getAvailableTradableUnits();
        int earnedWeek = m.getEarnedThisWeek();
        if (Integer.compareUnsigned(lifetime, LIFETIME_MATERIAL_LIMIT) > 0
                || Integer.remainderUnsigned(lifetime, MIN_CLAIM_STEP_UNITS) != 0
                || Integer.compareUnsigned(tradableTotal, TRADABLE_LIFETIME_LIMIT) > 0
                || Integer.compareUnsigned(tradableTotal, Integer.divideUnsigned(lifetime, BATTLE_MATERIAL_UNITS)) > 0
                || Integer.compareUnsigned(stage1Confirmed, tradableTotal) > 0
                || Long.remainderUnsigned(avail, MIN_CLAIM_STEP_UNITS) != 0
                || Long.compareUnsigned(availTradable, avail) > 0
                || (Integer.compareUnsigned(lifetime, LIFETIME_MATERIAL_LIMIT) < 0
                    && Long.compareUnsigned(availTradable, Integer.toUnsignedLong(tradableTotal)) > 0)
                || Integer.compareUnsigned(earnedWeek, WEEKLY_MATERIAL_LIMIT) > 0
                || Integer.remainderUnsigned(earnedWeek, MIN_CLAIM_STEP_UNITS) != 0) {
            return false;
        }
        if (m.getWeekStartsAtMs() == 0) {
            return earnedWeek == 0;
        }
        long[] week = com.game.scene.equip.EquipmentCraftService.resolveWeek(m.getWeekStartsAtMs());
        return earnedWeek != 0 && Integer.compareUnsigned(lifetime, LIFETIME_MATERIAL_LIMIT) >= 0
                && week != null && week[0] == m.getWeekStartsAtMs();
    }

    public CheckResult check(long key, int kind, ByteString fingerprint) {
        for (ClientRequestRecordState r : records) {
            if (r.getKey() != key) {
                continue;
            }
            if (r.getKind() == kind && r.getFingerprint().equals(fingerprint)) {
                return new CheckResult(Verdict.REPLAY, r);
            }
            return CheckResult.IS_MISMATCH;
        }
        return CheckResult.IS_NEW;
    }

    /** 写入一条成功回执；满 {@value #CAPACITY} 条淘汰 {@code seq} 最小者。 */
    public void remember(ClientRequestRecordState.Builder draft) {
        long key = draft.getKey();
        if (key == 0) {
            return;
        }
        long assignedSeq = nextSeq == 0 ? 1L : nextSeq;
        for (ClientRequestRecordState r : records) {
            if (Long.compareUnsigned(r.getSeq(), assignedSeq) >= 0) {
                assignedSeq = r.getSeq() + 1L;
            }
        }
        nextSeq = assignedSeq + 1L;
        records.removeIf(r -> r.getKey() == key);
        records.add(draft.setSeq(assignedSeq).build());
        while (records.size() > CAPACITY) {
            int oldest = 0;
            for (int i = 1; i < records.size(); i++) {
                if (Long.compareUnsigned(records.get(i).getSeq(), records.get(oldest).getSeq()) < 0) {
                    oldest = i;
                }
            }
            records.remove(oldest);
        }
    }

    public long nextSeq() {
        return nextSeq;
    }

    public long forgeRevision() {
        return forgeRevision;
    }

    public void setForgeRevision(long forgeRevision) {
        this.forgeRevision = forgeRevision;
    }

    public EquipmentMaterialRewardLedgerState materialReward() {
        return materialReward;
    }

    public void setMaterialReward(EquipmentMaterialRewardLedgerState materialReward) {
        this.materialReward = materialReward == null
                ? EquipmentMaterialRewardLedgerState.getDefaultInstance()
                : materialReward;
    }

    public boolean isPristine() {
        return records.isEmpty() && nextSeq == 0 && forgeRevision == 0
                && materialReward.equals(EquipmentMaterialRewardLedgerState.getDefaultInstance())
                && unknownFields.asMap().isEmpty();
    }

    public ClientRequestLedgerState toState() {
        List<ClientRequestRecordState> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparing(ClientRequestRecordState::getKey, Long::compareUnsigned));
        ClientRequestLedgerState.Builder b = ClientRequestLedgerState.newBuilder()
                .setUnknownFields(unknownFields)
                .setNextSeq(nextSeq)
                .setForgeRevision(forgeRevision)
                .addAllRecords(ordered);
        if (!materialReward.equals(EquipmentMaterialRewardLedgerState.getDefaultInstance())) {
            b.setMaterialReward(materialReward);
        }
        return b.build();
    }
}
