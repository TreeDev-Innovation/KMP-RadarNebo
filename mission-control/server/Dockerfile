# The fat jar is built outside Docker (`./gradlew :mission-control:server:buildFatJar`): Gradle configures every
# module, Android included, so building it here would need the Android SDK in the image.
FROM eclipse-temurin:21-jre@sha256:ff92a063dac03a29fca3987bcfc66aa5f17769542df802bbf7d2ebaba4df8ebe

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

# The heap is 75% of the cgroup limit (compose's mem_limit). An OutOfMemoryError exits instead of leaving a
# half-dead process, and restart: unless-stopped brings it back.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/server.jar"]
