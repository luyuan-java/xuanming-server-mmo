package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.CurrencyDebtState;
import com.game.player.store.state.CurrencyState;
import org.junit.jupiter.api.Test;

class WalletTest {

    @Test
    void 加扣与余额() {
        Wallet w = Wallet.empty();
        assertThat(w.add(Wallet.GOLD, 100)).isEqualTo(new Wallet.Change(0, Wallet.GOLD, 0, 100));
        assertThat(w.deduct(Wallet.GOLD, 30)).isEqualTo(new Wallet.Change(0, Wallet.GOLD, 100, 70));
        assertThat(w.balance(Wallet.GOLD)).isEqualTo(70);
        assertThat(w.balance(Wallet.DIAMOND)).isZero();
    }

    @Test
    void 参数非法回1005_余额不变() {
        Wallet w = Wallet.empty();
        w.add(Wallet.GOLD, 10);
        assertThat(w.add(Wallet.GOLD, 0).tipId()).isEqualTo(1005);
        assertThat(w.add(Wallet.GOLD, -5).tipId()).isEqualTo(1005);
        assertThat(w.add(Wallet.TYPE_COUNT, 5).tipId()).isEqualTo(1005);
        assertThat(w.add(-1, 5).tipId()).as("uint32 币种高位置位后是负数").isEqualTo(1005);
        assertThat(w.deduct(Wallet.GOLD, 0).tipId()).isEqualTo(1005);
        assertThat(w.deduct(7, 1).tipId()).isEqualTo(1005);
        assertThat(w.balance(Wallet.GOLD)).isEqualTo(10);
    }

    @Test
    void 余额不足回27000() {
        Wallet w = Wallet.empty();
        w.add(Wallet.DIAMOND, 5);
        Wallet.Change change = w.deduct(Wallet.DIAMOND, 6);
        assertThat(change.tipId()).isEqualTo(27000);
        assertThat(change.before()).isEqualTo(5).isEqualTo(change.after());
        assertThat(w.balance(Wallet.DIAMOND)).isEqualTo(5);
    }

    @Test
    void 溢出拒绝_不回绕() {
        Wallet w = Wallet.empty();
        w.add(Wallet.GOLD, Long.MAX_VALUE - 1);
        assertThat(w.add(Wallet.GOLD, 2).tipId()).isEqualTo(1005);
        assertThat(w.balance(Wallet.GOLD)).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(w.add(Wallet.GOLD, 1).ok()).isTrue();
    }

    @Test
    void 封禁只挡加币_幂等_解封后恢复() {
        Wallet w = Wallet.empty();
        assertThat(w.block(Wallet.GOLD)).isZero();
        assertThat(w.block(Wallet.GOLD)).as("重复封禁幂等成功").isZero();
        assertThat(w.add(Wallet.GOLD, 1).tipId()).isEqualTo(27005);
        assertThat(w.block(9)).isEqualTo(1005);
        assertThat(w.unblock(Wallet.GOLD)).isZero();
        assertThat(w.unblock(Wallet.GOLD)).as("没封禁也成功").isZero();
        assertThat(w.add(Wallet.GOLD, 1).ok()).isTrue();
    }

    @Test
    void 客户端形态总带齐币种槽_封禁按先后() {
        Wallet w = Wallet.empty();
        w.block(Wallet.BOUND_DIAMOND);
        w.block(Wallet.GOLD);
        assertThat(w.toClient().getValuesList()).containsExactly(0L, 0L, 0L);
        assertThat(w.toClient().getBlockedTypesList()).containsExactly(Wallet.BOUND_DIAMOND, Wallet.GOLD);
    }

    @Test
    void 持久化往返_多出的币种槽保留_没动过的可省略() {
        assertThat(Wallet.empty().isPristine()).isTrue();
        CurrencyState stored = CurrencyState.newBuilder().addBalances(7).addBalances(0).addBalances(3).addBalances(99)
                .addBlockedTypes(1).build();
        Wallet w = Wallet.restore(stored);
        assertThat(w.toState()).isEqualTo(stored);
        assertThat(w.isPristine()).isFalse();
        assertThat(Wallet.restore(CurrencyState.newBuilder().addBalances(5).build()).balance(Wallet.DIAMOND)).isZero();
    }

    @Test
    void 全服封禁排在参数校验之后_本人封禁之前_都回27005() {
        Wallet w = Wallet.empty();
        assertThat(w.add(Wallet.GOLD, 0, true, 0).tipId()).as("参数错误先判").isEqualTo(1005);
        assertThat(w.add(Wallet.TYPE_COUNT, 5, true, 0).tipId()).isEqualTo(1005);
        Wallet.Change blocked = w.add(Wallet.GOLD, 5, true, 0);
        assertThat(blocked).isEqualTo(new Wallet.Change(Wallet.BLOCKED, Wallet.GOLD, 0, 0));
        assertThat(blocked.tipId()).isEqualTo(27005);
        assertThat(w.add(Wallet.GOLD, 5, false, 0).ok()).isTrue();
        assertThat(w.balance(Wallet.GOLD)).isEqualTo(5);
    }

    // ------------------------------------------------------------------ 补缴欠款（同基线 CurrencySystem 的 Deferred-clawback 段）

