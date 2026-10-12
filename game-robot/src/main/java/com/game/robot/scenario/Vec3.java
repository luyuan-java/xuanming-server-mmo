package com.game.robot.scenario;

import com.game.proto.Location;
import com.game.proto.Rotation;
import com.game.proto.Vector3;
import com.game.proto.Velocity;
import java.util.Locale;

/**
 * 服务器坐标三元组（z-up，米；movement 契约 §1）。{@code Location} / {@code Vector3} / {@code Velocity} / {@code Rotation}
 * 线上形状相同只是类型名不同，这里统一成一个值类型做比较与运算。不可变。
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public static Vec3 of(Location v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    public static Vec3 of(Vector3 v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    public static Vec3 of(Velocity v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    public static Vec3 of(Rotation v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    public Location toLocation() {
        return Location.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    public Velocity toVelocity() {
        return Velocity.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    public Rotation toRotation() {
        return Rotation.newBuilder().setX(x).setY(y).setZ(z).build();
    }

    public Vec3 plus(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 minus(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scaled(double k) {
        return new Vec3(x * k, y * k, z * k);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public double length() {
        return Math.sqrt(dot(this));
    }

    public double distance(Vec3 o) {
        return minus(o).length();
    }

    /** 水平（x / y）距离：纠偏阈值只算水平（movement 契约 §4.3 第 5 步）。 */
    public double horizontalDistance(Vec3 o) {
        return Math.hypot(x - o.x, y - o.y);
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    public boolean isZero() {
        return x == 0 && y == 0 && z == 0;
    }

    /** 各分量之差都不超过 {@code eps}。 */
    public boolean approx(Vec3 o, double eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps && Math.abs(z - o.z) <= eps;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", x, y, z);
    }
}
