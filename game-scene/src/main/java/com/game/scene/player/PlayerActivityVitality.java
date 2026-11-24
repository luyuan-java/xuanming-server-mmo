package com.game.scene.player;

import com.game.player.store.state.ActivityClaimRecordState;
import com.game.player.store.state.ActivityPointSourceState;
import com.game.player.store.state.ActivityVitalityState;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 活动中心活跃度账本（只在场景逻辑线程上读写；对齐 C++ {@code PlayerActivityLedger}）。
 *
 * <p>保留不变量：
 * <ul>
 *   <li>一次性活动任务去重靠紧凑位图 {@code creditedMissions}（低位在前），不随周期增长；</li>
 *   <li>积分来源 {@code sources} 仅保留当前周期（旧周期裁剪）；</li>
 *   <li>领取记录 {@code claims} 对当前规则已结束周期裁剪保留最新 64 条供原 {@code request_id} 重放。</li>
 * </ul>
 */
public final class PlayerActivityVitality {

    private byte[] creditedMissions = new byte[0];
    private final List<ActivityPointSourceState> sources = new ArrayList<>();
    private final List<ActivityClaimRecordState> claims = new ArrayList<>();
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerActivityVitality() {
    }

    public static PlayerActivityVitality empty() {
        return new PlayerActivityVitality();
    }

    public static PlayerActivityVitality restore(ActivityVitalityState state) {
        PlayerActivityVitality v = new PlayerActivityVitality();
        if (state == null) {
            return v;
        }
        v.creditedMissions = state.getCreditedMissions().toByteArray();
        v.sources.addAll(state.getSourcesList());
        v.claims.addAll(state.getClaimsList());
        v.unknownFields = state.getUnknownFields();
        return v;
    }

    public boolean isMissionCredited(int bitIndex) {
        if (bitIndex < 0) {
            return false;
        }
        int byteIndex = bitIndex >>> 3;
        if (byteIndex >= creditedMissions.length) {
            return false;
        }
        return (creditedMissions[byteIndex] & (1 << (bitIndex & 7))) != 0;
    }

    public void markMissionCredited(int bitIndex) {
        if (bitIndex < 0) {
            throw new IllegalArgumentException("bitIndex 不能为负: " + bitIndex);
        }
        int byteIndex = bitIndex >>> 3;
        if (byteIndex >= creditedMissions.length) {
            creditedMissions = Arrays.copyOf(creditedMissions, byteIndex + 1);
        }
        creditedMissions[byteIndex] |= (byte) (1 << (bitIndex & 7));
    }

    public List<ActivityPointSourceState> sources() {
        return Collections.unmodifiableList(sources);
    }

    public List<ActivityClaimRecordState> claims() {
        return Collections.unmodifiableList(claims);
    }

    public void addSource(ActivityPointSourceState source) {
        sources.add(source);
    }

    public void addClaim(ActivityClaimRecordState claim) {
        claims.add(claim);
    }

    /** 裁剪当前规则已结束周期的积分来源（一次性任务去重由位图保证）。 */
    public void pruneEndedSources(int ruleId, long currentPeriodId) {
        sources.removeIf(s -> s.getRuleId() == ruleId && Long.compareUnsigned(s.getPeriodId(), currentPeriodId) < 0);
    }

    /** 裁剪当前规则已结束周期的领取记录，仅保留最新的 {@code keptPastClaims} 条供原请求重放；当前周期与其他规则不动。 */
    public void prunePastClaims(int ruleId, long currentPeriodId, int keptPastClaims) {
        int pastCount = 0;
        for (ActivityClaimRecordState c : claims) {
            if (c.getRuleId() == ruleId && Long.compareUnsigned(c.getPeriodId(), currentPeriodId) < 0) {
                pastCount++;
            }
        }
        int excess = pastCount - keptPastClaims;
        for (int i = 0; excess > 0 && i < claims.size(); ) {
            ActivityClaimRecordState c = claims.get(i);
            if (c.getRuleId() == ruleId && Long.compareUnsigned(c.getPeriodId(), currentPeriodId) < 0) {
                claims.remove(i);
                excess--;
            } else {
                i++;
            }
        }
    }

    public boolean isPristine() {
        return creditedMissions.length == 0 && sources.isEmpty() && claims.isEmpty() && unknownFields.asMap().isEmpty();
    }

    public ActivityVitalityState toState() {
        ActivityVitalityState.Builder b = ActivityVitalityState.newBuilder().setUnknownFields(unknownFields);
        if (creditedMissions.length > 0) {
            b.setCreditedMissions(ByteString.copyFrom(creditedMissions));
        }
        b.addAllSources(sources);
        b.addAllClaims(claims);
        return b.build();
    }
}
