package com.game.scene.world;

import com.game.proto.Location;
import com.game.proto.Velocity;
import com.game.proto.Vector3;

/**
 * 场景内坐标 / 速度矢量。与客户端 {@code Vector3} 一样用 double（mmorpg AGENTS.md §4：坐标必须 double，匹配 UE 精度）。
 *
 * <p>世界是 z-up：x / y 是地面平面，z 是高度，单位米（速度为米/秒）。线上的 {@code Vector3} / {@code Location} /
 * {@code Velocity} 形状完全相同（{@code double x=1,y=2,z=3}），只是类型名不同，边界处逐分量拷贝。
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ORIGIN = new Vec3(0, 0, 0);

    /** 三个分量都为 0（-0.0 也算 0）。基线把 (0,0,0) 当作「没有有效坐标」；速度为 (0,0,0) 即静止。 */
    public boolean isOrigin() {
        return x == 0 && y == 0 && z == 0;
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /** 三维欧氏距离是否不超过 {@code radius}（基线 CanSee 用 dtVdist，即三维距离，含等号）。 */
    public boolean within(Vec3 other, double radius) {
        return distanceSquared(other) <= radius * radius;
    }

    public double distanceSquared(Vec3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 水平（x / y）距离，z 不参与（基线纠偏阈值 kMoveCorrectionEpsilon 只算水平）。 */
    public double horizontalDistance(Vec3 other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    /** 三维模长。 */
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public Vec3 plus(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    /** {@code this + v × factor}（外推一步：位置 + 速度 × 步长），只分配一个对象。 */
    public Vec3 plusScaled(Vec3 v, double factor) {
        return new Vec3(x + v.x * factor, y + v.y * factor, z + v.z * factor);
    }

    public Vec3 scaled(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    public Vector3 toProto() {
        return Vector3.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    public Location toLocation() {
        return Location.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    public Velocity toVelocity() {
        return Velocity.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    public static Vec3 fromProto(Vector3 v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    public static Vec3 fromLocation(Location v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    public static Vec3 fromVelocity(Velocity v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }
}
