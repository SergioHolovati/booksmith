FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app

# Ghostscript: o PDF para gráfica (CMYK, PDF/X-1a). Do repositório do Ubuntu, que recebe as correções de segurança;
# o deploy passa APT_REFRESH com a data, para esta camada não ficar velha no cache do Docker
ARG APT_REFRESH=0
RUN echo "apt: $APT_REFRESH" && apt-get update \
    && apt-get install -y --no-install-recommends ghostscript \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 booksmith
COPY --from=build /workspace/target/*.jar app.jar
USER booksmith

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
