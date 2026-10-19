package com.game.scene.world;

/** 严格 SQL 组合夹具的包内快照接缝：仅在测试逻辑线程调用，不扩大生产 ScenePlayer 的写接口。 */
public final class InboxSnapshotTestBridge {
    private InboxSnapshotTestBridge() { }

    public static PlayerSave snapshot(ScenePlayer player) {
        return player.toSave();
    }
}
