package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.CreateInstanceRequest;
import com.game.scenemanager.InstanceIdIssuer.Issue;
import com.game.scenemanager.InstanceIdIssuer.Result;
import com.game.scenemanager.world.NodeAvailability;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 实例取号（批次 5.3，dungeon-mirror-spec §6.6、§12.3 InstanceIdIssuerTest）：参数校验各分支回 3005 且不发号；租约无效 → NO_LEASE（提供方转调用失败）；
 * 放置 = 发起节点（基线 TestCreateScene_Mirror_SingleNode_Trivial / _SameZoneSourceColocates）；号非 0 且两次不同；节点不接新实例（5.5 钩子）回 3000；
 * 不碰 Redis（构造里没有任何存储，只有发号器）。
 */
class InstanceIdIssuerTest {

    private static final CreateInstanceRequest MIRROR = CreateInstanceRequest.newBuilder()
            .setZoneId(1).setRequesterSceneNodeId(7).setPlayerId(42).setKind(ChannelKind.CHANNEL_KIND_MIRROR)
            .setSourceSceneId(0x8000_0000_0000_0101L).setSceneConfigId(1).setMirrorConfigId(1).build();
    private static final CreateInstanceRequest DUNGEON = CreateInstanceRequest.newBuilder()
            .setZoneId(1).setRequesterSceneNodeId(7).setKind(ChannelKind.CHANNEL_KIND_DUNGEON)
            .setSceneConfigId(17).setDungeonConfigId(1).build();

    private boolean leaseValid = true;
    private final SceneIdAllocator ids = SceneIdAllocator.forTesting(5, () -> leaseValid);
    private final List<Integer> availabilityAsked = new ArrayList<>();
    private final InstanceIdIssuer issuer = new InstanceIdIssuer(ids, (zone, node) -> {
        availabilityAsked.add(node);
        return true;
    });

    @Test
    void 镜像放在发起节点_号非0且每次不同() {
        Issue first = issuer.issue(MIRROR);
        Issue second = issuer.issue(MIRROR);

        assertThat(first.result()).isEqualTo(Result.OK);
        assertThat(first.response().getTipId()).isZero();
        assertThat(first.response().getSceneNodeId()).isEqualTo(7);
        assertThat(first.response().getSceneId()).isNotZero();
        assertThat(second.response().getSceneId()).isNotEqualTo(first.response().getSceneId());
        assertThat(availabilityAsked).containsExactly(7, 7);
    }

    @Test
    void 副本同样放在发起节点_玩家号可为0() {
        Issue issue = issuer.issue(DUNGEON);

        assertThat(issue.result()).isEqualTo(Result.OK);
        assertThat(issue.response().getSceneNodeId()).isEqualTo(7);
        assertThat(issue.response().getSceneId()).isNotZero();
    }

    @Test
    void 参数不合法各分支都回3005_不发号也不问节点可用性() {
        List<CreateInstanceRequest> bad = List.of(
                edit(MIRROR, b -> b.setZoneId(0)),
                edit(MIRROR, b -> b.setRequesterSceneNodeId(0)),
                edit(MIRROR, b -> b.setSceneConfigId(0)),
                edit(MIRROR, b -> b.setKind(ChannelKind.CHANNEL_KIND_UNSPECIFIED)),
                edit(MIRROR, b -> b.setKind(ChannelKind.CHANNEL_KIND_WORLD)),
                edit(MIRROR, b -> b.setKindValue(9)),
                edit(MIRROR, b -> b.setSourceSceneId(0)),
                edit(MIRROR, b -> b.setMirrorConfigId(0)),
                edit(MIRROR, b -> b.setPlayerId(0)),
                edit(MIRROR, b -> b.setDungeonConfigId(1)),
                edit(DUNGEON, b -> b.setDungeonConfigId(0)),
                edit(DUNGEON, b -> b.setSourceSceneId(5)),
                edit(DUNGEON, b -> b.setMirrorConfigId(1)),
                edit(DUNGEON, b -> b.setSceneConfigId(0)));
        int[] issued = {0};
        InstanceIdIssuer counting = new InstanceIdIssuer(() -> {
            issued[0]++;
            return OptionalLong.of(1);
        }, (zone, node) -> {
            availabilityAsked.add(node);
            return true;
        });

        for (CreateInstanceRequest request : bad) {
            Issue issue = counting.issue(request);
            assertThat(issue.result()).as(request.toString()).isEqualTo(Result.BAD_REQUEST);
            assertThat(issue.response().getTipId()).isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
            assertThat(issue.response().getSceneId()).isZero();
            assertThat(issue.response().getSceneNodeId()).isZero();
        }
        assertThat(availabilityAsked).isEmpty();
        assertThat(issued[0]).as("参数错不发号").isZero();
    }

    @Test
    void mirror_config_id超过2的31次方也算非0() {
        Issue issue = issuer.issue(edit(MIRROR, b -> b.setMirrorConfigId(0x8000_0000)));

        assertThat(issue.result()).isEqualTo(Result.OK);
    }

    @Test
    void 租约无效时NO_LEASE_没有应答_租约恢复后照常() {
        leaseValid = false;

        Issue issue = issuer.issue(MIRROR);

        assertThat(issue.result()).isEqualTo(Result.NO_LEASE);
        assertThat(issue.response()).isNull();

        leaseValid = true;
        assertThat(issuer.issue(MIRROR).result()).isEqualTo(Result.OK);
    }

    @Test
    void 租约确认丢失后永久NO_LEASE() {
        ids.leaseLost();

        assertThat(issuer.issue(MIRROR).result()).isEqualTo(Result.NO_LEASE);
        assertThat(issuer.issue(DUNGEON).result()).isEqualTo(Result.NO_LEASE);
    }

    @Test
    void 发起节点不接新实例时回3000_不发号_5_5钩子() {
        InstanceIdIssuer counting = new InstanceIdIssuer(() -> {
            throw new AssertionError("不该发号");
        }, (zone, node) -> false);

        Issue issue = counting.issue(MIRROR);

        assertThat(issue.result()).isEqualTo(Result.NODE_UNAVAILABLE);
        assertThat(issue.response().getTipId()).isEqualTo(InstanceIdIssuer.TIP_NODE_UNAVAILABLE).isEqualTo(3000);
    }

    @Test
    void 发号器为空时NO_LEASE() {
        InstanceIdIssuer empty = new InstanceIdIssuer(OptionalLong::empty, NodeAvailability.ALL);

        assertThat(empty.issue(MIRROR).result()).isEqualTo(Result.NO_LEASE);
    }

    private static CreateInstanceRequest edit(CreateInstanceRequest base, UnaryOperator<CreateInstanceRequest.Builder> change) {
        return change.apply(base.toBuilder()).build();
    }
}
