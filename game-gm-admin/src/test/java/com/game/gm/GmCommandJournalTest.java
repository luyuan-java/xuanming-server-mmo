package com.game.gm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class GmCommandJournalTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager txManager;
    private AtomicLong nowMs;
    private AtomicLong idSeq;
    private GmCommandJournal journal;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:gm_journal_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        txManager = new DataSourceTransactionManager(ds);
        try (InputStream in = getClass().getResourceAsStream("/db/gm-schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    jdbc.execute(stmt.strip());
                }
            }
        }
        nowMs = new AtomicLong(1_760_000_000_000L);
        idSeq = new AtomicLong(10_000L);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(nowMs.get());
            }
        };
        journal = new GmCommandJournal(jdbc, mapper, txManager, clock, idSeq::incrementAndGet);
    }

    @Test
    void 邮件草稿幂等重放_篡改冲突409_未知结局允许安全重发并保留完整审计() {
        String reqKey = UUID.randomUUID().toString();
        ObjectNode req = mapper.createObjectNode().put("senderKind", 1).put("title", "补偿").put("body", "内容").put("validDays", 7);

        ObjectNode draft = journal.prepare("ops.alice", reqKey, "1001", "故障补偿", req);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        String cmdId = draft.path("id").asText();

        // 同幂等号同载荷重放返回原草稿
        ObjectNode replayed = journal.prepare("ops.alice", reqKey, "1001", "故障补偿", req);
        assertThat(replayed.path("id").asText()).isEqualTo(cmdId);

        // 同幂等号换参数回 409
        assertThatThrownBy(() -> journal.prepare("ops.alice", reqKey, "1002", "故障补偿", req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // 非创建者不得发送 -> 403
        assertThatThrownBy(() -> journal.send(cmdId, "ops.bob", r -> mapper.createObjectNode().put("mailId", "1")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 首次发送网络未知 -> UNKNOWN
        ObjectNode unknown = journal.send(cmdId, "ops.alice", r -> {
            throw new GmGamePort.CallFailure("UNAVAILABLE", "timeout", true, 0);
        });
        assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");

        // 邮件类 UNKNOWN 允许安全重试并收敛为 DELIVERED
        ObjectNode delivered = journal.send(cmdId, "ops.alice", r -> mapper.createObjectNode().put("mailId", "8888"));
        assertThat(delivered.path("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.path("result").path("mailId").asText()).isEqualTo("8888");

        // 审计记录完整：DRAFT -> SENDING(1) -> UNKNOWN(1) -> SENDING(2) -> DELIVERED(2)
        ObjectNode audit = journal.auditHistory(cmdId, "MAIL");
        assertThat(audit.path("records")).hasSize(5);
        assertThat(audit.path("records").get(4).path("event").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void 玩家在线命令5分钟草稿过期与UNKNOWN禁止重发且禁止在事务内发送() {
        ObjectNode payload = mapper.createObjectNode().put("operation", "player.level");
        payload.putObject("arguments").put("level", 40);

        ObjectNode expiredDraft = journal.preparePlayerCommand("ops.alice", UUID.randomUUID().toString(), "2001", "测试", payload);
        nowMs.addAndGet(GmCommandJournal.PLAYER_DRAFT_TTL_MS + 1L);
        assertThatThrownBy(() -> journal.sendPlayerCommand(expiredDraft.path("id").asText(), "ops.alice", r -> mapper.createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // 新草稿执行返回 UNKNOWN 后严禁再次重发
        ObjectNode fresh = journal.preparePlayerCommand("ops.alice", UUID.randomUUID().toString(), "2001", "测试2", payload);
        String freshId = fresh.path("id").asText();
        ObjectNode unknown = journal.sendPlayerCommand(freshId, "ops.alice", r -> {
            throw new GmGamePort.CallFailure("UNAVAILABLE", "lost", true, 0);
        });
        assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> journal.sendPlayerCommand(freshId, "ops.alice", r -> mapper.createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // 活跃数据库事务内禁止调用发送
        TransactionTemplate ambient = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> ambient.executeWithoutResult(status ->
                journal.sendPlayerCommand(freshId, "ops.alice", r -> mapper.createObjectNode())))
                .isInstanceOf(IllegalStateException.class);
    }
}
