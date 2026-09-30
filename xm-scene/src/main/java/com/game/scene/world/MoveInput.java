package com.game.scene.world;

import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.MoveSyncC2S;
import com.game.proto.Rotation;

/**
 * 三条移动上行（134 MoveStart / 132 MoveSync / 131 MoveStop）归一化后的输入。不可变。
 *
 * <p>三者的处理完全相同（先位置、后速度），差别只在速度：MoveStart / MoveSync 用上报的速度，MoveStop 速度清零。
 * 未用到的字段（{@code target_location}、{@code client_time_ms}）基线也不读，这里不带。
 *
 * @param location 上报位置（服务器坐标，z-up）
 * @param rotation 朝向，原样存、原样广播（请求没带就是全零的空消息，基线同样无条件覆盖）
 * @param velocity 上报速度（米/秒，尚未截断）；MoveStop 为 {@link Vec3#ORIGIN}
 * @param inputSeq 输入序号，只在回 137 时原样回显
 */
record MoveInput(Vec3 location, Rotation rotation, Vec3 velocity, int inputSeq) {

    static MoveInput of(MoveStartC2S start) {
        return new MoveInput(Vec3.fromLocation(start.getStartLocation()), start.getRotation(),
                Vec3.fromVelocity(start.getVelocity()), start.getInputSeq());
    }

    static MoveInput of(MoveSyncC2S sync) {
        return new MoveInput(Vec3.fromLocation(sync.getLocation()), sync.getRotation(),
                Vec3.fromVelocity(sync.getVelocity()), sync.getInputSeq());
    }

    static MoveInput of(MoveStopC2S stop) {
        return new MoveInput(Vec3.fromLocation(stop.getEndLocation()), stop.getRotation(), Vec3.ORIGIN,
                stop.getInputSeq());
    }

    /** 位置、朝向、速度全部有限。非有限输入整条丢弃（基线不查，会把 NaN 写进 Transform 并广播出去）。 */
    boolean isFinite() {
        return location.isFinite() && velocity.isFinite()
                && Double.isFinite(rotation.getX()) && Double.isFinite(rotation.getY())
                && Double.isFinite(rotation.getZ());
    }
}
