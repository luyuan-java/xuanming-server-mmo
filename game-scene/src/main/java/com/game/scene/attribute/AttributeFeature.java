package com.game.scene.attribute;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AllocateAttributePointsRequest;
import com.game.proto.AllocateAttributePointsResponse;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.AttributePanelInfo;
import com.game.proto.AutoAllocateAttributePointsRequest;
import com.game.proto.AutoAllocateAttributePointsResponse;
import com.game.proto.CreateAttributeSchemeRequest;
import com.game.proto.CreateAttributeSchemeResponse;
import com.game.proto.GetAttributePanelRequest;
import com.game.proto.GetAttributePanelResponse;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.GmSetPlayerLevelResponse;
import com.game.proto.RenameAttributeSchemeRequest;
import com.game.proto.RenameAttributeSchemeResponse;
import com.game.proto.ResetAttributePointsRequest;
import com.game.proto.ResetAttributePointsResponse;
import com.game.proto.SwitchAttributeSchemeRequest;
import com.game.proto.SwitchAttributeSchemeResponse;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;

/**
 * 属性加点的客户端方法（{@code SceneAttributeClientPlayer}，167–175）。规则在 {@link AttributeService}，这里只翻译协议：
 * <ul>
 *   <li>成功：{@code error_message{0}} + 全量面板（173 是 {@code pool_id} 回显 + 建议，174 另带新方案 id）；</li>
 *   <li>拒绝：只回 {@code error_message{拒绝码}}，不带面板 / 建议（同基线：客户端保留旧面板）；</li>
 *   <li>170 面板推送只在等级变化后发（基线唯一的推送点）：其它写操作的应答已带全量面板，再推会冲掉客户端未确认的拖动。</li>
 * </ul>
 * 175 GmSetPlayerLevel 先过运行模式闸（gate 一道、scene 分发入口一道），这里只做业务。
 */
public final class AttributeFeature implements SceneFeature {

    private static final String SERVICE = "SceneAttributeClientPlayer";

    private final AttributeService service;
    private final int notifyPanelChanged;
    private final LevelListener levelListener;

    /** 等级设定成功后的连带（宝宝跟着重算并推 184、任务的等级条件事实），在推 170 之后、回应答之前按基线顺序调。 */
    @FunctionalInterface
    public interface LevelListener {
        void levelChanged(PlayerCall call);
    }

    public AttributeFeature(AttributeService service, MessageIdRegistry registry, LevelListener levelListener) {
        this.service = service;
        this.levelListener = levelListener;
        this.notifyPanelChanged = registry.requireId(SERVICE, "NotifyAttributePanelChanged");
    }

    /**
     * 冻结策略（scene-handoff-spec §5.9）：167 只读；写操作 GATED，由 {@link AttributeService} 的写前置在冻结中回 1005；
     * 173 自动加点不过写前置（同基线），冻结中在入口回 1005（REJECT，D9）。
     */
    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetAttributePanel", GetAttributePanelRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, req) -> call.reply(
                        GetAttributePanelResponse.newBuilder().setErrorMessage(tip(0)).setPanel(panel(call)).build()));
        r.on(SERVICE, "AllocateAttributePoints", AllocateAttributePointsRequest.class, FreezePolicy.GATED, BattlePolicy.GATED,
                this::allocate);
        r.on(SERVICE, "ResetAttributePoints", ResetAttributePointsRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, req) -> {
            int tipId = service.reset(call.player(), req.getPoolId());
            ResetAttributePointsResponse.Builder response = ResetAttributePointsResponse.newBuilder()
                    .setErrorMessage(tip(tipId));
            if (tipId == 0) {
                response.setPanel(panel(call));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "AutoAllocateAttributePoints", AutoAllocateAttributePointsRequest.class, FreezePolicy.REJECT, BattlePolicy.ALLOW,
                this::autoAllocate);
        r.on(SERVICE, "CreateAttributeScheme", CreateAttributeSchemeRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, req) -> {
            AttributeService.SchemeCreation created = service.createScheme(call.player(), req.getName());
            CreateAttributeSchemeResponse.Builder response = CreateAttributeSchemeResponse.newBuilder()
                    .setErrorMessage(tip(created.tipId()));
            if (created.tipId() == 0) {
                response.setSchemeId(created.schemeId()).setPanel(panel(call));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "SwitchAttributeScheme", SwitchAttributeSchemeRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, req) -> {
            int tipId = service.switchScheme(call.player(), req.getSchemeId());
            SwitchAttributeSchemeResponse.Builder response = SwitchAttributeSchemeResponse.newBuilder()
                    .setErrorMessage(tip(tipId));
            if (tipId == 0) {
                response.setPanel(panel(call));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "RenameAttributeScheme", RenameAttributeSchemeRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, req) -> {
            int tipId = service.renameScheme(call.player(), req.getSchemeId(), req.getName());
            RenameAttributeSchemeResponse.Builder response = RenameAttributeSchemeResponse.newBuilder()
                    .setErrorMessage(tip(tipId));
            if (tipId == 0) {
                response.setPanel(panel(call));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "GmSetPlayerLevel", GmSetPlayerLevelRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, this::gmSetLevel);
    }

    /** 168：池号为 0 或目标为空 → 1005（先于写前置，同基线处理器）。 */
    private void allocate(PlayerCall call, AllocateAttributePointsRequest request) {
        int tipId = request.getPoolId() == 0 || request.getAllocatedCount() == 0
                ? AttributeService.INVALID_PARAMETER
                : service.allocate(call.player(), request.getPoolId(), request.getAllocatedMap());
        AllocateAttributePointsResponse.Builder response = AllocateAttributePointsResponse.newBuilder()
                .setErrorMessage(tip(tipId));
        if (tipId == 0) {
            response.setPanel(panel(call));
        }
        call.reply(response.build());
    }

    /** 173：成功时回显池号并带建议（全部维度的目标已分配）；拒绝只回 tip。 */
    private void autoAllocate(PlayerCall call, AutoAllocateAttributePointsRequest request) {
        AttributeService.AutoAllocation result = service.autoAllocate(call.player(), request.getPoolId());
        AutoAllocateAttributePointsResponse.Builder response = AutoAllocateAttributePointsResponse.newBuilder()
                .setErrorMessage(tip(result.tipId()));
        if (result.tipId() == 0) {
            response.setPoolId(request.getPoolId());
            result.suggested().forEach((dimension, points) -> response.putSuggested(dimension, (int) (long) points));
        }
        call.reply(response.build());
    }

    /**
     * 175：设等级成功后按基线升级事件的顺序做连带——（已重算）→ 推 170 → 宝宝重算并推 184 → 任务等级条件事实
     * （等级没变也发，同基线）→ 回应答（应答里的面板在推送之后构建）。
     */
    private void gmSetLevel(PlayerCall call, GmSetPlayerLevelRequest request) {
        int tipId = service.gmSetLevel(call.player(), request.getLevel());
        GmSetPlayerLevelResponse.Builder response = GmSetPlayerLevelResponse.newBuilder().setErrorMessage(tip(tipId));
        if (tipId == 0) {
            call.push(notifyPanelChanged, AttributePanelChangedS2C.newBuilder().setPanel(panel(call)).build());
            levelListener.levelChanged(call);
            response.setPanel(panel(call));
        }
        call.reply(response.build());
    }

    private AttributePanelInfo panel(PlayerCall call) {
        return service.buildPanel(call.player());
    }
}
