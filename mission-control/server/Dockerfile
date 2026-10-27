# The fat jar is built outside Docker (`./gradlew :mission-control:server:buildFatJar`): Gradle configures every
# module, Android included, so building it here would need the Android SDK in the image.
FROM eclipse-temurin:21-jre

# State (the SQLite archive, and the single-instance lock under $HOME/.radarnebo) lives in the /data volume.
ENV HOME=/data \
    ARCHIVE_JDBC_URL=jdbc:sqlite:/data/radarnebo-archive.db \
    PORT=8080

RUN groupadd --gid 10001 radarnebo \
    && useradd --uid 10001 --gid radarnebo --home-dir /data --no-create-home radarnebo \
    && mkdir -p /data && chown radarnebo:radarnebo /data

WORKDIR /app
COPY build/libs/server-all.jar ./server.jar

USER radarnebo
VOLUME ["/data"]
EXPOSE 8080

# No curl in the JRE image: bash's /dev/tcp makes the request.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q ' 200 '"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/server.jar"]
