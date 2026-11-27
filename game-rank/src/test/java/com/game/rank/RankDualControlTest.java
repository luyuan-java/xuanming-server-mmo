package com.game.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.game.api.RankAdminService;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.common.base.AdminApproval;
import com.game.proto.rank.RankAdminResponse;
import com.game.proto.rank.RemoveRankEntryRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-26 榜单删除仅对 admin 强验双人复核，match 自动删榜不受限制。 */
class RankDualControlTest {

    private static final String ADMIN_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String MATCH_SECRET = "fedcba9876543210fedcba9876543210";

    @Test
    void admin删榜无批准或自批被拒_双人批准放行_match自动删榜不受限制() throws Exception {
        RankStore store = mock(RankStore.class);
        long now = 1_700_000_000_000L;
        ServiceCallSignatures sigs = new ServiceCallSignatures(
                Map.of("admin", ADMIN_SECRET, "match", MATCH_SECRET), () -> now);

        try (RankProvider provider = new RankProvider(store, sigs).withDualControl(true, new SimpleMeterRegistry())) {
            // 1. admin 缺批准 -> 拒绝
            RemoveRankEntryRequest unapproved = ServiceCallSignatures.sign(
                    RankAdminService.REMOVE_METHOD,
                    RemoveRankEntryRequest.newBuilder().setBoardId(1L).setGeneration(1L).setEntityId(42L).setRequestId(20001L).build(),
                    "admin", ADMIN_SECRET, now);
            RankAdminResponse r1 = provider.removeRankEntry(unapproved).join();
            assertThat(r1.getErrorMessage().getId()).isNotZero();
            verify(store, never()).remove(anyString(), any(), anyBoolean(), any());

            // 2. admin 合法双人批准且 approval_id == request_id -> 放行
            RemoveRankEntryRequest approved = ServiceCallSignatures.sign(
                    RankAdminService.REMOVE_METHOD,
                    RemoveRankEntryRequest.newBuilder().setBoardId(1L).setGeneration(1L).setEntityId(42L).setRequestId(20001L)
                            .setApproval(AdminApproval.newBuilder()
                                    .setApprovalId("20001").setProposer("alice").setApprover("bob").setApprovedAtMs(now))
                            .build(),
                    "admin", ADMIN_SECRET, now);
            assertThat(provider.removeRankEntry(approved).join().getErrorMessage().getId()).isZero();

            // 3. admin request_id 与 approval_id 不一致 -> 拒绝
            RemoveRankEntryRequest mismatch = ServiceCallSignatures.sign(
                    RankAdminService.REMOVE_METHOD,
                    RemoveRankEntryRequest.newBuilder().setBoardId(1L).setGeneration(1L).setEntityId(42L).setRequestId(20099L)
                            .setApproval(AdminApproval.newBuilder()
                                    .setApprovalId("20001").setProposer("alice").setApprover("bob").setApprovedAtMs(now))
                            .build(),
                    "admin", ADMIN_SECRET, now);
            assertThat(provider.removeRankEntry(mismatch).join().getErrorMessage().getId()).isNotZero();

            // 4. match 自动删榜无需 AdminApproval
            RemoveRankEntryRequest fromMatch = ServiceCallSignatures.sign(
                    RankAdminService.REMOVE_METHOD,
                    RemoveRankEntryRequest.newBuilder().setBoardId(1L).setGeneration(1L).setEntityId(43L).setRequestId(20002L).build(),
                    "match", MATCH_SECRET, now);
            assertThat(provider.removeRankEntry(fromMatch).join().getErrorMessage().getId()).isZero();
        }
    }
}
