package com.game.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityIds;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.mail.SendMailRequest;
import com.game.table.MailErrorTip;
import org.junit.jupiter.api.Test;

/** 个人邮件投递给已注销/已删除角色时返回 kMailRecipientDeleted(17011)，不创建空邮箱行。 */
class MailRecipientDeletedTest {

    @Test
    void 已删角拒收个人邮件17011且不触发建箱事务() throws Exception {
        AuthorityJdbc sql = mock(AuthorityJdbc.class);
        when(sql.read(any(), any())).thenReturn(null);
        AuthorityIds ids = mock(AuthorityIds.class);
        MailStore store = new MailStore(sql, ids, id -> true, () -> 1_000_000L, playerId -> playerId == 42L);

        SendMailRequest req = SendMailRequest.newBuilder()
                .setSenderKind(MailStore.sender("rank"))
                .setRequestId(9001L)
                .setRecipientPlayerId(42L)
                .setTitle("赛季结算奖励")
                .setBody("恭喜上榜")
                .build();

        assertThatThrownBy(() -> store.send("rank", 1, req, Deadline.after(3000)))
                .isInstanceOf(MailStore.Reject.class)
                .extracting(e -> ((MailStore.Reject) e).tip)
                .isEqualTo(MailErrorTip.mail_error.kMailRecipientDeleted_VALUE);

        // 绝不分配邮件 ID，也不开事务建 mail_box
        verify(ids, never()).next(any());
        verify(sql, never()).tx(any(), any());
        assertThat(MailProvider.error(new MailStore.Reject(MailStore.RECIPIENT_DELETED)))
                .isEqualTo(MailErrorTip.mail_error.kMailRecipientDeleted_VALUE);
    }
}
