package com.game.scene.world;

import com.game.proto.Vector3;

/**
 * 场景内坐标。与客户端 {@code Vector3} 一样用 double（mmorpg AGENTS.md §4：坐标必须 double，匹配 UE 精度）。
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ORIGIN = new Vec3(0, 0, 0);

    /** 三个分量都为 0（-0.0 也算 0）。基线把 (0,0,0) 当作「没有有效坐标」。 */
    public boolean isOrigin() {
        return x == 0 && y == 0 && z == 0;
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /** 三维欧氏距离是否不超过 {@code radius}（基线 CanSee 用 dtVdist，即三维距离）。 */
    public boolean within(Vec3 other, double radius) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    public Vector3 toProto() {
        return Vector3.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    public static Vec3 fromProto(Vector3 v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }
}
