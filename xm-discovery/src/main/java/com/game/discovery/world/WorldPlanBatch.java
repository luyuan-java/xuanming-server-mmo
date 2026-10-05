package com.game.discovery.world;

import com.game.api.proto.WorldChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一拍的计划改动（scene-channels-spec §4.4、§4.6.2 P7）：期望版本号 + 按顺序执行的 {@link WorldPlanOp}。
 * {@link WorldChannelStore#write} 把它编码成写入 Lua 的 {@code ARGV[2] = 期望 ver}、{@code ARGV[3] = 写入后的 ver} 与
 * {@code (op, field, value)} 三元组，令牌与版本号都对上才整批执行并把 ver 置为 {@link #writtenVersion()}，否则一条也不写。
 *
 * <p><b>写入后的版本号</b>：领导者用 {@link #forSnapshot}，取 {@code max(期望 ver + 1, 快照的 Redis TIME 毫秒)}——
 * 不只是「加一」。Redis 丢了最后一次写入（主从切换、AOF everysec）后版本号会退回，若仍按加一，领导者重新规划的那一批会落在<b>同一个</b>
 * 版本号上而内容不同，已应用过丢失那一版的节点按「版本号相同」永远不重读，领导者也按 {@code applied_plan_version ≥ plan_version}
 * 误以为节点看到了新记录。用 Redis TIME 托底后，重写的版本号严格大于丢失的那一版（同一时钟源；主从时钟偏差远小于两次写的间隔），
 * 版本号在每次写入间唯一。毫秒值远小于 2^53，Lua 与 Java 之间按十进制串传递，不丢精度。
 *
 * <p><b>plan_version 统一填写</b>：每条 {@link WorldPlanOp.PutChannel} 加入时，记录的 {@code plan_version} 改成
 * {@link #writtenVersion()}（即写入成功后的版本号）——不论调用方填的是什么，规划器不必自己算。
 *
 * <p>非线程安全（一拍内在一个线程上组装）。
 */
public final class WorldPlanBatch {

    private final long expectedVersion;
    private final long writtenVersion;
    private final List<WorldPlanOp> ops = new ArrayList<>();

    /** 写入后的版本号 = 期望 ver + 1（测试与工具用；领导者用 {@link #forSnapshot}）。 */
    public WorldPlanBatch(long expectedVersion) {
        this(expectedVersion, expectedVersion + 1);
    }

    /**
     * @param expectedVersion 快照读到的版本号（{@link WorldPlanSnapshot#version()}；不存在为 0）
     * @param writtenVersion  写入成功后的版本号，必须大于 {@code expectedVersion}
     */
    public WorldPlanBatch(long expectedVersion, long writtenVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("期望版本号不能为负: " + expectedVersion);
        }
        if (writtenVersion <= expectedVersion) {
            throw new IllegalArgumentException("写入后的版本号必须大于期望版本号: " + writtenVersion + " ≤ " + expectedVersion);
        }
        this.expectedVersion = expectedVersion;
        this.writtenVersion = writtenVersion;
    }

    /** 领导者一拍的批次：写入后的版本号 = {@code max(快照 ver + 1, 快照的 Redis TIME 毫秒)}（见类注释）。 */
    public static WorldPlanBatch forSnapshot(WorldPlanSnapshot snapshot) {
        return new WorldPlanBatch(snapshot.version(), Math.max(snapshot.version() + 1, snapshot.nowMs()));
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    /** 这批写入成功后的版本号，也是本批改写的每条记录的 {@code plan_version}。 */
    public long writtenVersion() {
        return writtenVersion;
    }

    /** 追加一条改动；{@link WorldPlanOp.PutChannel} 的 plan_version 改成 {@link #writtenVersion()}。 */
    public WorldPlanBatch add(WorldPlanOp op) {
        if (op == null) {
            throw new IllegalArgumentException("改动不能为空");
        }
        if (op instanceof WorldPlanOp.PutChannel put && put.channel().getPlanVersion() != writtenVersion()) {
            WorldChannel stamped = put.channel().toBuilder().setPlanVersion(writtenVersion()).build();
            op = new WorldPlanOp.PutChannel(stamped);
        }
        ops.add(op);
        return this;
    }

    /** {@code S}：新增或改写一条频道记录。 */
    public WorldPlanBatch putChannel(WorldChannel channel) {
        return add(new WorldPlanOp.PutChannel(channel));
    }

    /** {@code D}：删一条频道记录。 */
    public WorldPlanBatch removeChannel(long sceneId) {
        return add(new WorldPlanOp.RemoveChannel(sceneId));
    }

    /** {@code Q}：改写期望频道数。 */
    public WorldPlanBatch setDesired(int sceneConfigId, int count) {
        return add(new WorldPlanOp.SetDesired(sceneConfigId, count));
    }

    /** {@code N}：播种期望频道数（已有值不覆盖）。 */
    public WorldPlanBatch seedDesired(int sceneConfigId, int count) {
        return add(new WorldPlanOp.SeedDesired(sceneConfigId, count));
    }

    /** {@code X}：删期望频道数。 */
    public WorldPlanBatch removeDesired(int sceneConfigId) {
        return add(new WorldPlanOp.RemoveDesired(sceneConfigId));
    }

    /** {@code C}：设冷却到期时刻（Redis TIME 毫秒）。 */
    public WorldPlanBatch setCooldown(int sceneConfigId, long untilMs) {
        return add(new WorldPlanOp.SetCooldown(sceneConfigId, untilMs));
    }

    /** {@code Y}：删冷却。 */
    public WorldPlanBatch removeCooldown(int sceneConfigId) {
        return add(new WorldPlanOp.RemoveCooldown(sceneConfigId));
    }

    /** 已加入的改动（按执行顺序，不可修改的视图）。 */
    public List<WorldPlanOp> ops() {
        return Collections.unmodifiableList(ops);
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }

    public int size() {
        return ops.size();
    }

    @Override
    public String toString() {
        return "WorldPlanBatch{expectedVersion=" + expectedVersion + ", writtenVersion=" + writtenVersion + ", ops=" + ops.size() + "}";
    }
}
