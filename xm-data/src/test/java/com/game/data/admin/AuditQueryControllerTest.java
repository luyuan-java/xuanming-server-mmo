package com.game.data.admin;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 走 Spring MVC 的真实参数绑定（项目不带 -parameters 编译，参数名必须显式写）与 JSON 序列化。 */
class AuditQueryControllerTest {

    private static TransactionLogEntry entry(long txId, long timeMs, long from, long to) {
        TransactionLogEntry e = new TransactionLogEntry();
        e.setTxId(txId);
        e.setTimeMs(timeMs);
        e.setFromPlayer(from);
        e.setToPlayer(to);
        e.setReason(9);
        e.setKind(1);
        return e;
    }

    @Test
    void 参数绑定与uint64按字符串输出_双向结果按时间归并() throws Exception {
        TransactionLogMapper mapper = mock(TransactionLogMapper.class);
        PlayerSnapshotMapper snapshots = mock(PlayerSnapshotMapper.class);
        when(mapper.findByFromPlayer(eq(-1L), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(entry(20, 200, -1L, 0)));
        when(mapper.findByToPlayer(eq(-1L), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(entry(10, 100, 0, -1L)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditQueryController(mapper, snapshots)).build();

        mvc.perform(get("/admin/transaction-log").param("player", "18446744073709551615").param("since", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].txId").value("10"))
                .andExpect(jsonPath("$[0].toPlayer").value("18446744073709551615"))
                .andExpect(jsonPath("$[1].txId").value("20"));
        mvc.perform(get("/admin/transaction-log").param("player", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/transaction-log").param("player", "1").param("limit", "5000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 快照接口_元数据与字节数_uint64按字符串输出_参数校验同流水() throws Exception {
        PlayerSnapshotMapper snapshots = mock(PlayerSnapshotMapper.class);
        PlayerSnapshotEntry e = new PlayerSnapshotEntry();
        e.setSnapshotId(-2L);
        e.setPlayerId(1001);
        e.setTimeMs(300);
        e.setCause(2);
        e.setOwnerEpoch(7);
        e.setLevel(12);
        e.setPosX(1.5);
        e.setStateBytes(42);
        when(snapshots.findByPlayer(eq(1001L), eq(100L), eq(Long.MAX_VALUE), eq(10))).thenReturn(List.of(e));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AuditQueryController(mock(TransactionLogMapper.class), snapshots)).build();

        mvc.perform(get("/admin/player-snapshots").param("player", "1001").param("since", "100").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshotId").value("18446744073709551614"))
                .andExpect(jsonPath("$[0].playerId").value("1001"))
                .andExpect(jsonPath("$[0].cause").value(2))
                .andExpect(jsonPath("$[0].ownerEpoch").value("7"))
                .andExpect(jsonPath("$[0].level").value(12))
                .andExpect(jsonPath("$[0].posX").value(1.5))
                .andExpect(jsonPath("$[0].stateBytes").value(42))
                .andExpect(jsonPath("$[0].playerState").doesNotExist());
        mvc.perform(get("/admin/player-snapshots").param("player", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/player-snapshots").param("player", "1").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }
}
