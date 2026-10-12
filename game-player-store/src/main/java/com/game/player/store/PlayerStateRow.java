package com.game.player.store;

/**
 * {@code player_state} 表的一行（只取数据列）。MyBatis 把方法返回值 {@code byte[]} 当成「多行 byte」，
 * 所以用一个 JavaBean 承接 BLOB 列。
 */
public class PlayerStateRow {

    private byte[] data;

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
