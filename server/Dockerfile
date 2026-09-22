# syntax=docker/dockerfile:1.7
# Zwei Stufen: Gradle baut den Dienst im Container (JDK 21, ohne Android-SDK — :server haengt
# nur an :core), das Laufzeit-Image traegt nur das fertige Verzeichnis. So braucht der Server,
# auf dem das laeuft, weder Java noch Gradle: Docker und git genuegen (server/deploy/install.sh).
#
# Der Build-Kontext ist die Wurzel des Repos (siehe deploy/compose.yaml); der Gradle-Cache
# bleibt zwischen zwei Builds erhalten (BuildKit-Cache), damit ein Update Minuten dauert,
# nicht eine Viertelstunde.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core ./core
COPY server ./server
# Die Module der App werden nur bekannt gemacht, nicht gebaut; ihre Build-Skripte reichen.
COPY shared/build.gradle.kts ./shared/build.gradle.kts
COPY androidApp/build.gradle.kts ./androidApp/build.gradle.kts
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :server:installDist \
 && cp -r server/build/install/vereinsdeckel-server /dist

FROM eclipse-temurin:21-jre
# Welcher Stand laeuft: der Commit, aus dem gebaut wurde (install.sh und der Updater setzen ihn).
ARG GIT_SHA=unbekannt
ARG GIT_DATE=
ENV VEREINSDECKEL_VERSION=$GIT_SHA \
    VEREINSDECKEL_VERSION_DATE=$GIT_DATE

RUN useradd --system --create-home --home-dir /app vereinsdeckel \
 && mkdir -p /data/media /data/updates && chown vereinsdeckel /data/media /data/updates

WORKDIR /app
COPY --from=build --chown=vereinsdeckel /dist/ /app/

USER vereinsdeckel
ENV PORT=8080 \
    MEDIA_DIR=/data/media

VOLUME ["/data/media"]
EXPOSE 8080

# Der Dienst prueft sich selbst; siehe Main.kt.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD ["/app/bin/vereinsdeckel-server", "health"]

ENTRYPOINT ["/app/bin/vereinsdeckel-server"]
