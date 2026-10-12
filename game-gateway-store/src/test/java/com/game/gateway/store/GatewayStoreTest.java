package com.game.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** 写入前的校验（控制字符、长度）与 InnoDB 死锁时的有界重试。真 SQL 见 {@link GatewayStoreSqlTest}。 */
class GatewayStoreTest {

    private final GatewayStoreMapper mapper = mock(GatewayStoreMapper.class);
    private final AtomicInteger deadlocksLeft = new AtomicInteger();
    /** 前 deadlocksLeft 次整事务以死锁失败，之后照常执行。 */
    private final TransactionOperations tx = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            if (deadlocksLeft.getAndDecrement() > 0) {
                throw new DeadlockLoserDataAccessException("Deadlock found when trying to get lock", null);
            }
            return action.doInTransaction(null);
        }
    };
    private final GatewayStore store = new GatewayStore(mapper, tx, () -> 1000L);

    @Test
    void 控制字符一律拒绝_防日志注入() {
        assertThatThrownBy(() -> store.addWhitelist(1, "robot\nforged=1", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.addWhitelist(1, "robot_0001", "a\rb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createAnnouncement("标题", null, "notice\n", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createAnnouncement("标\u0007题", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.upsertZone(new ZoneRow(1, "一\n区", 0, 5000, "", null, false, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.setZoneStatus(1, ZoneManualStatus.MAINTENANCE, "维护\n伪造行"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).upsertWhitelist(anyInt(), anyString(), anyString());
    }

    @Test
    void 公告正文上限() {
        assertThatThrownBy(() -> store.createAnnouncement("标题", "字".repeat(GatewayStore.CONTENT_MAX + 1), null, null,
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 死锁时整事务重做_有界() {
        when(mapper.selectWhitelistEntry(1, "robot_0001")).thenReturn(new WhitelistRow(1, "robot_0001", ""));
        deadlocksLeft.set(GatewayStore.DEADLOCK_ATTEMPTS - 1);
        assertThat(store.addWhitelist(1, "robot_0001", "")).isNotNull();
        verify(mapper, times(1)).upsertWhitelist(1, "robot_0001", "");

        when(mapper.selectZone(1)).thenReturn(new ZoneRow(1, "一区", 0, 5000, "", null, false, 0, 1000, 1000));
        deadlocksLeft.set(GatewayStore.DEADLOCK_ATTEMPTS);
        assertThatThrownBy(() -> store.upsertZone(new ZoneRow(1, "一区", 0, 5000, "", null, false, 0, 0, 0)))
                .isInstanceOf(DeadlockLoserDataAccessException.class);
        verify(mapper, never()).upsertZone(any(), anyLong());
    }
}
