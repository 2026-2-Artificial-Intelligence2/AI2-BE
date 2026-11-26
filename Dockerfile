# 부장님 몰래 서버 이미지. 클라우드(Render, Fly.io 등)에 올릴 때 쓴다.
# 빌드: docker build -t office-stock .   실행: docker run -p 8080:8080 office-stock

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null || true
COPY src src
RUN ./gradlew --no-daemon -q bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/office-stock.jar app.jar
# 많은 클라우드는 포트를 PORT 환경 변수로 준다. 없으면 8080.
ENV PORT=8080
EXPOSE 8080
# 방은 서버 메모리에만 있으므로 인스턴스는 반드시 하나만 띄운다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar --server.port=${PORT}"]
