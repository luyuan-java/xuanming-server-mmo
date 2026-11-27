package com.game.inbox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.inbox.InboxProperties;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.registry.InboxRegistry;
import com.game.inbox.rules.InboxTips;
import com.game.inbox.service.GmSanctions;
import com.game.inbox.store.InboxAdminStore;
import com.game.inbox.store.LedgerReader;
import com.game.proto.player_inbox.GmItemGainPayload;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.proto.player_inbox.player_inbox_item_row;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 覆盖 GM 运维只读查询「单玩家物品获得封禁」的全部语义（PARITY 2026-11-06）：
 * ① 严格先读收件箱、再读存档；② 区分读不到存档 vs 空封禁表；③ 按 seq 升序解出 PENDING 类型 5/6 的配置号；
 * ④ 读失败回可重试故障码（STORE_UNAVAILABLE）且不带任何列表。
 */
class ItemGainBlocksAdminQueryTest {

    @SuppressWarnings("unchecked")
    @Test
    void 先读收件箱再读存档_区分无存档与空表_故障不带任何列表() throws Exception {
        List<String> order = new ArrayList<>();
        AuthorityJdbc jdbc = mock(AuthorityJdbc.class);
        player_inbox_item_row blockRow = player_inbox_item_row.newBuilder()
                .setPlayerId(1001L)
                .setDomain(PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_GM_SANCTION_VALUE)
                .setSeq(10L)
                .setMsgType(PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_BLOCK_ITEM_GAIN_VALUE)
                .setState(PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING_VALUE)
                .setRequestId(7001L)
                .setIssuedAtMs(50_000L)
                .setPayload(GmItemGainPayload.newBuilder().setItemConfigId(3001).build().toByteString())
                .build();
        player_inbox_item_row unblockRow = player_inbox_item_row.newBuilder()
                .setPlayerId(1001L)
                .setDomain(PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_GM_SANCTION_VALUE)
                .setSeq(11L)
                .setMsgType(PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_UNBLOCK_ITEM_GAIN_VALUE)
                .setState(PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING_VALUE)
                .setRequestId(7002L)
                .setIssuedAtMs(51_000L)
                .setPayload(GmItemGainPayload.newBuilder().setItemConfigId(2002).build().toByteString())
                .build();
        when(jdbc.read(any(), any())).thenAnswer(inv -> {
            order.add("inbox");
            return List.of(blockRow, unblockRow);
        });

        LedgerReader[] activeReader = new LedgerReader[1];
        LedgerReader delegate = new LedgerReader() {
            @Override
            public Read read(Deadline budget, long playerId) throws SQLException {
                return activeReader[0].read(budget, playerId);
            }

            @Override
            public ItemGainBlockRead readItemGainBlocks(Deadline budget, long playerId) throws SQLException {
                order.add("save");
                return activeReader[0].readItemGainBlocks(budget, playerId);
            }
        };

        InboxAdminStore store = new InboxAdminStore(jdbc, InboxRegistry.load(), delegate,
                InboxMetrics.noop(), () -> 100_000L);
        InboxProperties props = mock(InboxProperties.class);
        when(props.requestBudget()).thenReturn(Duration.ofSeconds(5));
        InboxAdminController controller = new InboxAdminController(mock(GmSanctions.class), store, props);

        // 1. 存档可读 + 收件箱有 2 条 PENDING
        activeReader[0] = new LedgerReader() {
            @Override public Read read(Deadline budget, long playerId) { return Read.absent(); }
            @Override public ItemGainBlockRead readItemGainBlocks(Deadline budget, long playerId) {
                return new ItemGainBlockRead(true, List.of(2002, 4004));
            }
        };
        ResponseEntity<String> okResp = controller.itemGainBlocks("1001");
        assertThat(order).as("必须先读收件箱、后读存档").containsExactly("inbox", "save");
        assertThat(okResp.getBody())
                .contains("\"save_readable\":true")
                .contains("\"persisted_blocked_item_config_ids\":[2002,4004]")
                .contains("\"item_config_id\":3001")
                .contains("\"item_config_id\":2002");

        // 2. 读不到存档时明确标 save_readable=false（不是空表混淆）
        order.clear();
        activeReader[0] = new LedgerReader() {
            @Override public Read read(Deadline budget, long playerId) { return Read.absent(); }
            @Override public ItemGainBlockRead readItemGainBlocks(Deadline budget, long playerId) {
                return ItemGainBlockRead.absent();
            }
        };
        ResponseEntity<String> absentResp = controller.itemGainBlocks("1001");
        assertThat(absentResp.getBody()).contains("\"save_readable\":false").contains("\"item_config_id\":3001");

        // 3. 读失败回 STORE_UNAVAILABLE 且不带任何列表
        activeReader[0] = new LedgerReader() {
            @Override public Read read(Deadline budget, long playerId) { return Read.absent(); }
            @Override public ItemGainBlockRead readItemGainBlocks(Deadline budget, long playerId) throws SQLException {
                throw new SQLException("db timeout");
            }
        };
        ResponseEntity<String> errResp = controller.itemGainBlocks("1001");
        assertThat(errResp.getBody())
                .contains("\"id\":" + InboxTips.STORE_UNAVAILABLE)
                .doesNotContain("persisted_blocked_item_config_ids")
                .doesNotContain("pending_sanctions");
    }
}
