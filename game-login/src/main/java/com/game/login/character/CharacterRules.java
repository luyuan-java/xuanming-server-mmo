package com.game.login.character;

/**
 * 建角要用的配表规则。存在这个接缝只为隔离全局配表单例（测试用替身注入），生产实现只有 {@link TableCharacterRules}。
 *
 * <p>契约：每次调用读当前配表（配表是快照替换式加载，不缓存旧规则）；线程安全。
 */
public interface CharacterRules {

    /**
     * {@code CreatePlayerRequest.class_id = 0} 时的默认职业：Class 表按文件顺序的第一行（当前为 1）。
     *
     * @throws IllegalStateException Class 表为空
     */
    int defaultClassId();

    boolean classExists(int classId);

    /**
     * RoleNameRule 表 id=1 行的规则（已自检）。
     *
     * @throws IllegalStateException 行缺失或不合法（调用方必须拒绝建角，不能带着坏规则放行）
     */
    RoleNameRules roleNameRules();
}
