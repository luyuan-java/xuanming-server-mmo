package com.game.player.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.player.store.PlayerStore.CreateResult;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

/** 不连库：PlayerMapper 用替身，重键异常按 MySQL 驱动的真实形状构造。 */
class PlayerStoreTest {

    private final PlayerMapper mapper = mock(PlayerMapper.class);
    private final PlayerStore store = new PlayerStore(mapper, () -> 1_000L);

    private static PlayerRow row(long playerId, String name) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(playerId);
        row.setAccount("robot_0001");
        row.setName(name);
        return row;
    }

    /** Spring 把 MySQL 1062 翻译成的异常（MyBatis → SQLErrorCodeSQLExceptionTranslator）。 */
    private static DuplicateKeyException duplicate(String entry, String key) {
        String message = "Duplicate entry '" + entry + "' for key '" + key + "'";
        return new DuplicateKeyException("### Error updating database.  Cause: " + message,
                new SQLIntegrityConstraintViolationException(message, "23000", 1062));
    }

    @Test
    void 名字唯一键大小写与全半角不敏感() {
        assertThat(PlayerStore.nameKey("Alice")).isEqualTo("alice");
        assertThat(PlayerStore.nameKey("ALICE")).isEqualTo("alice");
        assertThat(PlayerStore.nameKey(" ＡＬＩＣＥ ")).isEqualTo("alice");
        assertThat(PlayerStore.nameKey("　道友Ab1　")).isEqualTo("道友ab1");
        assertThat(PlayerStore.nameKey("张三")).isEqualTo("张三");
        assertThatThrownBy(() -> PlayerStore.nameKey(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 建角时由存储层计算唯一键() {
        PlayerRow row = row(7, "Alice");

        assertThat(store.createPlayer(row)).isEqualTo(CreateResult.CREATED);

        verify(mapper).insertPlayer(row, "alice");
        assertThat(row.getCreatedAt()).isEqualTo(1_000L);
        assertThat(row.getUpdatedAt()).isEqualTo(1_000L);
    }

    @Test
    void 撞名字唯一键回NAME_TAKEN_兼容带表名与不带表名两种消息() {
        when(mapper.insertPlayer(any(), eq("alice"))).thenThrow(duplicate("alice", "player.uk_player_name_key"));
        assertThat(store.createPlayer(row(7, "ALICE"))).isEqualTo(CreateResult.NAME_TAKEN);

        when(mapper.insertPlayer(any(), eq("bob"))).thenThrow(duplicate("bob", "uk_player_name_key"));
        assertThat(store.createPlayer(row(8, "Bob"))).isEqualTo(CreateResult.NAME_TAKEN);
    }

    @Test
    void 撞主键是不变量被破坏_抛异常不报重名() {
        when(mapper.insertPlayer(any(), anyString())).thenThrow(duplicate("7", "player.PRIMARY"));

        assertThatThrownBy(() -> store.createPlayer(row(7, "Alice")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRIMARY")
                .hasCauseInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 认不出撞了哪个约束时按不变量破坏处理() {
        when(mapper.insertPlayer(any(), anyString())).thenThrow(new DuplicateKeyException("未知"));

        assertThatThrownBy(() -> store.createPlayer(row(7, "Alice"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 索引名取最后一个for_key_不被被撞的值干扰() {
        DuplicateKeyException e = duplicate("x' for key 'uk_player_name_key", "player.PRIMARY");
        assertThat(PlayerStore.duplicatedKeyName(e)).isEqualTo("PRIMARY");
        assertThat(PlayerStore.duplicatedKeyName(new RuntimeException("no sql"))).isNull();
    }

    // ================================================================ 上限内建角（数据库事务保证上限）

    private static List<PlayerRow> players(int n) {
        List<PlayerRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(row(100 + i, "角色" + i));
        }
        return rows;
    }

    @Test
    void 上限内建角_第一条语句是锁账号行_之后才数角色() {
        when(mapper.lockAccount("robot_0001")).thenReturn("robot_0001");
        when(mapper.selectByAccount("robot_0001")).thenReturn(players(4));

        PlayerStore.CreateOutcome outcome = store.createPlayerWithinCap(row(7, null), 5, List.of("Alice"));

        assertThat(outcome.status()).isEqualTo(PlayerStore.CreateStatus.CREATED);
        assertThat(outcome.existing()).hasSize(4);
        InOrder order = inOrder(mapper);
        order.verify(mapper).lockAccount("robot_0001");
        order.verify(mapper).selectByAccount("robot_0001");
        order.verify(mapper).insertPlayer(any(), eq("alice"));
    }

    @Test
    void 上限内建角_锁到之后已满回PLAYER_FULL_不插入() {
        when(mapper.lockAccount("robot_0001")).thenReturn("robot_0001");
        when(mapper.selectByAccount("robot_0001")).thenReturn(players(5));

        PlayerStore.CreateOutcome outcome = store.createPlayerWithinCap(row(7, null), 5, List.of("Alice"));

        assertThat(outcome.status()).isEqualTo(PlayerStore.CreateStatus.PLAYER_FULL);
        verify(mapper, never()).insertPlayer(any(), anyString());
    }

    @Test
    void 上限内建角_账号行不存在_fail_closed() {
        PlayerStore.CreateOutcome outcome = store.createPlayerWithinCap(row(7, null), 5, List.of("Alice"));

        assertThat(outcome.status()).isEqualTo(PlayerStore.CreateStatus.ACCOUNT_MISSING);
        verify(mapper, never()).selectByAccount(anyString());
        verify(mapper, never()).insertPlayer(any(), anyString());
    }

    @Test
    void 上限内建角_候选名撞名换下一个_全撞回NAME_TAKEN() {
        when(mapper.lockAccount("robot_0001")).thenReturn("robot_0001");
        when(mapper.selectByAccount("robot_0001")).thenReturn(players(1));
        when(mapper.insertPlayer(any(), eq("a1"))).thenThrow(duplicate("a1", "uk_player_name_key"));

        PlayerRow row = row(7, null);
        assertThat(store.createPlayerWithinCap(row, 5, List.of("a1", "a2")).status())
                .isEqualTo(PlayerStore.CreateStatus.CREATED);
        assertThat(row.getName()).isEqualTo("a2");

        when(mapper.insertPlayer(any(), eq("a2"))).thenThrow(duplicate("a2", "uk_player_name_key"));
        PlayerStore.CreateOutcome taken = store.createPlayerWithinCap(row(8, null), 5, List.of("a1", "a2"));
        assertThat(taken.status()).isEqualTo(PlayerStore.CreateStatus.NAME_TAKEN);
        assertThat(taken.existing()).hasSize(1);
    }

    // ================================================================ 归属协议

    @Test
    void 夺权_上一个写者已释放或租约过期才成功_新租约为现在加OWNER_LEASE() {
        when(mapper.claimOwnerEpoch(9, 1_000L, 1_000L + PlayerStore.OWNER_LEASE.toMillis())).thenReturn(1);
        when(mapper.selectOwnerEpoch(9)).thenReturn(6L);

        assertThat(store.claimOwnership(9)).isEqualTo(new PlayerStore.ClaimResult.Claimed(6));
    }

    @Test
    void 夺权_仍被持有回Held带当前epoch_玩家不存在回NotFound() {
        when(mapper.selectOwnerEpoch(9)).thenReturn(5L);
        assertThat(store.claimOwnership(9)).isEqualTo(new PlayerStore.ClaimResult.Held(5));

        when(mapper.selectOwnerEpoch(10)).thenReturn(null);
        assertThat(store.claimOwnership(10)).isEqualTo(new PlayerStore.ClaimResult.NotFound());
    }

    @Test
    void 续约_全部续上不查_有续不上的报出来() {
        List<OwnerLease> leases = List.of(new OwnerLease(1, 3), new OwnerLease(2, 4), new OwnerLease(3, 5));
        long until = 1_000L + PlayerStore.OWNER_LEASE.toMillis();
        when(mapper.renewOwnerLeases(leases, until)).thenReturn(3);
        assertThat(store.renewOwnerLeases(leases)).isEmpty();
        verify(mapper, never()).selectStillHeld(any());

        when(mapper.renewOwnerLeases(leases, until)).thenReturn(1);
        when(mapper.selectStillHeld(leases)).thenReturn(List.of(2L));
        assertThat(store.renewOwnerLeases(leases)).containsExactly(new OwnerLease(1, 3), new OwnerLease(3, 5));
    }

    @Test
    void 续约按批切分() {
        List<OwnerLease> leases = new ArrayList<>();
        for (int i = 0; i < PlayerStore.RENEW_BATCH + 1; i++) {
            leases.add(new OwnerLease(i + 1, 1));
        }
        when(mapper.renewOwnerLeases(any(), anyLong())).thenAnswer(inv -> inv.<List<?>>getArgument(0).size());

        assertThat(store.renewOwnerLeases(leases)).isEmpty();
        verify(mapper, times(2)).renewOwnerLeases(any(), anyLong());
    }

    @Test
    void 释放与写回都带epoch围栏() {
        when(mapper.releaseOwner(9, 6, 1_000L)).thenReturn(1);
        assertThat(store.releaseOwnership(9, 6)).isTrue();
        assertThat(store.releaseOwnership(9, 5)).isFalse();

        PlayerRow save = row(9, "x");
        save.setOwnerEpoch(6);
        when(mapper.updateStateAndRelease(save)).thenReturn(1);
        assertThat(store.saveStateAndRelease(save)).isTrue();
        assertThat(save.getUpdatedAt()).isEqualTo(1_000L);
    }
}
