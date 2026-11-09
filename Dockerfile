FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app

RUN useradd --system --uid 10001 booksmith
COPY --from=build /workspace/target/*.jar app.jar
USER booksmith

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
