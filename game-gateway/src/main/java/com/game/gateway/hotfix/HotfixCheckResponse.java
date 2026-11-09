package com.game.gateway.hotfix;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 未配置平台时仅返回两个 false；无需更新时省略补丁与说明，已知发布线仍返回最新版本。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotfixCheckResponse(boolean needUpdate, boolean forceUpdate, String patchUrl,
                                  String latestVersion, String changelog) { }
