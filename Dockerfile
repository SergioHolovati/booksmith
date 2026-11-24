FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app

# Moderação de imagens: o modelo (AdamCodd/vit-base-nsfw-detector, Apache-2.0, ONNX int8), fixado no commit e
# conferido pela assinatura SHA-256 (a mesma que o Hugging Face publica). Sem ele, a API não sobe (MODERATION_REQUIRED)
ADD --checksum=sha256:432763a6899ebc418c55f784b98f90565e5fc694c778d2ffcb0294b12f6a7404 \
    https://huggingface.co/AdamCodd/vit-base-nsfw-detector/resolve/8587de998f441aac03fdd57a85d2e4cb808c7d64/onnx/model_quantized.onnx \
    /app/models/nsfw-vit-int8.onnx
# Violência extrema e símbolos de ódio: a parte de imagem do CLIP (Xenova/clip-vit-base-patch32, MIT, ONNX int8),
# fixada no commit e conferida pela SHA-256. As frases já vêm prontas no jar (moderation/clip-prompts.json)
ADD --checksum=sha256:583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299 \
    https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/6b7c523703ae26e39be4b03774966f8571c7f523/onnx/vision_model_quantized.onnx \
    /app/models/clip-vision-int8.onnx
RUN chmod 644 /app/models/nsfw-vit-int8.onnx /app/models/clip-vision-int8.onnx
ENV MODERATION_MODEL=/app/models/nsfw-vit-int8.onnx MODERATION_CLIP_MODEL=/app/models/clip-vision-int8.onnx \
    MODERATION_REQUIRED=true

RUN useradd --system --uid 10001 booksmith
COPY --from=build /workspace/target/*.jar app.jar
USER booksmith

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
