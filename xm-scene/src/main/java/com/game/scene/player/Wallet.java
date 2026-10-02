package com.game.scene.player;

import com.game.player.store.state.CurrencyState;
import com.game.proto.CurrencyComp;
import com.game.table.AssetErrorTip;
import com.game.table.CommonErrorTip;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 玩家的货币（只在场景逻辑线程上读写）。规则与 tip 码同 mmorpg {@code CurrencySystem}：
 * <ul>
 *   <li>加：数额 ≤ 0 → 1005；币种越界 → 1005；本人被 GM 封禁该币种 → 27005 {@code kAssetBlocked}；然后入账；</li>
 *   <li>扣：数额 ≤ 0 → 1005；币种越界 → 1005；余额不足 → 27000 {@code kAssetCurrencyInsufficient}（玩家看得懂的终局拒绝）；</li>
 *   <li>纯参数校验排在状态判定之前（同基线：资产通道对编程错误不能无限重投）。</li>
 * </ul>
 * 与基线的差异（有意）：加币溢出（基线 uint64 无检查、GM 可加到回绕）回 1005、余额不变；
 * 余额上限因此是 {@code Long.MAX_VALUE}（客户端协议是 uint64，取值不变）。跨区冻结（27003）与全服禁发随对应功能接入。
 * 补缴欠款（基线 debts）没有任何生产调用方能挂上，暂不做。
 */
public final class Wallet {

    /** 币种：金币。 */
    public static final int GOLD = 0;
    /** 币种：钻石。 */
    public static final int DIAMOND = 1;
    /** 币种：绑定钻石。 */
    public static final int BOUND_DIAMOND = 2;
    /** 币种数（基线 kCurrencyMax）。 */
    public static final int TYPE_COUNT = 3;

    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int BLOCKED = AssetErrorTip.asset_error.kAssetBlocked_VALUE;
    static final int INSUFFICIENT = AssetErrorTip.asset_error.kAssetCurrencyInsufficient_VALUE;

    /**
     * 一次加 / 扣的结局。
     *
     * @param tipId  0 = 成功；否则拒绝码，余额未变
     * @param before 操作前余额（拒绝时为当前余额）
     * @param after  操作后余额（拒绝时 = before）
     */
    public record Change(int tipId, int type, long before, long after) {

        public boolean ok() {
            return tipId == 0;
        }
    }

    private final long[] balances;
    /** 被封禁获取的币种，按封禁先后（同基线 blocked_types 的追加顺序）。 */
    private final List<Integer> blocked;
    /** 存档里本版本不认识的字段（更新版本写的，如补缴欠款）：原样带回，滚动升级 / 回滚时不被旧节点抹掉。 */
    private final UnknownFieldSet unknownFields;

    private Wallet(long[] balances, List<Integer> blocked, UnknownFieldSet unknownFields) {
        this.balances = balances;
        this.blocked = blocked;
        this.unknownFields = unknownFields;
    }

    /** 新号：全 0、无封禁。 */
    public static Wallet empty() {
        return new Wallet(new long[TYPE_COUNT], new ArrayList<>(), UnknownFieldSet.getDefaultInstance());
    }

    /** 从持久化组件恢复。存档里多出来的币种与不认识的字段（更新版本写的）原样保留，少的币种补 0。 */
    public static Wallet restore(CurrencyState state) {
        long[] balances = new long[Math.max(TYPE_COUNT, state.getBalancesCount())];
        for (int i = 0; i < state.getBalancesCount(); i++) {
            balances[i] = state.getBalances(i);
        }
        List<Integer> blocked = new ArrayList<>();
        for (int type : state.getBlockedTypesList()) {
            if (!blocked.contains(type)) {
                blocked.add(type);
            }
        }
        return new Wallet(balances, blocked, state.getUnknownFields());
    }

    public long balance(int type) {
        return known(type) ? balances[type] : 0;
    }

    public boolean isBlocked(int type) {
        return blocked.contains(type);
    }

    public Change add(int type, long amount) {
        if (amount <= 0 || !known(type)) {
            return rejected(INVALID_PARAMETER, type);
        }
        if (isBlocked(type)) {
            return rejected(BLOCKED, type);
        }
        long before = balances[type];
        if (before > Long.MAX_VALUE - amount) {
            return rejected(INVALID_PARAMETER, type);
        }
        balances[type] = before + amount;
        return new Change(0, type, before, balances[type]);
    }

    public Change deduct(int type, long amount) {
        if (amount <= 0 || !known(type)) {
            return rejected(INVALID_PARAMETER, type);
        }
        long before = balances[type];
        if (before < amount) {
            return rejected(INSUFFICIENT, type);
        }
        balances[type] = before - amount;
        return new Change(0, type, before, balances[type]);
    }

    /** GM 封禁获取：币种越界 1005；已封禁幂等成功。返回 tip。 */
    public int block(int type) {
        if (!known(type)) {
            return INVALID_PARAMETER;
        }
        if (!blocked.contains(type)) {
            blocked.add(type);
        }
        return 0;
    }

    /** GM 解除封禁：币种越界 1005；没封禁也成功。返回 tip。 */
    public int unblock(int type) {
        if (!known(type)) {
            return INVALID_PARAMETER;
        }
        blocked.remove(Integer.valueOf(type));
        return 0;
    }

    /** 客户端协议形态（54 GetCurrencyList）。总是带齐全部币种的槽（基线新号首次访问前是空数组，客户端缺省同为 0）。 */
    public CurrencyComp toClient() {
        CurrencyComp.Builder comp = CurrencyComp.newBuilder();
        for (long balance : balances) {
            comp.addValues(balance);
        }
        comp.addAllBlockedTypes(blocked);
        return comp.build();
    }

    /** 从没动过（全 0、无封禁、没有多余币种槽、没有不认识的字段）：持久化时可以省略整个组件，读回来仍是 {@link #empty()}。 */
    public boolean isPristine() {
        if (balances.length != TYPE_COUNT || !blocked.isEmpty() || !unknownFields.asMap().isEmpty()) {
            return false;
        }
        for (long balance : balances) {
            if (balance != 0) {
                return false;
            }
        }
        return true;
    }

    public CurrencyState toState() {
        CurrencyState.Builder state = CurrencyState.newBuilder();
        for (long balance : balances) {
            state.addBalances(balance);
        }
        state.addAllBlockedTypes(blocked);
        state.setUnknownFields(unknownFields);
        return state.build();
    }

    private boolean known(int type) {
        return type >= 0 && type < TYPE_COUNT;
    }

    private Change rejected(int tipId, int type) {
        long current = balance(type);
        return new Change(tipId, type, current, current);
    }

    @Override
    public String toString() {
        return "Wallet" + Arrays.toString(balances) + " blocked=" + blocked;
    }
}
