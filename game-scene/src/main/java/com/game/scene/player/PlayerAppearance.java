package com.game.scene.player;

import com.game.player.store.state.AppearanceRequestReceipt;
import com.game.player.store.state.AppearanceState;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家角色形象的永久收藏位图、1 小时免费试用位图、在途试用态与幂等回执（当前永久形象存于 {@code player.appearance_id}）。
 *
 * <p>位图采用紧凑字节低位在前格式（第 i 位在 {@code bytes[i / 8]} 的 {@code 1 << (i % 8)}），
 * 与 C++ {@code bit_index_util.h} 完全一致；仅在置位时按需扩容，完整保留未知高位 bit 及消息级未知字段。
 */
public final class PlayerAppearance {

    public static final int MAX_RECENT_REQUESTS = 32;
    public static final int KIND_SWITCH = 1;
    public static final int KIND_START_TRIAL = 2;
    public static final int KIND_FREE_SWITCH = 3;

    public enum IdempotencyVerdict { NEW, REPLAY, MISMATCH }

    private record Receipt(long clientRequestId, int kind, String targetAppearanceId, long seq) { }

    private byte[] ownedBits;
    private byte[] trialUsedBits;
    private String trialAppearanceId;
    private long trialExpiresAt;
    private long switchSeq;
    private long nextReceiptSeq;
    private final Map<Long, Receipt> receipts;
    private final UnknownFieldSet unknownFields;

    private PlayerAppearance(byte[] ownedBits, byte[] trialUsedBits, String trialAppearanceId, long trialExpiresAt,
                             long switchSeq, Map<Long, Receipt> receipts, long nextReceiptSeq,
                             UnknownFieldSet unknownFields) {
        this.ownedBits = ownedBits;
        this.trialUsedBits = trialUsedBits;
        this.trialAppearanceId = trialAppearanceId == null ? "" : trialAppearanceId;
        this.trialExpiresAt = trialExpiresAt;
        this.switchSeq = switchSeq;
        this.receipts = receipts;
        this.nextReceiptSeq = nextReceiptSeq;
        this.unknownFields = unknownFields == null ? UnknownFieldSet.getDefaultInstance() : unknownFields;
    }

    public static PlayerAppearance empty() {
        return new PlayerAppearance(new byte[0], new byte[0], "", 0, 0, new LinkedHashMap<>(), 1, UnknownFieldSet.getDefaultInstance());
    }

    public static PlayerAppearance restore(AppearanceState state) {
        if (state == null) {
            return empty();
        }
        Map<Long, Receipt> map = new LinkedHashMap<>();
        long maxSeq = 0;
        var sorted = state.getRecentRequestsList().stream()
                .sorted(Comparator.comparingLong(AppearanceRequestReceipt::getSeq))
                .toList();
        for (AppearanceRequestReceipt r : sorted) {
            if (r.getClientRequestId() != 0) {
                map.put(r.getClientRequestId(), new Receipt(
                        r.getClientRequestId(), r.getKind(), r.getTargetAppearanceId(), r.getSeq()));
                if (Long.compareUnsigned(r.getSeq(), maxSeq) > 0) {
                    maxSeq = r.getSeq();
                }
            }
        }
        return new PlayerAppearance(
                state.getOwnedBits().toByteArray(),
                state.getTrialUsedBits().toByteArray(),
                state.getTrialAppearanceId(),
                state.getTrialExpiresAt(),
                state.getSwitchSeq(),
                map,
                maxSeq + 1,
                state.getUnknownFields());
    }

    public boolean isPristine() {
        return ownedBits.length == 0
                && trialUsedBits.length == 0
                && trialAppearanceId.isEmpty()
                && trialExpiresAt == 0
                && switchSeq == 0
                && receipts.isEmpty()
                && unknownFields.asMap().isEmpty();
    }

    public byte[] ownedBitsCopy() {
        return Arrays.copyOf(ownedBits, ownedBits.length);
    }

    public byte[] trialUsedBitsCopy() {
        return Arrays.copyOf(trialUsedBits, trialUsedBits.length);
    }

    public String trialAppearanceId() {
        return trialAppearanceId;
    }

    public long trialExpiresAt() {
        return trialExpiresAt;
    }

    public long switchSeq() {
        return switchSeq;
    }