    private static Wallet withDebt(long balance, CurrencyDebtState debt) {
        return Wallet.restore(CurrencyState.newBuilder().addBalances(balance).addBalances(0).addBalances(0)
                .addDebts(debt).build());
    }

    private static CurrencyDebtState.Builder goldDebt(long owed, long paid) {
        return CurrencyDebtState.newBuilder().setCurrencyType(Wallet.GOLD).setOwed(owed).setPaid(paid)
                .setReason("刷金").setGmOperator("gm1").setCreatedAt(100);
    }

    @Test
    void 收入先抵欠款_剩下的入账() {
        Wallet w = withDebt(10, goldDebt(100, 30).build());
        Wallet.Change change = w.add(Wallet.GOLD, 50, false, 1_000);
        assertThat(change).isEqualTo(new Wallet.Change(0, Wallet.GOLD, 10, 10, 50));
        assertThat(w.debt(Wallet.GOLD).paid()).isEqualTo(80);
        assertThat(w.debt(Wallet.GOLD).remaining()).isEqualTo(20);
        Wallet.Change rest = w.add(Wallet.GOLD, 50, false, 1_000);
        assertThat(rest).isEqualTo(new Wallet.Change(0, Wallet.GOLD, 10, 40, 20));
        assertThat(w.debt(Wallet.GOLD)).as("还清即删").isNull();
        assertThat(w.add(Wallet.GOLD, 5, false, 1_000).clawback()).isZero();
    }

    @Test
    void 冻结或过期的欠款不抵_到期时刻当场算过期_零表示不过期() {
        Wallet frozen = withDebt(0, goldDebt(100, 0).setFrozen(true).build());
        assertThat(frozen.add(Wallet.GOLD, 10, false, 1_000).clawback()).isZero();
        assertThat(frozen.balance(Wallet.GOLD)).isEqualTo(10);

        Wallet expiring = withDebt(0, goldDebt(100, 0).setExpiresAt(2_000).build());
        assertThat(expiring.add(Wallet.GOLD, 10, false, 1_999).clawback()).isEqualTo(10);
        assertThat(expiring.add(Wallet.GOLD, 10, false, 2_000).clawback()).as("now >= expires_at 即过期").isZero();

        Wallet forever = withDebt(0, goldDebt(100, 0).build());
        assertThat(forever.add(Wallet.GOLD, 10, false, Long.MAX_VALUE).clawback()).isEqualTo(10);
    }

    @Test
    void 欠款只抵同币种_封禁与参数错误不碰欠款() {
        Wallet w = withDebt(0, goldDebt(100, 0).build());
        assertThat(w.add(Wallet.DIAMOND, 10, false, 1).clawback()).isZero();
        assertThat(w.add(Wallet.GOLD, 10, true, 1).tipId()).isEqualTo(27005);
        assertThat(w.add(Wallet.GOLD, 0, false, 1).tipId()).isEqualTo(1005);
        assertThat(w.debt(Wallet.GOLD).paid()).isZero();
    }

    @Test
    void 溢出按净入账判_拒绝时欠款也不动() {
        Wallet w = withDebt(Long.MAX_VALUE - 10, goldDebt(100, 0).build());
        assertThat(w.checkAdd(Wallet.GOLD, 110, false, 1)).as("净入账 10 恰好到上限").isZero();
        assertThat(w.checkAdd(Wallet.GOLD, 111, false, 1)).isEqualTo(1005);
        assertThat(w.add(Wallet.GOLD, 111, false, 1).tipId()).isEqualTo(1005);
        assertThat(w.debt(Wallet.GOLD).paid()).isZero();
        assertThat(w.balance(Wallet.GOLD)).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(w.add(Wallet.GOLD, 110, false, 1)).isEqualTo(new Wallet.Change(0, Wallet.GOLD, Long.MAX_VALUE - 10,
                Long.MAX_VALUE, 100));
    }

    @Test
    void 欠款按无符号_paid超过owed视为还清() {
        Wallet w = withDebt(0, goldDebt(-1L, -2L).build());
        assertThat(w.debt(Wallet.GOLD).remaining()).isEqualTo(1);
        assertThat(w.add(Wallet.GOLD, 5, false, 1).clawback()).isEqualTo(1);
        Wallet over = withDebt(0, goldDebt(5, 9).build());
        assertThat(over.debt(Wallet.GOLD).remaining()).isZero();
        assertThat(over.add(Wallet.GOLD, 5, false, 1).clawback()).isZero();
    }

    @Test
    void 欠款持久化往返_原样保留其余字段_同币种重复的以第一笔为准() {
        CurrencyDebtState debt = goldDebt(100, 30).setExpiresAt(9_999).build();
        CurrencyState stored = CurrencyState.newBuilder().addBalances(0).addBalances(0).addBalances(0).addDebts(debt)
                .addDebts(goldDebt(7, 0).build()).build();
        Wallet w = Wallet.restore(stored);
        assertThat(w.isPristine()).isFalse();
        assertThat(w.toState().getDebtsList()).containsExactly(debt);
        w.add(Wallet.GOLD, 10, false, 1);
        assertThat(w.toState().getDebts(0)).isEqualTo(debt.toBuilder().setPaid(40).build());
    }
}
