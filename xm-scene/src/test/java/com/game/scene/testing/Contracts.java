package com.game.scene.testing;

import com.game.contract.MessageIdRegistry;
import com.game.scene.world.SceneMessageIds;

/** 测试共用的契约产物（从 classpath 上的 xm-proto 读，不连任何外部服务）。 */
public final class Contracts {

    public static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    public static final SceneMessageIds IDS = SceneMessageIds.resolve(REGISTRY);

    private Contracts() {
    }
}
