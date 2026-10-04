package com.game.player.store;

/**
 * 账号的口令记录（生产口令认证只读它）。
 *
 * @param account      库里的账号（规范值：认证通过后用它，不回显客户端输入）
 * @param passwordHash Argon2id PHC 串；null = 该账号不能口令登录
 */
public record AccountPassword(String account, String passwordHash) {
}
