package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetStream;
import com.game.player.store.asset.AssetLedgerRules;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 共用账本规则（xm-player-store {@link AssetLedgerRules}，不依赖 xm-api）的合法流号是常量集合；这里把它与 xm-api 的 {@link AssetStream} 枚举逐项对拍
 * （guild-economy-spec Q3a）。枚举加了新流而常量没跟上时这里先红：否则 scene 会把带新流的账本判成损坏（fail-closed，但该玩家的资产通道全关）。
 * 同时钉住 scene 的流规则表只覆盖这些流。
 */
class AssetStreamPinTest {

    @Test
    void 合法流号常量集合等于AssetStream枚举除UNSPECIFIED之外的全部取值() {
        Set<Integer> fromEnum = Arrays.stream(AssetStream.values())
                .filter(s -> s != AssetStream.UNRECOGNIZED && s != AssetStream.ASSET_STREAM_UNSPECIFIED)
                .map(AssetStream::getNumber)
                .collect(Collectors.toSet());
        assertThat(AssetLedgerRules.VALID_STREAMS).isEqualTo(fromEnum);
        assertThat(AssetLedgerRules.isValidStream(AssetStream.ASSET_STREAM_UNSPECIFIED_VALUE)).isFalse();
    }

    @Test
    void scene的流规则表覆盖且只覆盖合法流() {
        Set<Integer> ruled = AssetOpService.ruledStreams().stream().map(AssetStream::getNumber).collect(Collectors.toSet());
        assertThat(ruled).isEqualTo(AssetLedgerRules.VALID_STREAMS);
    }
}
