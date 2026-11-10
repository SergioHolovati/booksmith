# Corretor ortográfico e gramatical (§8.5, etapa 2): LanguageTool de código aberto (LGPL), versão oficial
# baixada do site do projeto. Fixada na 6.6 (março de 2025) para o resultado não mudar sozinho.
FROM eclipse-temurin:21-jre
ARG LT_VERSION=6.6
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /tmp/lt.zip "https://languagetool.org/download/LanguageTool-${LT_VERSION}.zip" \
    && unzip -q /tmp/lt.zip -d /opt && mv /opt/LanguageTool-${LT_VERSION} /opt/languagetool && rm /tmp/lt.zip \
    && useradd --system --home /opt/languagetool languagetool
COPY server.properties /opt/languagetool/server.properties
USER languagetool
WORKDIR /opt/languagetool
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --retries=12 CMD curl -fs "http://localhost:8081/v2/languages" > /dev/null || exit 1
# --public: aceita conexões de fora do container (a porta só é publicada para 127.0.0.1 no compose)
CMD ["java", "-Xms256m", "-Xmx1g", "-cp", "languagetool-server.jar", "org.languagetool.server.HTTPServer", \
     "--port", "8081", "--public", "--config", "server.properties"]
