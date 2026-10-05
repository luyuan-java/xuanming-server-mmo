# 一个 Dockerfile 服务全部 Spring Boot 进程模块（docs/porting/deploy-ci-spec.md §3）：
#   ./mvnw -B -DskipTests package -Dxm.build.commit=<40 位 sha> -Dxm.build.contract=<SOURCE 里的 mmorpg.commit>
#   docker build -f deploy/docker/Dockerfile --build-arg MODULE=xm-gate [--build-arg XM_BUILD_COMMIT=…] -t xuanming-java/xm-gate:<sha12> .
# 构建上下文 = 仓库根；仓库根的 .dockerignore 只放行 xm-*/target/xm-*.jar 与 config-data/tables。jar 先由宿主 Maven 一次打齐
# （多模块 reactor 不在容器里跑 mvn：容器里没有 ~/.m2 缓存，镜像也只在装好 JDK 的 CI 里构建）。
#
# 基础镜像钉 tag + index digest（多架构清单的 digest，2026-10-05 由 registry-1.docker.io 的 Docker-Content-Digest 查得；
# 与开发机 JDK 21.0.12.1+1 是同一个构建；Ubuntu noble、glibc，自带 curl 与 bash，compose 的 healthcheck 要用）。
# 更新：docker buildx imagetools inspect <image:tag> 取 Digest 行替换 @sha256，推送后看 integration.yml 是否绿。
# 不用 `# syntax=`、不用 RUN --mount（同基线 deploy/k8s/Dockerfile.go-svc 的发布标准）；不写 HEALTHCHECK（编排层负责）。
# 镜像 tag：快照轨 <sha12>，永远不打 latest 等可变 tag（spec §3.8）；推送 registry 归批次 7.6。
ARG JRE_IMAGE=eclipse-temurin:21.0.12.1_1-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c

FROM ${JRE_IMAGE} AS layers
ARG MODULE
RUN [ -n "${MODULE}" ] || { echo "需要 --build-arg MODULE=<进程模块名>，例如 xm-gate" >&2; exit 1; }
WORKDIR /x
# 只会匹配一个文件（repackage 把原始包改名为 .jar.original）；匹配到多个时 COPY 直接报错，不会悄悄选错
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
# Spring Boot 3.3 起的分层解包（jarmode=tools 取代已弃用的 layertools）：解出 app.jar（Class-Path 指向 lib/）与 lib/，
# 按 dependencies / spring-boot-loader / snapshot-dependencies / application 四层分目录
RUN java -Djarmode=tools -jar app.jar extract --layers --destination out

FROM ${JRE_IMAGE}
# 非 root：固定数字 uid / gid 10001（K8s 的 runAsNonRoot 只认数字，同基线各镜像）。
# home 必须可写：Dubbo 3 往 ${user.home}/.dubbo 写文件缓存（基线把 home 设成 /app，这里不能照抄）
RUN groupadd --system --gid 10001 xm \
 && useradd --system --uid 10001 --gid xm --create-home --home-dir /home/xm --shell /usr/sbin/nologin xm
WORKDIR /app
# /app 归 root、运行用户只读；可写的只有 /tmp（Tomcat 工作目录、Netty native 库、hsperfdata）与 /home/xm。
# 层顺序 = 变化频率从低到高：三方依赖 → loader → 表数据 → 三方 SNAPSHOT → 应用层。
# 实测（xm-chat）：Boot 缺省分层把同一 reactor 的库模块（xm-common / xm-api 等，includeModuleDependencies）放进
# application 层、与进程模块自身同层；不带 --launcher 解包时 spring-boot-loader 与 snapshot-dependencies 是空目录
# （COPY 空目录不报错）。两条 COPY 保留：将来引入三方 SNAPSHOT 依赖或改分层配置时不用改这里。
# 表数据是随提交走的契约，烤进镜像（「镜像 = 代码 + 同一个提交的表」），运行时照样校验 manifest 的 sha256；
# xm.table-dir 的缺省 config-data/tables 相对 WORKDIR /app，不用改配置。秘密永远不进镜像
COPY --from=layers /x/out/dependencies/ ./
COPY --from=layers /x/out/spring-boot-loader/ ./
COPY config-data/tables/ ./config-data/tables/
COPY --from=layers /x/out/snapshot-dependencies/ ./
COPY --from=layers /x/out/application/ ./
# 堆随容器内存上限伸缩，剩下的留给 Netty 直接内存、metaspace 与线程栈；显式 G1（小 limit 下 JVM 会退化到 SerialGC）；
# OOM 直接退出不带病运行。用 JDK_JAVA_OPTIONS 而不是 JAVA_TOOL_OPTIONS：只作用于 java 启动器，不污染 jcmd 等工具。
# compose / K8s 用同名环境变量整串替换
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
# 版本戳 ARG 放在运行阶段末尾，只让下面几层随提交失效。created 取提交时间（git log -1 --format=%cI）而不是墙钟，
# 同一个提交两次构建得到相同的 label
ARG MODULE
ARG XM_BUILD_VERSION=unknown
ARG XM_BUILD_COMMIT=unknown
ARG XM_BUILD_CONTRACT=unknown
ARG XM_BUILD_CREATED=unknown
LABEL org.opencontainers.image.title="${MODULE}" \
      org.opencontainers.image.version="${XM_BUILD_VERSION}" \
      org.opencontainers.image.revision="${XM_BUILD_COMMIT}" \
      org.opencontainers.image.created="${XM_BUILD_CREATED}" \
      org.opencontainers.image.source="https://github.com/luyuan-java/xuanming-server-mmo" \
      com.game.contract.mmorpg-commit="${XM_BUILD_CONTRACT}"
RUN printf 'module=%s\nversion=%s\ncommit=%s\ncontract=%s\ncreated=%s\n' \
      "${MODULE}" "${XM_BUILD_VERSION}" "${XM_BUILD_COMMIT}" "${XM_BUILD_CONTRACT}" "${XM_BUILD_CREATED}" > /app/BUILD_INFO
USER 10001:10001
# exec 形式：java 是 PID 1，直接收 SIGTERM 走 Spring 优雅停机（不需要 tini）；同时绕过基础镜像的 cacert 入口脚本
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
