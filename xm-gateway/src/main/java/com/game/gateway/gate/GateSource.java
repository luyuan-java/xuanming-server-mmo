package com.game.gateway.gate;

import com.game.api.proto.GateNodeInfo;
import java.util.List;

/**
 * gate 在线目录的读取面（gateway 只读不写）。生产实现是 {@link RedisGateSource}；测试注入替身，不需要 Redis。
 *
 * <p>契约：
 * <ul>
 *   <li>返回该 zone 下当前未过期的 gate 条目，顺序不保证；可能包含刚死、尚未过期的节点，调用方不得假设都连得上；</li>
 *   <li>条目内容来自各 gate 自己发布，调用方要自行过滤不可用的条目（见 {@link GatePicker}）；</li>
 *   <li>目录不可达时抛 {@link RuntimeException}，调用方按 fail-closed 处理，不得当成「没有 gate」以外的成功；</li>
 *   <li>阻塞调用，线程安全；只能在 Servlet 请求线程等允许阻塞的线程上调用。</li>
 * </ul>
 */
public interface GateSource {

    List<GateNodeInfo> listGates(int zoneId);
}
