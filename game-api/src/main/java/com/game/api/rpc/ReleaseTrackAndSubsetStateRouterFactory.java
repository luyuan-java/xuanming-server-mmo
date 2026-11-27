package com.game.api.rpc;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.cluster.router.state.StateRouter;
import org.apache.dubbo.rpc.cluster.router.state.StateRouterFactory;

/** 注册 {@link ReleaseTrackAndSubsetRouter} 供 Dubbo 消费端自动按发布轨道与确定性子集选点。 */
@Activate(order = -100)
public final class ReleaseTrackAndSubsetStateRouterFactory implements StateRouterFactory {

    @Override
    public <T> StateRouter<T> getRouter(Class<T> interfaceClass, URL url) {
        return new ReleaseTrackAndSubsetRouter<>(url);
    }
}
