package com.game.data.rollback;

import com.game.api.DubboGroups;
import com.game.api.GuildInternalService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会内部查询的 Dubbo 调用方（data-ops-spec §4.6.1；Q15：走 4.5 交付的 Dubbo 契约，不直读 {@code guild_asset_op}）。
 * 编程式引用（{@link IsolatedDubboModule}，同 xm-scene / xm-guild 的资产通道客户端）：xm-data 不引入 dubbo-spring-boot-starter，
 * 只读与 dry-run 的进程不起 Dubbo；引用在<b>第一次检查时</b>才建（在作业线程上，建连阻塞也无妨）。直连 {@code xm.dubbo.guild-url}
 * （本机 {@code tri://127.0.0.1:20886}），{@code retries = 0}、{@code check = false}；调用方 MAC（{@code XM_DUBBO_SECRET}）由 xm-api 的
 * SPI 过滤器自动加上，缺密钥时那次调用异常完成（= 问不到，fail-closed）。线程安全。
 */
public final class DubboGuildInternalClient implements GuildDivergenceGate.Client, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DubboGuildInternalClient.class);

    private final String url;
    private final Duration defaultTimeout;
    private volatile IsolatedDubboModule module;
    private volatile ReferenceConfig<GuildInternalService> reference;
    private volatile GuildInternalService service;
    private volatile boolean closed;

    /** @param url 直连地址（如 {@code tri://127.0.0.1:20886}） */
    public DubboGuildInternalClient(String url, Duration defaultTimeout) {
        this.url = url;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public CompletableFuture<ListAppliedAssetOpsSinceResponse> list(ListAppliedAssetOpsSinceRequest request,
                                                                   Duration timeout) {
        GuildInternalService target;
        try {
            target = service();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        long timeoutMs = Math.max(1, Math.min(timeout.toMillis(), Integer.MAX_VALUE));
        CompletableFuture<ListAppliedAssetOpsSinceResponse> future;
        // 单次超时走调用级附件（只作用于这一次调用，调完即清），比引用上的缺省值优先
        RpcContext.getClientAttachment().setObjectAttachment(CommonConstants.TIMEOUT_KEY, timeoutMs);
        try {
            future = target.listAppliedAssetOpsSince(request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            RpcContext.getClientAttachment().removeAttachment(CommonConstants.TIMEOUT_KEY);
        }
        if (future == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Dubbo 返回了空的 future"));
        }
        return future.orTimeout(timeoutMs + 200, TimeUnit.MILLISECONDS);
    }

    private GuildInternalService service() {
        GuildInternalService s = service;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("帮会内部查询客户端已关闭");
            }
            if (service == null) {
                IsolatedDubboModule m = IsolatedDubboModule.create("xm-data-guild-check");
                ReferenceConfig<GuildInternalService> ref = new ReferenceConfig<>(m.module());
                try {
                    ref.setInterface(GuildInternalService.class);
                    ref.setGroup(DubboGroups.GUILD);
                    ref.setUrl(url);
                    ref.setRetries(0);
                    ref.setCheck(false);
                    ref.setTimeout((int) Math.min(Integer.MAX_VALUE, defaultTimeout.toMillis()));
                    service = ref.get();
                    reference = ref;
                    module = m;
                    log.info("帮会内部查询客户端就绪 url={}", url);
                } catch (RuntimeException e) {
                    m.close();
                    throw e;
                }
            }
            return service;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        ReferenceConfig<GuildInternalService> ref = reference;
        reference = null;
        service = null;
        if (ref != null) {
            try {
                ref.destroy();
            } catch (RuntimeException e) {
                log.debug("销毁帮会内部查询引用出错（忽略）：{}", e.toString());
            }
        }
        IsolatedDubboModule m = module;
        module = null;
        if (m != null) {
            m.close();
        }
    }
}
