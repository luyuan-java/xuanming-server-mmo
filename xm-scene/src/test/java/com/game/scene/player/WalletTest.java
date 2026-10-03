package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(w.add(Wallet.GOLD, 0, true).tipId()).as("参数错误先判").isEqualTo(1005);
        assertThat(w.add(Wallet.TYPE_COUNT, 5, true).tipId()).isEqualTo(1005);
        Wallet.Change blocked = w.add(Wallet.GOLD, 5, true);
        assertThat(blocked).isEqualTo(new Wallet.Change(Wallet.BLOCKED, Wallet.GOLD, 0, 0));
        assertThat(blocked.tipId()).isEqualTo(27005);
        assertThat(w.add(Wallet.GOLD, 5, false).ok()).isTrue();
        assertThat(w.balance(Wallet.GOLD)).isEqualTo(5);
    }
}
