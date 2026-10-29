#!/usr/bin/env python3
"""读取 compose config 的 JSON，核对真实 Boot 模块与发布接口；不打印环境变量值。"""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]


def check(config):
    services = config["services"]
    boot = set()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for pom in ROOT.glob("game-*/pom.xml"):
        tree = ET.parse(pom)
        if any(p.findtext("m:artifactId", namespaces=ns) == "spring-boot-maven-plugin"
               for p in tree.findall("./m:build/m:plugins/m:plugin", ns)):
            boot.add(pom.parent.name)
    apps = {name for name in services if name.startswith("game-")}
    if apps != boot:
        raise ValueError("Boot 模块与编排不一致: missing=" + str(sorted(boot-apps)) + " extra=" + str(sorted(apps-boot)))
    for name in sorted(apps):
        svc = services[name]
        if svc.get("build", {}).get("args", {}).get("MODULE") != name:
            raise ValueError(name + " 构建 MODULE 不一致")
        build = svc["build"]["args"]
        commit = build.get("GAME_BUILD_COMMIT", "")
        contract = build.get("GAME_BUILD_CONTRACT", "")
        if not re.fullmatch("[0-9a-f]{40}", commit) or not re.fullmatch("[0-9a-f]{40}", contract):
            raise ValueError(name + " 缺精确构建/契约版本")
        if not svc.get("image", "").endswith(":" + commit[:12]):
            raise ValueError(name + " 镜像标签必须对应构建提交")
        if name == "game-robot":
            if svc.get("profiles") != ["tools"]:
                raise ValueError("robot 必须为显式工具 profile")
            continue
        env = svc["environment"]
        if env.get("GAME_RUN_MODE") != "dev":
            raise ValueError("单节点开发夹具不得冒充生产形态")
        namespace = env.get("GAME_NAMESPACE", "")
        if not re.fullmatch("[a-z][a-z0-9]{0,27}", namespace) or namespace in ("mysql", "sys", "public"):
            raise ValueError(name + " 部署命名空间非法")
        if not svc.get("healthcheck", {}).get("test"):
            raise ValueError(name + " 缺少探针")
        if "SPRING_DATASOURCE_URL" in env:
            if not env["SPRING_DATASOURCE_URL"].startswith("jdbc:mysql://tidb:4000/" + namespace + "?"):
                raise ValueError(name + " 未使用同一 TiDB 权威域")
            if env.get("SPRING_DATASOURCE_USERNAME") != namespace + "_app":
                raise ValueError(name + " 账号未限定部署命名空间")
        for port in svc.get("ports", []):
            if port.get("host_ip") != "127.0.0.1":
                raise ValueError(name + " 开发端口必须限于本机")
        for key, value in env.items():
            if "SECRET" in key or key in ("GAME_ADMIN_TOKEN", "GAME_LOGIN_DEV_PASSWORD"):
                if not isinstance(value, str) or len(value.encode()) < 32:
                    raise ValueError(name + " 缺少有效密钥: " + key)
    return len(apps)


if __name__ == "__main__":
    try:
        count = check(json.load(sys.stdin))
        print("整栈配置守卫通过: " + str(count) + " 个 Boot 模块，含 robot")
    except (KeyError, ValueError, ET.ParseError) as failure:
        print("整栈配置守卫失败: " + str(failure), file=sys.stderr)
        sys.exit(1)
