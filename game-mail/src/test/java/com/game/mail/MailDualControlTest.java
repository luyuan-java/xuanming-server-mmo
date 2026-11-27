package com.game.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.MailAdminService;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.common.base.AdminApproval;
import com.game.proto.mail.SendMailRequest;
import com.game.proto.mail.SendMailResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-26 邮件管理发信仅对 admin 强验双人复核（approval_id == request_id），系统生产方不受限制。 */
class MailDualControlTest {

    private static final String ADMIN_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String RANK_SECRET = "fedcba9876543210fedcba9876543210";

    @Test
    void 管理员发信无批准或请求号不符被拒_双人批准放行_系统生产方rank不受双人复核限制() throws Exception {
        MailStore store = mock(MailStore.class);
        when(store.send(anyString(), anyInt(), any(), any()))
                .thenReturn(SendMailResponse.newBuilder().setMailId(101L).build());
        MailNotifications notifications = mock(MailNotifications.class);
        long now = 1_700_000_000_000L;
        ServiceCallSignatures sigs = new ServiceCallSignatures(
                Map.of("admin", ADMIN_SECRET, "rank", RANK_SECRET), () -> now);

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (MailProvider provider = new MailProvider(store, sigs, notifications).withDualControl(true, meters)) {
            // 1. admin 不带 AdminApproval -> 拒收，不碰 store
            SendMailRequest unapproved = ServiceCallSignatures.sign(
                    MailAdminService.PERSONAL_METHOD,
                    SendMailRequest.newBuilder().setRequestId(10001L).setRecipientPlayerId(42L).build(),
                    "admin", ADMIN_SECRET, now);
            SendMailResponse r1 = provider.sendPersonalMail(unapproved).join();
            assertThat(r1.getErrorMessage().getId()).isNotZero();
            verify(store, never()).send(anyString(), anyInt(), any(), any());

            // 2. admin 带合法双人批准且 approval_id == request_id -> 放行
            SendMailRequest approved = ServiceCallSignatures.sign(
                    MailAdminService.PERSONAL_METHOD,
                    SendMailRequest.newBuilder().setRequestId(10002L).setRecipientPlayerId(42L)
                            .setApproval(AdminApproval.newBuilder()
                                    .setApprovalId("10002").setProposer("alice").setApprover("bob").setApprovedAtMs(now))
                            .build(),
                    "admin", ADMIN_SECRET, now);
            SendMailResponse r2 = provider.sendPersonalMail(approved).join();
            assertThat(r2.getErrorMessage().getId()).isZero();
            assertThat(r2.getMailId()).isEqualTo(101L);

            // 3. 系统生产方 rank 不带 AdminApproval 照常放行
            SendMailRequest fromRank = ServiceCallSignatures.sign(
                    MailAdminService.PERSONAL_METHOD,
                    SendMailRequest.newBuilder().setRequestId(90001L).setRecipientPlayerId(42L).build(),
                    "rank", RANK_SECRET, now);
            SendMailResponse r3 = provider.sendPersonalMail(fromRank).join();
            assertThat(r3.getErrorMessage().getId()).isZero();
        }
    }
}
