# ─────────────────────────────────────────────────────────────
# 단일 app 이미지: 프론트(Vite) 정적 산출물 + 백엔드(Spring Boot) 통합
#   - 프론트는 상대경로(/api, location.host/ws)로 호출하므로
#     Spring Boot가 정적 리소스 + REST + WebSocket을 같은 오리진에서 서빙한다.
# ─────────────────────────────────────────────────────────────

# 1단계: 프론트엔드 빌드 (Vite)
FROM node:20-alpine AS frontend
WORKDIR /fe
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/tsconfig.json frontend/vite.config.ts frontend/index.html ./
COPY frontend/src ./src
RUN npm run build

# 2단계: 백엔드 빌드 (Gradle) — 프론트 dist를 static 리소스로 포함시켜 bootJar
FROM gradle:8-jdk21 AS backend
WORKDIR /app
COPY backend/build.gradle backend/settings.gradle ./
# 의존성 레이어 캐시 (소스 변경 시 재다운로드 방지)
RUN gradle dependencies --no-daemon -q > /dev/null 2>&1 || true
COPY backend/src ./src
COPY --from=frontend /fe/dist ./src/main/resources/static
RUN gradle bootJar --no-daemon -q

# 3단계: 실행 이미지 (JRE only) — alpine busybox의 wget으로 헬스체크
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend /app/build/libs/*.jar app.jar
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "app.jar"]
