package com.game.scene.player;

import com.game.player.store.state.CurrencyDebtState;
import com.game.player.store.state.CurrencyState;
import com.game.proto.CurrencyComp;
import com.game.table.AssetErrorTip;
import com.game.table.CommonErrorTip;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * 玩家的货币（只在场景逻辑线程上读写）。规则与 tip 码同 mmorpg {@code CurrencySystem}：
 * <ul>
 *   <li>加：数额 ≤ 0 → 1005；币种越界 → 1005；该币种被全服产出封禁或本人被 GM 封禁 → 27005 {@code kAssetBlocked}；
 *       然后先抵补缴欠款（未冻结、未过期、有剩余的那一笔，抵 min(收入, 剩余)，还清即删），剩下的入账；</li>
 *   <li>扣：数额 ≤ 0 → 1005；币种越界 → 1005；余额不足 → 27000 {@code kAssetCurrencyInsufficient}（玩家看得懂的终局拒绝）；</li>
 *   <li>纯参数校验排在状态判定之前（同基线：资产通道对编程错误不能无限重投）。</li>
 * </ul>
 * 与基线的差异（有意）：加币溢出（基线 uint64 无检查、GM 可加到回绕）回 1005、余额与欠款都不变——按抵欠款之后的
 * 净入账判（先算后改）；余额上限因此是 {@code Long.MAX_VALUE}（客户端协议是 uint64，取值不变）。跨区冻结（27003）随跨节点换图接入。
 * 补缴欠款目前没有任何写入入口（同基线：GM 挂欠款的指令是空桩，随路线图 7.2），只会来自存档。
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
    /** 被封禁获取（全服或本人）：27005 {@code kAssetBlocked}。 */
    public static final int BLOCKED = AssetErrorTip.asset_error.kAssetBlocked_VALUE;
    static final int INSUFFICIENT = AssetErrorTip.asset_error.kAssetCurrencyInsufficient_VALUE;

    /**
     * 一次加 / 扣的结局。
     *
     * @param tipId    0 = 成功；否则拒绝码，余额未变
     * @param before   操作前余额（拒绝时为当前余额）
     * @param after    操作后余额（拒绝时 = before）
     * @param clawback 这笔收入里抵了补缴欠款的部分（只对加有意义；after = before + 数额 − clawback）
     */
    public record Change(int tipId, int type, long before, long after, long clawback) {

        public Change(int tipId, int type, long before, long after) {
            this(tipId, type, before, after, 0);
        }

        public boolean ok() {
            return tipId == 0;
        }
    }

    /** 一笔补缴欠款（基线 CurrencyDebt）。owed / paid / expiresAt / createdAt 是 uint64，按无符号放在 long 里。 */
    public static final class Debt {
        private final long owed;
        private long paid;
        private final boolean frozen;
        private final long expiresAt;
        private final CurrencyDebtState stored;

        private Debt(CurrencyDebtState stored) {
            this.stored = stored;
            this.owed = stored.getOwed();
            this.paid = stored.getPaid();
            this.frozen = stored.getFrozen();
            this.expiresAt = stored.getExpiresAt();
        }

        /** 剩余 = owed − paid（paid 超过 owed 时为 0）。 */
        public long remaining() {
            return Long.compareUnsigned(owed, paid) > 0 ? owed - paid : 0;
        }

        public long paid() {
            return paid;
        }

        /** 现在能不能抵扣：有剩余、未冻结、未过期（到期时间 0 = 不过期）。 */
        boolean active(long nowSeconds) {
            boolean expired = expiresAt != 0 && Long.compareUnsigned(nowSeconds, expiresAt) >= 0;
            return remaining() != 0 && !frozen && !expired;
        }

        CurrencyDebtState toState() {
            return stored.toBuilder().setPaid(paid).build();
        }
    }

    private final long[] balances;
    /** 被封禁获取的币种，按封禁先后（同基线 blocked_types 的追加顺序）。 */
    private final List<Integer> blocked;
    /** 补缴欠款，按币种升序（同一币种至多一笔；存档里重复的以第一笔为准）。 */
    private final TreeMap<Integer, Debt> debts;
    /** 存档里本版本不认识的字段（更新版本写的）：原样带回，滚动升级 / 回滚时不被旧节点抹掉。 */
    private final UnknownFieldSet unknownFields;

    private Wallet(long[] balances, List<Integer> blocked, TreeMap<Integer, Debt> debts, UnknownFieldSet unknownFields) {
        this.balances = balances;
        this.blocked = blocked;
        this.debts = debts;
        this.unknownFields = unknownFields;
    }

    /** 新号：全 0、无封禁、无欠款。 */
    public static Wallet empty() {
        return new Wallet(new long[TYPE_COUNT], new ArrayList<>(), new TreeMap<>(Integer::compareUnsigned),
                UnknownFieldSet.getDefaultInstance());
    }

    /** 从持久化数据恢复。存档里多出来的币种与不认识的字段（更新版本写的）原样保留，少的币种补 0。 */
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
        TreeMap<Integer, Debt> debts = new TreeMap<>(Integer::compareUnsigned);
        for (CurrencyDebtState debt : state.getDebtsList()) {
            debts.putIfAbsent(debt.getCurrencyType(), new Debt(debt));
        }
        return new Wallet(balances, blocked, debts, state.getUnknownFields());
    }

    public long balance(int type) {
        return known(type) ? balances[type] : 0;
    }

    public boolean isBlocked(int type) {
        return blocked.contains(type);
    }

    /** 某币种的补缴欠款；没有为 null。 */
    public Debt debt(int type) {
        return debts.get(type);
    }

    public Change add(int type, long amount) {
        return add(type, amount, false, 0);
    }

    /**
     * 加币，判定顺序同基线 AddCurrency：参数（1005）→ 全服产出封禁 → 本人封禁（都是 27005）→ 抵补缴欠款 → 净入账溢出（1005，什么都不改）
     * → 入账。
     *
     * @param globallyBlocked 该币种在全服产出封禁名单上（由 {@code CurrencyService} 判定后传入）
     * @param nowSeconds      当前 Unix 秒（欠款到期判断）
     */
    public Change add(int type, long amount, boolean globallyBlocked, long nowSeconds) {
        int tip = checkAdd(type, amount, globallyBlocked, nowSeconds);
        if (tip != 0) {
            return rejected(tip, type);
        }
        long before = balances[type];
        Debt debt = debts.get(type);
        long clawback = clawbackOf(debt, amount, nowSeconds);
        long net = amount - clawback;
        if (clawback != 0) {
            debt.paid += clawback;
            if (debt.remaining() == 0) {
                debts.remove(type);
            }
        }
        balances[type] = before + net;
        return new Change(0, type, before, balances[type], clawback);
    }

    /** 不改任何状态、只回答 {@link #add(int, long, boolean, long)} 现在会回什么：0 = 会成，否则是拒绝码。 */
    public int checkAdd(int type, long amount, boolean globallyBlocked, long nowSeconds) {
        if (amount <= 0 || !known(type)) {
            return INVALID_PARAMETER;
        }
        if (globallyBlocked || isBlocked(type)) {
            return BLOCKED;
        }
        long net = amount - clawbackOf(debts.get(type), amount, nowSeconds);
        return balances[type] > Long.MAX_VALUE - net ? INVALID_PARAMETER : 0;
    }

    /** 这笔收入里要抵补缴欠款的部分：min(收入, 剩余)，欠款不在生效期为 0。 */
    private static long clawbackOf(Debt debt, long amount, long nowSeconds) {
        if (debt == null || !debt.active(nowSeconds)) {
            return 0;
        }
        long remaining = debt.remaining();
        return Long.compareUnsigned(remaining, amount) < 0 ? remaining : amount;
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

    /** 从没动过（全 0、无封禁、无欠款、没有多余币种槽、没有不认识的字段）：持久化时可以省略整段，读回来仍是 {@link #empty()}。 */
    public boolean isPristine() {
        if (balances.length != TYPE_COUNT || !blocked.isEmpty() || !debts.isEmpty() || !unknownFields.asMap().isEmpty()) {
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
        for (Debt debt : debts.values()) {
            state.addDebts(debt.toState());
        }
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
        return "Wallet" + Arrays.toString(balances) + " blocked=" + blocked + " debts=" + debts.keySet();
    }
}