    public boolean isTrialActive(long nowSec) {
        return !trialAppearanceId.isEmpty() && Long.compareUnsigned(trialExpiresAt, nowSec) > 0;
    }

    public String effectiveAppearance(String permanentAppearanceId, long nowSec) {
        return isTrialActive(nowSec) ? trialAppearanceId : (permanentAppearanceId == null ? "" : permanentAppearanceId);
    }

    public static boolean isBitSet(byte[] bits, int bitIndex) {
        if (bitIndex < 0 || bits == null) {
            return false;
        }
        int byteIdx = bitIndex >>> 3;
        return byteIdx < bits.length && ((bits[byteIdx] & (1 << (bitIndex & 7))) != 0);
    }

    public static byte[] setBit(byte[] bits, int bitIndex) {
        if (bitIndex < 0) {
            throw new IllegalArgumentException("bitIndex must be non-negative: " + bitIndex);
        }
        int byteIdx = bitIndex >>> 3;
        byte[] out = bits.length <= byteIdx ? Arrays.copyOf(bits, byteIdx + 1) : bits;
        out[byteIdx] = (byte) (out[byteIdx] | (1 << (bitIndex & 7)));
        return out;
    }

    public boolean isOwned(int bitIndex) {
        return isBitSet(ownedBits, bitIndex);
    }

    public void markOwned(int bitIndex) {
        ownedBits = setBit(ownedBits, bitIndex);
    }

    public boolean isTrialUsed(int bitIndex) {
        return isBitSet(trialUsedBits, bitIndex);
    }

    public void markTrialUsed(int bitIndex) {
        trialUsedBits = setBit(trialUsedBits, bitIndex);
    }

    public void startTrial(String targetAppearanceId, int bitIndex, long expiresAtSec) {
        markTrialUsed(bitIndex);
        this.trialAppearanceId = targetAppearanceId;
        this.trialExpiresAt = expiresAtSec;
    }

    public void clearTrial() {
        this.trialAppearanceId = "";
        this.trialExpiresAt = 0;
    }

    public void incrementSwitchSeq() {
        this.switchSeq++;
    }

    public IdempotencyVerdict checkIdempotency(long clientRequestId, int kind, String targetAppearanceId) {
        if (clientRequestId == 0) {
            return IdempotencyVerdict.NEW;
        }
        Receipt existing = receipts.get(clientRequestId);
        if (existing == null) {
            return IdempotencyVerdict.NEW;
        }
        if (existing.kind() == kind && existing.targetAppearanceId().equals(targetAppearanceId)) {
            return IdempotencyVerdict.REPLAY;
        }
        return IdempotencyVerdict.MISMATCH;
    }

    public void rememberIdempotency(long clientRequestId, int kind, String targetAppearanceId) {
        if (clientRequestId == 0 || receipts.containsKey(clientRequestId)) {
            return;
        }
        while (receipts.size() >= MAX_RECENT_REQUESTS) {
            Long oldestKey = receipts.keySet().iterator().next();
            receipts.remove(oldestKey);
        }
        receipts.put(clientRequestId, new Receipt(clientRequestId, kind, targetAppearanceId, nextReceiptSeq++));
    }

    public AppearanceState toState() {
        AppearanceState.Builder builder = AppearanceState.newBuilder().setUnknownFields(unknownFields);
        if (ownedBits.length > 0) {
            builder.setOwnedBits(ByteString.copyFrom(ownedBits));
        }
        if (trialUsedBits.length > 0) {
            builder.setTrialUsedBits(ByteString.copyFrom(trialUsedBits));
        }
        if (!trialAppearanceId.isEmpty()) {
            builder.setTrialAppearanceId(trialAppearanceId);
        }
        if (trialExpiresAt != 0) {
            builder.setTrialExpiresAt(trialExpiresAt);
        }
        if (switchSeq != 0) {
            builder.setSwitchSeq(switchSeq);
        }
        for (Receipt r : receipts.values()) {
            builder.addRecentRequests(AppearanceRequestReceipt.newBuilder()
                    .setClientRequestId(r.clientRequestId())
                    .setKind(r.kind())
                    .setTargetAppearanceId(r.targetAppearanceId())
                    .setSeq(r.seq())
                    .build());
        }
        return builder.build();
    }
}
