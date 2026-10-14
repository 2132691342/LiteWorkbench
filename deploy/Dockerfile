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
RUN apk add --no-cache python3 curl tzdata
COPY --from=backend-build /build/backend/target/lite-workbench-server.jar app.jar
COPY --from=frontend-build /build/frontend/dist ./static/
ENV LITEWORKBENCH_LOG_DIR=/var/log/liteworkbench \
    LITEWORKBENCH_RUNTIME_DATA_DIR=/var/lib/liteworkbench \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxRAMPercentage=70 \
-XX:+UseStringDeduplication -XX:+UseCompressedOops -XX:+UseCompressedClassPointers \
-Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \
  --spring.web.resources.static-locations=file:/app/static/,classpath:/static/"]
