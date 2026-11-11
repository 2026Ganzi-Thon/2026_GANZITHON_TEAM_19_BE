# 운영 이미지 (GitHub Actions에서 빌드 → ECR)
# 테스트는 CI build job에서 이미 돌렸으므로 여기서는 jar만 만든다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 의존성 레이어를 먼저 캐시 (build.gradle이 안 바뀌면 재사용)
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# root로 실행하지 않음. UID 1001은 deploy.sh에서 Firebase 키 파일 소유자로 맞춘다.
RUN groupadd --system --gid 1001 app && useradd --system --uid 1001 --gid app app
COPY --from=build /src/build/libs/*.jar app.jar
USER app

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
