package com.game.data.admin;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        when(mapper.findByFromPlayer(eq(-1L), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(entry(20, 200, -1L, 0)));
        when(mapper.findByToPlayer(eq(-1L), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(entry(10, 100, 0, -1L)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditQueryController(mapper)).build();

        mvc.perform(get("/admin/transaction-log").param("player", "18446744073709551615").param("since", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].txId").value("10"))
                .andExpect(jsonPath("$[0].toPlayer").value("18446744073709551615"))
                .andExpect(jsonPath("$[1].txId").value("20"));
        mvc.perform(get("/admin/transaction-log").param("player", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/transaction-log").param("player", "1").param("limit", "5000"))
                .andExpect(status().isBadRequest());
    }
}
