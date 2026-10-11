# 三阶段构建：后端 Maven 编译 → 前端 Vite 构建 → 运行时只放 JRE + jar + 静态资源
FROM maven:3.9-eclipse-temurin-17 AS server-build
WORKDIR /build/server
COPY server/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY server/src ./src
RUN mvn -B -q -DskipTests package

FROM node:22-alpine AS web-build
WORKDIR /build/web
COPY web/package.json ./
RUN npm install --no-audit --no-fund
COPY web .
RUN npm run build

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# 内置 Python 运行时（唯一预置脚本运行时）
RUN apk add --no-cache python3 curl tzdata
COPY --from=server-build /build/server/target/lite-workbench-server.jar app.jar
COPY --from=web-build /build/web/dist ./static/
ENV LITEWORKBENCH_LOG_DIR=/var/log/liteworkbench \
    LITEWORKBENCH_RUNTIME_DATA_DIR=/var/lib/liteworkbench
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar", \
  "--spring.web.resources.static-locations=file:/app/static/,classpath:/static/"]
