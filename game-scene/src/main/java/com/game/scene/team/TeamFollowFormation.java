package com.game.scene.team;

import com.game.scene.world.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

/**
 * 服务器驱动的组队跟随队形纯逻辑（对齐 C++ {@code TeamFollowFormation}，{@code team_follow_formation.{h,cpp}}）。
 *
 * <p>最多容纳 4 名随从（5 人队除队长外），按 {@code (joinSeq, playerId)} 排槽位，默认槽间距 1.8m、追赶速度 7.5m/s、
 * 最大落后 24m（超限由服务器直接瞬移放进槽位）；含 250ms/转弯 15°/变速 15% 移动广播节流判定。
 */
public final class TeamFollowFormation {

    public static final int MAX_MEMBERS = 4;
    /** 2026-11-19 对齐主仓定案：沿路槽位间距 3.0 m（原 1.4/1.8 m），与 50 m 视野匹配。 */
    public static final double DEFAULT_SPACING = 3.0;
    public static final double CATCH_UP_SPEED = 7.5;
    public static final double MAX_LAG = 24.0;
    /** 20 FPS 下 5 帧 = 250ms 周期校正广播。 */
    public static final int BROADCAST_INTERVAL_FRAMES = 5;
    /** 拐弯角度超过 15° 立即广播。 */
    public static final double TURN_BROADCAST_COS = Math.cos(Math.toRadians(15.0));
    /** 水平速率变化超过 15% 立即广播。 */
    public static final double SPEED_CHANGE_RATIO = 0.15;

    /** 跑图跟随三阵型样式（对齐 proto TeamFollowFormationStyle 与 C++ TeamFollowStyle）。 */
    public enum Style {
        COLUMN(0),
        ECHELON(1),
        WUXING(2);

        private final int protoValue;

        Style(int protoValue) {
            this.protoValue = protoValue;
        }

        public int protoValue() {
            return protoValue;
        }

        public static Style fromProtoValue(int value) {
            return switch (value) {
                case 1 -> ECHELON;
                case 2 -> WUXING;
                default -> COLUMN;
            };
        }
    }

    public static final class Member {
        public final long playerId;
        public final int joinSeq;
        public double cursorOdo;
        public double lateralOffset;
        public boolean suspended;
        public boolean needsPlacement;

        public Member(long playerId, int joinSeq, double cursorOdo) {
            this.playerId = playerId;
            this.joinSeq = joinSeq;
            this.cursorOdo = cursorOdo;
        }
    }

    public record Step(long playerId, Vec3 position, Vec3 trailPosition, double moved, boolean placed, boolean suspended) {
        public Step(long playerId, Vec3 position, double moved, boolean placed, boolean suspended) {
            this(playerId, position, position, moved, placed, suspended);
        }
    }

    public record BroadcastState(boolean moving, double vx, double vy) {
    }

    private final TeamFollowTrail trail;
    private final double spacing;
    private Style style;
    private final List<Member> members = new ArrayList<>();

    public TeamFollowFormation(Vec3 leaderStart) {
        this(leaderStart, DEFAULT_SPACING, Style.COLUMN);
    }

    public TeamFollowFormation(Vec3 leaderStart, double spacing) {
        this(leaderStart, spacing, Style.COLUMN);
    }

    public TeamFollowFormation(Vec3 leaderStart, double spacing, Style style) {
        this.trail = new TeamFollowTrail(leaderStart);
        this.spacing = spacing;
        this.style = style == null ? Style.COLUMN : style;
    }

