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

# Moderação de imagens: o modelo (AdamCodd/vit-base-nsfw-detector, Apache-2.0, ONNX int8), fixado no commit e
# conferido pela assinatura SHA-256 (a mesma que o Hugging Face publica). Sem ele, a API não sobe (MODERATION_REQUIRED)
ADD --checksum=sha256:432763a6899ebc418c55f784b98f90565e5fc694c778d2ffcb0294b12f6a7404 \
    https://huggingface.co/AdamCodd/vit-base-nsfw-detector/resolve/8587de998f441aac03fdd57a85d2e4cb808c7d64/onnx/model_quantized.onnx \
    /app/models/nsfw-vit-int8.onnx
RUN chmod 644 /app/models/nsfw-vit-int8.onnx
ENV MODERATION_MODEL=/app/models/nsfw-vit-int8.onnx MODERATION_REQUIRED=true

RUN useradd --system --uid 10001 booksmith
COPY --from=build /workspace/target/*.jar app.jar
USER booksmith

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
