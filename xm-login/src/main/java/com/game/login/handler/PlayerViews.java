package com.game.login.handler;

import com.game.player.store.PlayerRow;
import com.game.proto.AccountSimplePlayer;
import com.game.proto.login.AccountSimplePlayerWrapper;
import java.util.ArrayList;
import java.util.List;

/** 存储行 → 客户端角色列表条目。只填契约里有的六个字段（这个消息没有 level）。 */
final class PlayerViews {

    private PlayerViews() {
    }

    static AccountSimplePlayerWrapper wrap(PlayerRow row) {
        return AccountSimplePlayerWrapper.newBuilder()
                .setPlayer(AccountSimplePlayer.newBuilder()
                        .setPlayerId(row.getPlayerId())
                        .setClassId(row.getClassId())
                        .setGender(row.getGender())
                        .setZoneId(row.getZoneId())
                        .setName(row.getName() == null ? "" : row.getName())
                        .setAppearanceId(row.getAppearanceId() == null ? "" : row.getAppearanceId()))
                .build();
    }

    /** 保持入参顺序（存储按建角先后排好，{@code players[0]} 是最早的角色）。 */
    static List<AccountSimplePlayerWrapper> wrapAll(List<PlayerRow> rows) {
        List<AccountSimplePlayerWrapper> out = new ArrayList<>(rows.size());
        for (PlayerRow row : rows) {
            out.add(wrap(row));
        }
        return out;
    }
}
