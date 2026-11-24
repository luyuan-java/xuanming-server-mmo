package com.game.scene.player;

import com.game.player.store.state.PlayerMysteryMerchantPurchaseState;
import com.game.player.store.state.PlayerMysteryMerchantState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import java.util.TreeMap;

/**
 * 神秘商人个人每轮限购记录（仅记当前一轮，换轮逻辑清零；对齐 C++ {@code PlayerMysteryMerchantComp}）。
 */
public final class PlayerMysteryMerchant {

    private long roundId;
    private final TreeMap<Integer, Integer> purchases = new TreeMap<>(Integer::compareUnsigned);
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerMysteryMerchant() {
    }

    public static PlayerMysteryMerchant empty() {
        return new PlayerMysteryMerchant();
    }

    public static PlayerMysteryMerchant restore(PlayerMysteryMerchantState state) {
        PlayerMysteryMerchant out = new PlayerMysteryMerchant();
        if (state == null) {
            return out;
        }
        out.roundId = state.getRoundId();
        for (PlayerMysteryMerchantPurchaseState p : state.getPurchasesList()) {
            if (p.getGoodsId() != 0 && p.getCount() != 0) {
                out.purchases.put(p.getGoodsId(), p.getCount());
            }
        }
        out.unknownFields = state.getUnknownFields();
        return out;
    }

    public int boughtCount(long currentRoundId, int goodsId) {
        if (this.roundId != currentRoundId) {
            return 0;
        }
        return purchases.getOrDefault(goodsId, 0);
    }

    /** 记录一次成功购买；换轮时整份逻辑清空（包括旧轮次的未知字段，与 C++ L7 规则一致）。 */
    public void recordPurchase(long currentRoundId, int goodsId, int count) {
        if (this.roundId != currentRoundId) {
            this.roundId = currentRoundId;
            this.purchases.clear();
            this.unknownFields = UnknownFieldSet.getDefaultInstance();
        }
        this.purchases.merge(goodsId, count, Integer::sum);
    }

    public boolean isPristine() {
        return roundId == 0 && purchases.isEmpty() && unknownFields.asMap().isEmpty();
    }

    public PlayerMysteryMerchantState toState() {
        PlayerMysteryMerchantState.Builder b = PlayerMysteryMerchantState.newBuilder()
                .setUnknownFields(unknownFields)
                .setRoundId(roundId);
        for (Map.Entry<Integer, Integer> e : purchases.entrySet()) {
            b.addPurchases(PlayerMysteryMerchantPurchaseState.newBuilder()
                    .setGoodsId(e.getKey())
                    .setCount(e.getValue()));
        }
        return b.build();
    }
}
