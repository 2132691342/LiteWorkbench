# 三阶段构建：后端 Maven 编译 → 前端 Vite 构建 → 运行时只保留 JRE + jar + 前端静态资源
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build/backend
# 仓库配置放在 cache mount 之外：/root/.m2 被缓存挂载遮蔽，放在里面会读不到
COPY deploy/maven-settings.xml /build/maven-settings.xml
# lombok.config 必须一起进构建上下文：它声明了 Qualifier 可复制到构造参数，
# 缺了它所有 @Qualifier 构造注入会退化成按类型匹配，同类多 Bean 的池直接启动失败
COPY backend/pom.xml backend/lombok.config ./
# 依赖缓存挂载：CI 每次构建都重新下载全部 Maven 依赖会成为主耗时，挂缓存后只拉增量
RUN --mount=type=cache,target=/root/.m2 mvn -s /build/maven-settings.xml -B -q dependency:go-offline
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -s /build/maven-settings.xml -B -q -DskipTests package

FROM node:22-alpine AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend .
RUN npm run build

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/openjdk:17-jdk-alpine
WORKDIR /app
# 内置 Python 运行时（唯一预置脚本运行时），所需工具直接在镜像内下载
# 日志与数据目录必须先在镜像里建出来：-Xlog 的 gc 日志文件在 JVM 启动最早期就要打开，
# 晚于任何应用代码，没挂卷的 `docker run` 会直接「Could not create the Java Virtual Machine」退出
RUN apk add --no-cache python3 curl tzdata \
    && mkdir -p /var/log/liteworkbench /var/lib/liteworkbench
COPY --from=backend-build /build/backend/target/lite-workbench-server.jar app.jar
COPY --from=frontend-build /build/frontend/dist ./static/
ENV LITEWORKBENCH_LOG_DIR=/var/log/liteworkbench \
    LITEWORKBENCH_RUNTIME_DATA_DIR=/var/lib/liteworkbench \
    SPRING_PROFILES_ACTIVE=prod \
    # 堆用绝对值钉死，不用 MaxRAMPercentage：百分比依赖 JVM 能否读到 cgroup 限额，
    # 读不到就按宿主物理内存算（实测 Docker Desktop 下读到 16G），小机上直接把堆放大到 GB 级。
    # InitialRAMPercentage / -Xms 一律取小：Serial GC 下给它大初值等于开机就把堆提交掉，
    # 省内存的唯一正确姿势是「小初值 + 让 GC 自己长 + 用完还回去」。
    JAVA_OPTS="-Xms32m -Xmx256m \
-XX:MinHeapFreeRatio=15 -XX:MaxHeapFreeRatio=35 \
-XX:+UseSerialGC \
-XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=48m -XX:ReservedCodeCacheSize=64m \
-XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 \
-Xss512k -Djava.awt.headless=true -XX:-UsePerfData \
-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom \
-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/var/log/liteworkbench/ -XX:-OmitStackTraceInFastThrow \
-Xlog:gc:file=/var/log/liteworkbench/gc.log:time,uptime,level,tags:filecount=2,filesize=4m"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \
  --spring.web.resources.static-locations=file:/app/static/,classpath:/static/"]
