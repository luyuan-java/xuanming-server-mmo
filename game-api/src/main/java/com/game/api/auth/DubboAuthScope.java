package com.game.api.auth;

/** 哪些 Dubbo 接口要求调用方鉴权：本仓库自己的业务接口（包 {@code com.game.}）。调用方与提供方共用这一个判断。 */
final class DubboAuthScope {

    static final String BUSINESS_PACKAGE_PREFIX = "com.game.";

    private DubboAuthScope() {
    }

    static boolean covers(String serviceInterface) {
        return serviceInterface != null && serviceInterface.startsWith(BUSINESS_PACKAGE_PREFIX);
    }
}
