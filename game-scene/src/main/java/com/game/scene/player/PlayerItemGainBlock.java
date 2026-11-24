package com.game.scene.player;

import com.game.player.store.state.ItemGainBlockState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * 个人物品获取封锁清单（GM 收件箱类型 5 / 6；只在场景逻辑线程上读写）。
 * 按 {@code item_config_id} 无符号升序维护并落盘，保留未知字段以便滚动升级与回滚往返。
 */
public final class PlayerItemGainBlock {

    private final TreeSet<Integer> blockedIds = new TreeSet<>(Integer::compareUnsigned);
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerItemGainBlock() {
    }

    public static PlayerItemGainBlock empty() {
        return new PlayerItemGainBlock();
    }

    public static PlayerItemGainBlock restore(ItemGainBlockState state) {
        PlayerItemGainBlock block = new PlayerItemGainBlock();
        if (state == null) {
            return block;
        }
        for (int id : state.getBlockedItemConfigIdsList()) {
            if (id != 0) {
                block.blockedIds.add(id);
            }
        }
        block.unknownFields = state.getUnknownFields();
        return block;
    }

    public boolean isBlocked(int itemConfigId) {
        return itemConfigId != 0 && blockedIds.contains(itemConfigId);
    }

    /** 幂等封锁指定物品配置号；返回是否首次加入。 */
    public boolean block(int itemConfigId) {
        return itemConfigId != 0 && blockedIds.add(itemConfigId);
    }

    /** 幂等解封指定物品配置号；返回是否实际移除。 */
    public boolean unblock(int itemConfigId) {
        return itemConfigId != 0 && blockedIds.remove(itemConfigId);
    }

    public Set<Integer> blockedIds() {
        return Collections.unmodifiableSet(blockedIds);
    }

    public boolean isPristine() {
        return blockedIds.isEmpty() && unknownFields.asMap().isEmpty();
    }

    public ItemGainBlockState toState() {
        ItemGainBlockState.Builder b = ItemGainBlockState.newBuilder().setUnknownFields(unknownFields);
        for (int id : blockedIds) {
            b.addBlockedItemConfigIds(id);
        }
        return b.build();
    }
}
