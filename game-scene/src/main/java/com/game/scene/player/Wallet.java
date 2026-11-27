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
 * 净入账判（先算后改）；余额上限因此是 {@code Long.MAX_VALUE}（客户端协议是 uint64，取值不变）。
 * 跨节点换图冻结（27003 {@link #FROZEN}，排在纯参数校验之后、封禁之前，同基线）由 {@code CurrencyService} 判——钱包不认识玩家状态。
 * 补缴欠款目前没有任何写入入口（同基线：GM 挂欠款的指令是空桩，随路线图 7.2），只会来自存档。
 */
public final class Wallet {

    /** 币种：银两。 */
    public static final int GOLD = 0;
    /** 币种：灵石。 */
    public static final int DIAMOND = 1;
    /** 币种：绑定灵石。 */
    public static final int BOUND_DIAMOND = 2;
    /** 币种：绑定银两（仅个人绑定养成消耗，严禁玩家间交易/流通）。 */
    public static final int BOUND_GOLD = 3;
    /** 币种数（基线 kCurrencyMax）。 */
    public static final int TYPE_COUNT = 4;

    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    /** 被封禁获取（全服或本人）：27005 {@code kAssetBlocked}。 */
    public static final int BLOCKED = AssetErrorTip.asset_error.kAssetBlocked_VALUE;
    static final int INSUFFICIENT = AssetErrorTip.asset_error.kAssetCurrencyInsufficient_VALUE;
    /**
     * 玩家冻结中（跨节点换图的交出事务在途）：27003 {@code kAssetFrozen}。会自己消失的条件，调用方稍后重投即可，不是终局拒绝
     * （基线特意不用 1005：资产通道要据此区分「重投」与「坏请求」）。
     */
    public static final int FROZEN = AssetErrorTip.asset_error.kAssetFrozen_VALUE;

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

    /** 复合银两（绑定银两优先 + 流通银两补齐）一次原子扣款的结局。 */
    public record SplitDeduction(int tipId, Change bindChange, Change goldChange) {
        public boolean ok() {
            return tipId == 0;
        }
    }

    /** 提交同一逻辑线程预检完成的纯扣款；不改变欠款、封禁或未知字段。 */
    public void commitDeduction(Change prepared) {
        if (!prepared.ok() || !known(prepared.type()) || balances[prepared.type()] != prepared.before()
                || Long.compareUnsigned(prepared.after(), prepared.before()) > 0) {
            throw new IllegalStateException("钱包候选已过期或不是扣款");
        }
        balances[prepared.type()] = prepared.after();
    }

    /** 原子提交复合银两预检扣款（先校验两项均未漂移再写入）。 */
    public void commitSplitDeduction(SplitDeduction prepared) {
        if (!prepared.ok()) {
            throw new IllegalStateException("钱包复合候选不是成功扣款");
        }
        Change b = prepared.bindChange();
        Change g = prepared.goldChange();
        if (b != null && (b.type() != BOUND_GOLD || balances[BOUND_GOLD] != b.before()
                || Long.compareUnsigned(b.after(), b.before()) > 0)) {
            throw new IllegalStateException("绑定银两候选已过期");
        }
        if (g != null && (g.type() != GOLD || balances[GOLD] != g.before()
                || Long.compareUnsigned(g.after(), g.before()) > 0)) {
            throw new IllegalStateException("银两候选已过期");
        }
        if (b != null) {
            balances[BOUND_GOLD] = b.after();
        }
        if (g != null) {
            balances[GOLD] = g.after();
        }
    }

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
        return add(type, amount, globallyBlocked, true, nowSeconds);
    }

    /**
     * 同 {@link #add(int, long, boolean, long)}，支持豁免个人获取封禁（仅用于交易退回本人托管资产等非新增获得路径）。
     */
    public Change add(int type, long amount, boolean globallyBlocked, boolean checkPersonalBlock, long nowSeconds) {
        int tip = checkAdd(type, amount, globallyBlocked, checkPersonalBlock, nowSeconds);
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

    /**
     * 纯参数校验（加 / 扣共用）：数额 ≤ 0 或币种越界回 1005，否则 0。排在冻结、封禁这些会自己消失或要记账的状态判定之前
     * （同基线：冻结中的玩家传一个越界币种，不该拿到 RETRY 类的 27003，那是解冻之后照样失败的请求）。
     */
    public static int checkRequest(int type, long amount) {
        return amount <= 0 || !known(type) ? INVALID_PARAMETER : 0;
    }

    /** 不改任何状态、只回答 {@link #add(int, long, boolean, long)} 现在会回什么：0 = 会成，否则是拒绝码。 */
    public int checkAdd(int type, long amount, boolean globallyBlocked, long nowSeconds) {
        return checkAdd(type, amount, globallyBlocked, true, nowSeconds);
    }

    /** 不改任何状态预检加币；{@code checkPersonalBlock=false} 时跳过个人获取封禁（交易退回本人资产用）。 */
    public int checkAdd(int type, long amount, boolean globallyBlocked, boolean checkPersonalBlock, long nowSeconds) {
        int invalid = checkRequest(type, amount);
        if (invalid != 0) {
            return invalid;
        }
        if (globallyBlocked || (checkPersonalBlock && isBlocked(type))) {
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
        int invalid = checkRequest(type, amount);
        if (invalid != 0) {
            return rejected(invalid, type);
        }
        long before = balances[type];
        if (before < amount) {
            return rejected(INSUFFICIENT, type);
        }
        balances[type] = before - amount;
        return new Change(0, type, before, balances[type]);
    }

    /** 绑定银两 + 流通银两总可用额（溢出夹紧到 Long.MAX_VALUE）。 */
    public long spendableGoldWithBind() {
        long bind = balances[BOUND_GOLD];
        long gold = balances[GOLD];
        return gold > Long.MAX_VALUE - bind ? Long.MAX_VALUE : bind + gold;
    }

    /** 只读预检：绑定银两 + 流通银两是否足够支付 amount。 */
    public boolean canAffordGoldWithBind(long amount) {
        return amount > 0 && spendableGoldWithBind() >= amount;
    }

    /** 复合银两只读预检：strictGold 严守流通银两，preferBindGold 优先消耗绑定银两、不足由剩余流通银两补齐。 */
    public boolean canAffordSplitGold(long strictGold, long preferBindGold) {
        if (strictGold < 0 || preferBindGold < 0) {
            return false;
        }
        long gold = balances[GOLD];
        if (gold < strictGold) {
            return false;
        }
        if (preferBindGold == 0) {
            return true;
        }
        long remainingGold = gold - strictGold;
        long bind = balances[BOUND_GOLD];
        return bind >= preferBindGold || (preferBindGold - bind) <= remainingGold;
    }

    /** 原子优先扣绑定银两，不足部分由流通银两补齐（先完整预检，绝不半扣）。 */
    public SplitDeduction deductGoldPreferBind(long amount) {
        if (amount <= 0) {
            return new SplitDeduction(INVALID_PARAMETER, null, null);
        }
        return deductSplitGold(0L, amount);
    }

    /** 原子复合银两扣款：strictGold 严格只扣 GOLD，preferBindGold 优先扣 BOUND_GOLD 再用剩余 GOLD 补齐。 */
    public SplitDeduction deductSplitGold(long strictGold, long preferBindGold) {
        if (strictGold < 0 || preferBindGold < 0 || (strictGold == 0 && preferBindGold == 0)) {
            return new SplitDeduction(INVALID_PARAMETER, null, null);
        }
        if (!canAffordSplitGold(strictGold, preferBindGold)) {
            return new SplitDeduction(INSUFFICIENT, null, null);
        }
        long bindBefore = balances[BOUND_GOLD];
        long goldBefore = balances[GOLD];
        long spentBind = Math.min(bindBefore, preferBindGold);
        long spentGold = strictGold + (preferBindGold - spentBind);
        Change bindChange = null;
        Change goldChange = null;
        if (spentBind > 0) {
            balances[BOUND_GOLD] = bindBefore - spentBind;
            bindChange = new Change(0, BOUND_GOLD, bindBefore, balances[BOUND_GOLD]);
        }
        if (spentGold > 0) {
            balances[GOLD] = goldBefore - spentGold;
            goldChange = new Change(0, GOLD, goldBefore, balances[GOLD]);
        }
        return new SplitDeduction(0, bindChange, goldChange);
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

    private static boolean known(int type) {
        return type >= 0 && type < TYPE_COUNT;
    }

    /** 一次被拒的加 / 扣：余额不变（before = after = 当前余额）。 */
    public Change rejected(int tipId, int type) {
        long current = balance(type);
        return new Change(tipId, type, current, current);
    }

    @Override
    public String toString() {
        return "Wallet" + Arrays.toString(balances) + " blocked=" + blocked + " debts=" + debts.keySet();
    }
}
