package com.game.gateway.store;

/** {@code zone_whitelist} 的一行；zoneId 按 uint32 位模式保存，0 非法。 */
public record WhitelistRow(int zoneId, String account, String note) {
}
