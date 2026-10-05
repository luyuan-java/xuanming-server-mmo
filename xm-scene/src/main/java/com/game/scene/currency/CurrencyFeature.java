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
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.player.Wallet;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;

/**
 * 货币的客户端方法（{@code SceneCurrencyClientPlayer}）：54 GetCurrencyList 与 GM 的加 / 扣 / 封禁 / 解封（37 / 49 / 94 / 95）。
 * GM 方法先过运行模式闸（gate 一道、scene 分发入口一道，见 {@code ClientRequestHandler}），这里只做业务。
 * 规则与 tip 码见 {@link Wallet}；成功的变动经 {@link CurrencyService} 记一条资产流水（GM 发放 / 扣除单独记原因）。
 */
public final class CurrencyFeature implements SceneFeature {

    private static final String SERVICE = "SceneCurrencyClientPlayer";

    private final CurrencyService currency;

    public CurrencyFeature(CurrencyService currency) {
        this.currency = currency;
    }

    /**
     * 冻结策略（scene-handoff-spec §5.9）：54 只读；37 / 49 GATED，由 {@link CurrencyService} 的冻结闸回 27003；
     * 94 / 95 冻结中在入口回 1005（REJECT，同基线 player_currency_handler 的冻结拒绝），服务层同样有闸。
     */
    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetCurrencyList", GetCurrencyListRequest.class, FreezePolicy.READ_ONLY, (call, req) -> call.reply(
                GetCurrencyListResponse.newBuilder().setErrorMessage(tip(0)).setCurrency(call.player().wallet().toClient()).build()));
        r.on(SERVICE, "GmAddCurrency", GmAddCurrencyRequest.class, FreezePolicy.GATED, this::gmAdd);
        r.on(SERVICE, "GmDeductCurrency", GmDeductCurrencyRequest.class, FreezePolicy.GATED, this::gmDeduct);
        r.on(SERVICE, "GmBlockCurrency", GmBlockCurrencyRequest.class, FreezePolicy.REJECT, (call, req) -> call.reply(
                GmBlockCurrencyResponse.newBuilder()
                        .setErrorMessage(tip(currency.block(call.player(), req.getCurrencyType()))).build()));
        r.on(SERVICE, "GmUnblockCurrency", GmUnblockCurrencyRequest.class, FreezePolicy.REJECT, (call, req) -> call.reply(
                GmUnblockCurrencyResponse.newBuilder()
                        .setErrorMessage(tip(currency.unblock(call.player(), req.getCurrencyType()))).build()));
    }

    /** 37：成功回 {@code balance_after}；拒绝只回 tip（同基线：失败时不设 balance_after）。 */
    private void gmAdd(PlayerCall call, GmAddCurrencyRequest request) {
        Wallet.Change change = currency.add(call.player(), request.getCurrencyType(), request.getAmount(), Reason.GM_GRANT);
        GmAddCurrencyResponse.Builder response = GmAddCurrencyResponse.newBuilder().setErrorMessage(tip(change.tipId()));
        if (change.ok()) {
            response.setBalanceAfter(change.after());
        }
        call.reply(response.build());
    }

    /** 49：同 37。 */
    private void gmDeduct(PlayerCall call, GmDeductCurrencyRequest request) {
        Wallet.Change change = currency.deduct(call.player(), request.getCurrencyType(), request.getAmount(),
                Reason.GM_DEDUCT);
        GmDeductCurrencyResponse.Builder response = GmDeductCurrencyResponse.newBuilder().setErrorMessage(tip(change.tipId()));
        if (change.ok()) {
            response.setBalanceAfter(change.after());
        }
        call.reply(response.build());
    }
}
