package com.game.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GuildAssetOpBrief;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.api.proto.ListAppliedResult;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 帮会内部查询的 Java 自有契约（guild-economy-spec §7.11 / E11）：结果码 0 不是成功（漏填 / 对端不认识一律按失败，fail-closed）；
 * 保留期拒绝带 cutoff_ms 字段而不是拼进文本；字段号钉住（与基线 guild_internal.proto 同形，ops = 1、next_after_op_id = 2）。
 */
class GuildInternalContractTest {

    @Test
    void 结果码缺省不是成功() {
        assertThat(ListAppliedAssetOpsSinceResponse.getDefaultInstance().getResult())
                .isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_UNSPECIFIED);
        assertThat(ListAppliedResult.LIST_APPLIED_RESULT_OK.getNumber()).isNotZero();
    }

    @Test
    void 字段号与基线同形_保留期拒绝带cutoff字段() throws Exception {
        assertThat(ListAppliedAssetOpsSinceRequest.getDescriptor().findFieldByName("player_ids").getNumber()).isEqualTo(2);
        assertThat(ListAppliedAssetOpsSinceRequest.getDescriptor().findFieldByName("limit").getNumber()).isEqualTo(5);
        assertThat(ListAppliedAssetOpsSinceResponse.getDescriptor().findFieldByName("ops").getNumber()).isEqualTo(1);
        assertThat(ListAppliedAssetOpsSinceResponse.getDescriptor().findFieldByName("next_after_op_id").getNumber())
                .isEqualTo(2);
        assertThat(GuildAssetOpBrief.getDescriptor().findFieldByName("updated_ms").getNumber()).isEqualTo(9);

        ListAppliedAssetOpsSinceResponse rejected = ListAppliedAssetOpsSinceResponse.newBuilder()
                .setResult(ListAppliedResult.LIST_APPLIED_RESULT_RETENTION_REJECTED)
                .setCutoffMs(1_697_411_600_000L)
                .setDetail("since_ms older than terminal retention; cutoff_ms=1697411600000")
                .build();
        ListAppliedAssetOpsSinceResponse parsed = ListAppliedAssetOpsSinceResponse.parseFrom(rejected.toByteArray());
        assertThat(parsed.getCutoffMs()).isEqualTo(1_697_411_600_000L);
        assertThat(parsed.getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_RETENTION_REJECTED);
    }

    @Test
    void 接口是异步的() throws Exception {
        GuildInternalService service = request -> CompletableFuture.completedFuture(
                ListAppliedAssetOpsSinceResponse.newBuilder().setResult(ListAppliedResult.LIST_APPLIED_RESULT_OK).build());
        assertThat(service.listAppliedAssetOpsSince(ListAppliedAssetOpsSinceRequest.getDefaultInstance()).get().getResult())
                .isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_OK);
    }
}
