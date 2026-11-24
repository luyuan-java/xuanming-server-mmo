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
    public static final int DAILY_MATERIAL_LIMIT = 20;
    public static final int BATTLE_MATERIAL_UNITS = 10;

    /** 持久化请求种类（与 C++ {@code ClientRequestKind} 数值一一对应，永不重排）。 */
    public static final int KIND_ENHANCE_EQUIP = 1;
    public static final int KIND_BUY_MYSTERY_MERCHANT = 2;
    public static final int KIND_CREATE_ATTRIBUTE_SCHEME = 3;
    public static final int KIND_EQUIPMENT_CRAFT = 4;
    public static final int KIND_EQUIPMENT_MATERIAL_REWARD = 5;

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
        if (records.size() > CAPACITY) {
            return false;
        }
        Set<Long> keys = new HashSet<>();
        for (ClientRequestRecordState r : records) {
            if (r.getKey() == 0 || r.getKind() == 0 || r.getSeq() == 0 || !keys.add(r.getKey())) {
                return false;
            }
        }
        if (Integer.compareUnsigned(materialReward.getEarnedToday(), DAILY_MATERIAL_LIMIT) > 0
                || Integer.remainderUnsigned(materialReward.getEarnedToday(), BATTLE_MATERIAL_UNITS) != 0
                || Long.remainderUnsigned(materialReward.getAvailableUnits(), BATTLE_MATERIAL_UNITS) != 0) {
            return false;
        }
        return true;
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
