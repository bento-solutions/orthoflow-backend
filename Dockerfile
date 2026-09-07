# Build stage
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user (audit M7). The jar is copied read-only and owned by
# root; the app never needs to write to the image filesystem.
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080

# Container-level health for `docker run` (compose/Traefik define their own).
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8080/api/v1/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
