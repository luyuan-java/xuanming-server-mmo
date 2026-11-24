package com.game.api;

import com.game.proto.content_guard.CheckTextRequest;
import com.game.proto.content_guard.CheckTextResponse;

/**
 * 内容安全服务（Dubbo group {@link DubboGroups#CONTENT_GUARD}，由 game-content-guard 提供）。
 *
 * <p>对齐权威仓 {@code proto/content_guard/content_guard.proto} 的内部服务 {@code ContentGuard}：
 * 只回答文字在指定场景下能否使用及打码结果，无业务副作用，客户端无法直达。
 */
public interface ContentGuardService {

    /** 本地词库 + 变体联系方式 + 可选外部平台审核（世界频道支持短超时先行放行并标 {@code pending_platform_review}）。 */
    CheckTextResponse checkText(CheckTextRequest request);

    /** 仅走完整预算的外部平台审核（供世界频道短超时放行后的后台复检撤回使用）。 */
    CheckTextResponse checkPlatformText(CheckTextRequest request);
}
