package com.game.scenemanager.travel;

import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 跨 zone 选路的装配（批次 5.4）。
 *
 * <p><b>先行件阶段只有占位</b>：{@link TravelRouting} 的两个方法都以 {@link IllegalStateException}（{@value #PLACEHOLDER_REASON}）完成——
 * 调用方按「调用失败」处理：226 受理后推 23 {3027}、留在原地；登录期重定向回落入口 zone。占位不选 gate、不签票据、不读任何存储，
 * 所以<b>这里还不要求 gate 令牌密钥</b>（{@code XM_GATE_TOKEN_SECRET}）：真实现接上时在本类里经 {@code Environment} 读它，
 * 缺失即拒启，取法与 xm-gate / xm-gateway 逐字节相同（{@code GateTokens.ofUtf8(环境变量原值)}，只判 {@code isBlank()}、<b>不去空白</b>）。
 */
@Configuration(proxyBeanMethods = false)
public class TravelConfiguration {

    /** 占位阶段两个方法失败的原因（进调用方的日志）。 */
    static final String PLACEHOLDER_REASON = "5.4 施工中";

    /** 占位：两个方法都以异常完成。真实现（选 gate、签票据）换掉这个 bean。 */
    @Bean
    public TravelRouting travelRouting() {
        return new TravelRouting() {
            @Override
            public CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request) {
                return CompletableFuture.failedFuture(new IllegalStateException(PLACEHOLDER_REASON));
            }

            @Override
            public CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request) {
                return CompletableFuture.failedFuture(new IllegalStateException(PLACEHOLDER_REASON));
            }

            @Override
            public String toString() {
                return "TravelRouting.PLACEHOLDER";
            }
        };
    }
}
