package com.game.scene.world;

/** 不可变的场景导航策略；无导航的旧场景仍沿用既有坐标和高度规则。 */
public interface SceneNavigation {
    SceneNavigation NONE = new SceneNavigation() {
        @Override public boolean isWalkable(Vec3 point) { return true; }
        @Override public Move trace(Vec3 from, Vec3 to) { return new Move(to, false); }
        @Override public Vec3 velocity(Vec3 velocity) { return velocity; }
    };

    boolean isWalkable(Vec3 point);

    /** 检查整段位移，不能只检查终点；阻挡时返回阻挡前合法位置。 */
    Move trace(Vec3 from, Vec3 to);

    /** 平面地图将垂直速度归零；无导航场景不改变旧语义。 */
    Vec3 velocity(Vec3 velocity);

    record Move(Vec3 position, boolean blocked) { }
}
