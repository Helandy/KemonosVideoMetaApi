FROM gradle:9.3.0-jdk21 AS build
WORKDIR /workspace

COPY build.gradle.kts settings.gradle.kts gradlew gradlew.bat /workspace/
COPY gradle /workspace/gradle
RUN chmod +x /workspace/gradlew

COPY src /workspace/src
RUN /workspace/gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

# Размер heap берётся от лимита контейнера, а не от памяти хоста.
# SerialGC на небольшом heap заметно экономит RSS: нет региональных структур G1 и его потоков.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=20 -XX:+UseSerialGC -Xss512k \
-XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -XX:MaxDirectMemorySize=64m \
-XX:+ExitOnOutOfMemoryError"

VOLUME ["/data"]
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
