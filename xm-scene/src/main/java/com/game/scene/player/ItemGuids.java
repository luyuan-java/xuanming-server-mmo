package com.game.scene.player;

/** 物品 guid 源：一次铸齐一批（全服唯一、永不为 0）。拿不到就整批放弃，调用方据此零写入地拒绝（基线 CanMintGuids）。 */
@FunctionalInterface
public interface ItemGuids {

    /** 铸 {@code count} 个号；有任何一个发不出来返回 null（已发出的号作废，雪花号允许空洞）。 */
    long[] tryMint(int count);
}
