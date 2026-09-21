# Erwartet das Ergebnis von `./gradlew :server:installDist` unter
# build/install/vereinsdeckel-server. Gradle laeuft bewusst nicht im Container: Das Image
# soll nur das fertige Verzeichnis tragen, keine Build-Kette. Gebaut wird davor mit
# ./gradlew :server:installDist — das geht auch ohne Android-SDK.
FROM eclipse-temurin:21-jre

RUN useradd --system --create-home --home-dir /app vereinsdeckel \
 && mkdir -p /data/media && chown vereinsdeckel /data/media

WORKDIR /app
COPY --chown=vereinsdeckel build/install/vereinsdeckel-server/ /app/

USER vereinsdeckel
ENV PORT=8080 \
    MEDIA_DIR=/data/media

VOLUME ["/data/media"]
EXPOSE 8080

# Der Dienst prueft sich selbst; siehe Main.kt.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD ["/app/bin/vereinsdeckel-server", "health"]

ENTRYPOINT ["/app/bin/vereinsdeckel-server"]
