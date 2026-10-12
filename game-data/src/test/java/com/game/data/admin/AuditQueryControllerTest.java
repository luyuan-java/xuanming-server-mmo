package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.data.query.TransactionLogQueryService;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogMapper;
import com.game.data.store.TransactionLogQuery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 走 Spring MVC 的真实参数绑定（项目不带 -parameters 编译，参数名必须显式写）与 JSON 序列化；错误应答体带 code（OpsErrorAdvice）。
 * 流水查询的规则（T-Q1）：参数组合与走索引约束、金币（currencyType=0）是有效条件、游标在应答头。
 */
class AuditQueryControllerTest {

    private final TransactionLogMapper mapper = mock(TransactionLogMapper.class);
    private final PlayerSnapshotMapper snapshots = mock(PlayerSnapshotMapper.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditQueryController(
                    new TransactionLogQueryService(mapper, Duration.ofDays(7)), snapshots))
            .setControllerAdvice(new OpsErrorAdvice()).build();

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
        when(mapper.query(any())).thenAnswer(inv -> {
            TransactionLogQuery q = inv.getArgument(0);
            if (q.getFromPlayer() != null) {
                return List.of(entry(20, 200, -1L, 0));
            }
            return List.of(entry(10, 100, 0, -1L));
        });

        mvc.perform(get("/admin/transaction-log").param("player", "18446744073709551615").param("since", "0"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(AuditQueryController.NEXT_CURSOR_HEADER))
                .andExpect(jsonPath("$[0].txId").value("10"))
                .andExpect(jsonPath("$[0].toPlayer").value("18446744073709551615"))
                .andExpect(jsonPath("$[1].txId").value("20"));
        mvc.perform(get("/admin/transaction-log").param("player", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(get("/admin/transaction-log").param("player", "1").param("limit", "5000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 截断时应答头给游标_游标原样带回查询() throws Exception {
        List<TransactionLogEntry> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            rows.add(entry(i, 100L * i, 0, 7));
        }
        when(mapper.query(any())).thenReturn(rows);

        mvc.perform(get("/admin/transaction-log").param("itemUuid", "55").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string(AuditQueryController.NEXT_CURSOR_HEADER, "200:2"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/admin/transaction-log").param("itemUuid", "55").param("after", "200:2"));

        ArgumentCaptor<TransactionLogQuery> q = ArgumentCaptor.forClass(TransactionLogQuery.class);
        verify(mapper, org.mockito.Mockito.times(2)).query(q.capture());
        assertThat(q.getAllValues().get(0).getFetch()).as("limit + 1 判截断").isEqualTo(3);
        assertThat(q.getAllValues().get(1).getAfterTimeMs()).isEqualTo(200);
        assertThat(q.getAllValues().get(1).getAfterTxId()).isEqualTo(2);
        mvc.perform(get("/admin/transaction-log").param("itemUuid", "55").param("after", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 不给玩家时必须能走索引_币种0是有效条件并推出kind() throws Exception {
        when(mapper.query(any())).thenReturn(List.of());

        mvc.perform(get("/admin/transaction-log").param("reasons", "9,10")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(get("/admin/transaction-log").param("reasons", "9").param("since", "0").param("until", "1000"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/transaction-log").param("currencyType", "0")).andExpect(status().isOk());
        mvc.perform(get("/admin/transaction-log").param("kind", "item").param("currencyType", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/transaction-log").param("currencyType", "0").param("itemConfigId", "5"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/transaction-log").param("itemConfigId", "0")).andExpect(status().isBadRequest());

        ArgumentCaptor<TransactionLogQuery> q = ArgumentCaptor.forClass(TransactionLogQuery.class);
        verify(mapper, org.mockito.Mockito.times(2)).query(q.capture());
        assertThat(q.getAllValues().get(0).getReasons()).containsExactly(9);
        TransactionLogQuery gold = q.getAllValues().get(1);
        assertThat(gold.getCurrencyType()).as("金币 = 币种 0，有效").isZero();
        assertThat(gold.getKind()).isEqualTo(1);
    }

    @Test
    void 快照接口_元数据与字节数_原因与排序参数_uint64按字符串输出() throws Exception {
        PlayerSnapshotEntry e = new PlayerSnapshotEntry();
        e.setSnapshotId(-2L);
        e.setPlayerId(1001);
        e.setTimeMs(300);
        e.setCause(6);
        e.setOwnerEpoch(7);
        e.setLevel(12);
        e.setPosX(1.5);
        e.setStateBytes(42);
        e.setOperator("ops");
        when(snapshots.listByPlayer(eq(1001L), eq(100L), eq(Long.MAX_VALUE), eq(List.of(6, 2)), eq(true), eq(10)))
                .thenReturn(List.of(e));

        mvc.perform(get("/admin/player-snapshots").param("player", "1001").param("since", "100").param("limit", "10")
                        .param("cause", "GM_MANUAL", "2").param("order", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshotId").value("18446744073709551614"))
                .andExpect(jsonPath("$[0].playerId").value("1001"))
                .andExpect(jsonPath("$[0].cause").value(6))
                .andExpect(jsonPath("$[0].causeName").value("GM_MANUAL"))
                .andExpect(jsonPath("$[0].ownerEpoch").value("7"))
                .andExpect(jsonPath("$[0].operator").value("ops"))
                .andExpect(jsonPath("$[0].stateBytes").value(42))
                .andExpect(jsonPath("$[0].playerState").doesNotExist());
        mvc.perform(get("/admin/player-snapshots").param("player", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/player-snapshots").param("player", "1").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/player-snapshots").param("player", "1").param("cause", "NOPE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/player-snapshots").param("player", "1").param("order", "sideways"))
                .andExpect(status().isBadRequest());
        verify(snapshots, org.mockito.Mockito.never()).listByPlayer(eq(1L), anyLong(), anyLong(), any(), anyBoolean(),
                anyInt());
    }
}
