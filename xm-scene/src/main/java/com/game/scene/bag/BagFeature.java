package com.game.scene.bag;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.proto.BagInfo;
import com.game.proto.BagItemInfo;
import com.game.proto.BagLayoutInfo;
import com.game.proto.BagSlotInfo;
import com.game.proto.GetBagRequest;
import com.game.proto.GetBagResponse;
import com.game.proto.SortBagRequest;
import com.game.proto.SortBagResponse;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemCatalog.ItemSpec;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 背包的客户端方法（{@code SceneBagClientPlayer}）：191 GetBag 读取、192 SortBag 整理（基线 player_bag_handler + PlayerBagSystem）。
 *
 * <p>GetBag 纯读取：bag_type（uint32，按无符号看）不是 0–3 回 1005；成功回整包快照——物品按 item_id（无符号）升序、
 * 格子按格子号升序、每格 1×1，表里查得到才填 max_stack / equip_kind，名字与图标恒空（表没有展示列），{@code layout} 总在
 * （空包也带容量，客户端 / robot 缺了它就当失败），{@code currency} 是当前钱包（同 54）。
 * SortBag 只允许人物背包与仓库（否则 1005），合并 + 重排后回整包快照与 {@code changed}（已经最优时 false、什么都不动）。
 * 失败只回 tip（不带 bag、不带 changed）。成功也带 {@code error_message{id:0}}（同基线线上形态）。
 * 基线还有 1009（实体无效）、1003（背包组件缺失）、1002（两层不一致）——Java 里玩家从会话解析、背包总在、两层由容器保证一致，到不了。
 */
public final class BagFeature implements SceneFeature {

    private static final String SERVICE = "SceneBagClientPlayer";
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;

    private final BagService bags;

    public BagFeature(BagService bags) {
        this.bags = bags;
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetBag", GetBagRequest.class, this::getBag);
        r.on(SERVICE, "SortBag", SortBagRequest.class, this::sortBag);
    }

    private void getBag(PlayerCall call, GetBagRequest request) {
        BagType type = BagType.ofCode(request.getBagType());
        if (type == null) {
            call.reply(GetBagResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build());
            return;
        }
        call.reply(GetBagResponse.newBuilder().setErrorMessage(tip(0)).setBag(snapshot(call.player(), type)).build());
    }

    private void sortBag(PlayerCall call, SortBagRequest request) {
        BagType type = BagType.ofCode(request.getBagType());
        // 战斗中禁止整理（基线 InBattleComp → 1005）随回合制战斗（6.3）接入
        Bag.SortResult result = type == null || !type.sortable() ? null : bags.sortByPlayer(call.player(), type);
        if (result == null) {
            call.reply(SortBagResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build());
            return;
        }
        call.reply(SortBagResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setBag(snapshot(call.player(), type))
                .setChanged(result.changed())
                .build());
    }

    private BagInfo snapshot(ScenePlayer player, BagType type) {
        Bag bag = player.bags().bag(type);
        List<BagItem> items = bag.items();
        List<BagItem> byGuid = new ArrayList<>(items);
        byGuid.sort(Comparator.comparing(BagItem::guid, Long::compareUnsigned));
        BagInfo.Builder info = BagInfo.newBuilder();
        for (BagItem item : byGuid) {
            BagItemInfo.Builder entry = BagItemInfo.newBuilder()
                    .setItemId(item.guid())
                    .setConfigId(item.configId())
                    .setCount((int) item.size());
            ItemSpec spec = bags.tables().item(item.configId());
            if (spec != null) {
                entry.setMaxStack(spec.maxStackSize()).setEquipKind(spec.equipKind());
            }
            info.addItems(entry);
        }
        BagLayoutInfo.Builder layout = BagLayoutInfo.newBuilder()
                .setBagType(type.code())
                .setCapacity(bag.capacity())
                .setCanSort(type.sortable());
        for (BagItem item : items) {
            layout.addSlots(BagSlotInfo.newBuilder().setSlot(item.slot()).setItemId(item.guid()).setWidth(1).setHeight(1));
        }
        return info.setLayout(layout).setCurrency(player.wallet().toClient()).build();
    }
}
