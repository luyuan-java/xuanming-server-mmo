package com.game.scene.currency;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.GmBlockCurrencyRequest;
import com.game.proto.GmBlockCurrencyResponse;
import com.game.proto.GmDeductCurrencyRequest;
import com.game.proto.GmDeductCurrencyResponse;
import com.game.proto.GmUnblockCurrencyRequest;
import com.game.proto.GmUnblockCurrencyResponse;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.player.Wallet;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;

/**
 * 货币的客户端方法（{@code SceneCurrencyClientPlayer}）：54 GetCurrencyList 与 GM 的加 / 扣 / 封禁 / 解封（37 / 49 / 94 / 95）。
 * GM 方法先过运行模式闸（gate 一道、scene 分发入口一道，见 {@code ClientRequestHandler}），这里只做业务。
 * 规则与 tip 码见 {@link Wallet}；成功的变动记一条资产流水（{@link AssetAudit}，GM 发放 / 扣除单独记原因）。
 */
public final class CurrencyFeature implements SceneFeature {

    private static final String SERVICE = "SceneCurrencyClientPlayer";

    private final AssetAudit audit;

    public CurrencyFeature(AssetAudit audit) {
        this.audit = audit;
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetCurrencyList", GetCurrencyListRequest.class, (call, req) -> call.reply(
                GetCurrencyListResponse.newBuilder().setErrorMessage(tip(0)).setCurrency(call.player().wallet().toClient()).build()));
        r.on(SERVICE, "GmAddCurrency", GmAddCurrencyRequest.class, this::gmAdd);
        r.on(SERVICE, "GmDeductCurrency", GmDeductCurrencyRequest.class, this::gmDeduct);
        r.on(SERVICE, "GmBlockCurrency", GmBlockCurrencyRequest.class, (call, req) -> call.reply(
                GmBlockCurrencyResponse.newBuilder()
                        .setErrorMessage(tip(call.player().wallet().block(req.getCurrencyType()))).build()));
        r.on(SERVICE, "GmUnblockCurrency", GmUnblockCurrencyRequest.class, (call, req) -> call.reply(
                GmUnblockCurrencyResponse.newBuilder()
                        .setErrorMessage(tip(call.player().wallet().unblock(req.getCurrencyType()))).build()));
    }

    /** 37：成功回 {@code balance_after}；拒绝只回 tip（同基线：失败时不设 balance_after）。 */
    private void gmAdd(PlayerCall call, GmAddCurrencyRequest request) {
        Wallet.Change change = call.player().wallet().add(request.getCurrencyType(), request.getAmount());
        GmAddCurrencyResponse.Builder response = GmAddCurrencyResponse.newBuilder().setErrorMessage(tip(change.tipId()));
        if (change.ok()) {
            audit.currencyChanged(call.player().playerId(), change.type(), request.getAmount(), change.before(),
                    change.after(), Reason.GM_GRANT);
            response.setBalanceAfter(change.after());
        }
        call.reply(response.build());
    }

    /** 49：同 37。 */
    private void gmDeduct(PlayerCall call, GmDeductCurrencyRequest request) {
        Wallet.Change change = call.player().wallet().deduct(request.getCurrencyType(), request.getAmount());
        GmDeductCurrencyResponse.Builder response = GmDeductCurrencyResponse.newBuilder().setErrorMessage(tip(change.tipId()));
        if (change.ok()) {
            audit.currencyChanged(call.player().playerId(), change.type(), -request.getAmount(), change.before(),
                    change.after(), Reason.GM_DEDUCT);
            response.setBalanceAfter(change.after());
        }
        call.reply(response.build());
    }
}