    public Style style() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style == null ? Style.COLUMN : style;
    }

    public TeamFollowTrail trail() {
        return trail;
    }

    public double spacing() {
        return spacing;
    }

    public List<Member> members() {
        return Collections.unmodifiableList(members);
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public Member find(long playerId) {
        for (Member m : members) {
            if (m.playerId == playerId) {
                return m;
            }
        }
        return null;
    }

    public OptionalInt rankOf(long playerId) {
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).playerId == playerId) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    public int rankFor(int joinSeq, long playerId) {
        int rank = 0;
        for (Member m : members) {
            if (m.playerId != playerId && ranksBefore(m.joinSeq, m.playerId, joinSeq, playerId)) {
                rank++;
            }
        }
        return rank;
    }

    public boolean add(Member member) {
        if (member.playerId == 0 || find(member.playerId) != null || members.size() >= MAX_MEMBERS) {
            return false;
        }
        int pos = 0;
        while (pos < members.size()
                && ranksBefore(members.get(pos).joinSeq, members.get(pos).playerId, member.joinSeq, member.playerId)) {
            pos++;
        }
        members.add(pos, member);
        return true;
    }

    public boolean remove(long playerId) {
        return members.removeIf(m -> m.playerId == playerId);
    }

    public void setSuspended(long playerId, boolean suspended) {
        Member m = find(playerId);
        if (m != null) {
            m.suspended = suspended;
        }
    }

    public double slotLag(int rank) {
        return switch (style) {
            case ECHELON -> (rank / 2 + 1) * spacing;
            case WUXING -> switch (rank) {
                case 0, 1 -> 0.85 * spacing;
                case 2 -> 1.7 * spacing;
                default -> 2.55 * spacing;
            };
            case COLUMN -> (rank + 1) * spacing;
        };
    }

    public double targetLateral(int rank) {
        return switch (style) {
            case ECHELON -> switch (rank) {
                case 0 -> -2.0;
                case 1 -> 2.0;
                case 2 -> -3.6;
                case 3 -> 3.6;
                default -> 0.0;
            };
            case WUXING -> switch (rank) {
                case 0 -> -2.4;
                case 1 -> 2.4;
                default -> 0.0;
            };
            case COLUMN -> 0.0;
        };
    }

    public double slotOdo(int rank) {
        return Math.max(trail.tailOdo(), trail.headOdo() - slotLag(rank));
    }

    /** 按 1.0x -> 0.5x -> 0.0x 导航校验安全横向展开，贴墙/窄道自动收拢回队长安全轨迹点。 */
    public Vec3 resolveSlotPoint(double odo, double desiredLateral, TeamFollowTrail.WalkableCheck walkable,
                                 double[] appliedLateralOut) {
        Vec3 base = trail.pointAt(odo);
        if (appliedLateralOut != null && appliedLateralOut.length > 0) {
            appliedLateralOut[0] = 0.0;
        }
        if (Math.abs(desiredLateral) < 1e-4 || (trail.headOdo() - trail.tailOdo()) < TeamFollowTrail.MIN_STEP) {
            return base;
        }
        double halfWindow = 0.5;
        Vec3 back = trail.pointAt(Math.max(trail.tailOdo(), odo - halfWindow));
        Vec3 front = trail.pointAt(Math.min(trail.headOdo(), odo + halfWindow));
        double dx = front.x() - back.x();
        double dy = front.y() - back.y();
        double len = Math.hypot(dx, dy);
        if (len < 1e-3) {
            return base;
        }
        double rx = dy / len;
        double ry = -dx / len;
        for (double scale : new double[]{1.0, 0.5}) {
            double offset = desiredLateral * scale;
            Vec3 candidate = new Vec3(base.x() + rx * offset, base.y() + ry * offset, base.z());
            if (walkable == null || walkable.walkable(base, candidate)) {
                if (appliedLateralOut != null && appliedLateralOut.length > 0) {
                    appliedLateralOut[0] = offset;
                }
                return candidate;
            }
        }
        return base;
    }

    public Vec3 slotPosition(int rank, TeamFollowTrail.WalkableCheck walkable) {
        return resolveSlotPoint(slotOdo(rank), targetLateral(rank), walkable, null);
    }

    public double protectOdo() {
        double protect = -Double.MAX_VALUE;
        for (Member m : members) {
            if (!m.needsPlacement) {
                protect = Math.max(protect, m.cursorOdo);
            }
        }
        return protect;
    }

    public double keepOdo() {
        double keep = trail.headOdo() - (MAX_MEMBERS + 1) * spacing;
        for (Member m : members) {
            if (!m.needsPlacement) {
                keep = Math.min(keep, m.cursorOdo);
            }
        }
        return keep;
    }

    public void trimTailForNewcomer(int rank) {
        double trimTo = trail.headOdo() - slotLag(rank);
        for (Member m : members) {
            if (!m.needsPlacement) {
                trimTo = Math.min(trimTo, m.cursorOdo);
            }
        }
        trail.trimTailTo(trimTo);
    }

    /** 推进一帧：喂入队长位置、为每名随从计算步进 / 横向展开 / 瞬移放槽 / 暂停，并修剪不再需要的旧轨迹。 */
    public List<Step> tick(Vec3 leader, double deltaSeconds, TeamFollowTrail.WalkableCheck walkable) {
        double headBefore = trail.headOdo();
        trail.feed(leader, protectOdo(), walkable);
        double headAdvance = Math.max(0.0, trail.headOdo() - headBefore);
        double maxStep = Math.max(headAdvance, CATCH_UP_SPEED * Math.max(0.0, deltaSeconds));

        double[] appliedOut = new double[1];
        List<Step> out = new ArrayList<>(members.size());
        for (int rank = 0; rank < members.size(); rank++) {
            Member m = members.get(rank);
            double slot = slotOdo(rank);
            if (m.suspended) {
                if (!m.needsPlacement && slot - m.cursorOdo > MAX_LAG) {
                    m.needsPlacement = true;
                }
                Vec3 p = trail.pointAt(m.cursorOdo);
                out.add(new Step(m.playerId, p, p, 0.0, false, true));
                continue;
            }
            if (m.needsPlacement || slot - m.cursorOdo > MAX_LAG) {
                m.cursorOdo = slot;
                m.needsPlacement = false;
                Vec3 trailPos = trail.pointAt(slot);
                Vec3 resolved = resolveSlotPoint(slot, targetLateral(rank), walkable, appliedOut);
                m.lateralOffset = appliedOut[0];
                out.add(new Step(m.playerId, resolved, trailPos, 0.0, true, false));
                continue;
            }
            double next = m.cursorOdo < slot ? Math.min(slot, m.cursorOdo + maxStep) : m.cursorOdo;
            double odoAdvance = next - m.cursorOdo;
            m.cursorOdo = next;
            Vec3 trailPos = trail.pointAt(next);

            Vec3 safeTargetPoint = resolveSlotPoint(next, targetLateral(rank), walkable, appliedOut);
            double safeTargetLateral = appliedOut[0];
            double lateralDiff = safeTargetLateral - m.lateralOffset;
            double desiredNextLateral = Math.abs(lateralDiff) <= maxStep
                    ? safeTargetLateral
                    : m.lateralOffset + Math.copySign(maxStep, lateralDiff);
            Vec3 resolved = resolveSlotPoint(next, desiredNextLateral, walkable, appliedOut);
            double appliedLateral = appliedOut[0];
            if (Math.abs(appliedLateral - desiredNextLateral) > 1e-4
                    && Math.abs(desiredNextLateral - safeTargetLateral) > 1e-4) {
                resolved = safeTargetPoint;
                appliedLateral = safeTargetLateral;
            }
            double lateralMoved = Math.abs(appliedLateral - m.lateralOffset);
            m.lateralOffset = appliedLateral;
            double moved = (odoAdvance > 0.0 || lateralMoved > 1e-4) ? Math.hypot(odoAdvance, lateralMoved) : 0.0;
            out.add(new Step(m.playerId, resolved, trailPos, moved, false, false));
        }
        trail.pruneBefore(keepOdo());
        return out;
    }

    /** 广播节流判定（起步/停步、250ms 周期、15° 拐弯、15% 速率变化）。 */
    public static boolean shouldBroadcast(BroadcastState last, BroadcastState now, long framesSinceLast) {
        if (last.moving() != now.moving()) {
            return true;
        }
        if (!now.moving()) {
            return false;
        }
        if (framesSinceLast >= BROADCAST_INTERVAL_FRAMES) {
            return true;
        }
        double lastSpeed = Math.hypot(last.vx(), last.vy());
        double speed = Math.hypot(now.vx(), now.vy());
        if (lastSpeed <= 0.0 || speed <= 0.0) {
            return true;
        }
        double cosTurn = (last.vx() * now.vx() + last.vy() * now.vy()) / (lastSpeed * speed);
        if (cosTurn < TURN_BROADCAST_COS) {
            return true;
        }
        return Math.abs(speed - lastSpeed) > SPEED_CHANGE_RATIO * lastSpeed;
    }

    private static boolean ranksBefore(int seqA, long idA, int seqB, long idB) {
        if (seqA != seqB) {
            return Integer.compareUnsigned(seqA, seqB) < 0;
        }
        return Long.compareUnsigned(idA, idB) < 0;
    }
}
