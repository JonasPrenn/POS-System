# Der Updater: git zum Holen, Docker-CLI mit compose zum Bauen und Neustarten des Dienstes.
# Klein gehalten — er tut nur, was updater.sh sagt, und das nur auf Wunsch der Verwaltung.
FROM docker:27-cli
RUN apk add --no-cache bash git jq openssh-client docker-cli-compose coreutils
COPY updater.sh /usr/local/bin/updater
RUN chmod +x /usr/local/bin/updater
ENTRYPOINT ["updater"]
