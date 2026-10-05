package com.game.guild;

import com.game.api.DubboGroups;
import com.game.api.GuildInternalService;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.guild.service.GuildInternalQueries;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * {@link GuildInternalService} 的 Dubbo 提供方（group {@code guild}，与 {@code ClientMessageService} 同一端口；guild-economy-spec §3.6、§7.11、Q7）。
 * 只做协议适配，判定与查询在 {@link GuildInternalQueries}。不占消息号、不进客户端白名单；调用方鉴权靠 Dubbo 调用方 MAC（{@code XM_DUBBO_SECRET}，
 * xm-api 的 SPI 过滤器），基线这里没有凭据、只靠网络隔离（§9.1 第 11 条）。注册与资产通道开关无关（guild.go:362-367）。
 */
@DubboService(group = DubboGroups.GUILD)
public class GuildInternalServiceProvider implements GuildInternalService {

    private final GuildInternalQueries queries;

    public GuildInternalServiceProvider(GuildInternalQueries queries) {
        this.queries = queries;
    }

    @Override
    public CompletableFuture<ListAppliedAssetOpsSinceResponse> listAppliedAssetOpsSince(ListAppliedAssetOpsSinceRequest request) {
        return queries.listAppliedAssetOpsSince(request);
    }
}
