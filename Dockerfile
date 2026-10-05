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
# PDF/Excel export: fontconfig and freetype are what the JDK's AWT classes load
# even in headless mode; the PDF fonts themselves ship inside the jar.
RUN apk add --no-cache fontconfig freetype ttf-dejavu
# The one writable path: uploaded files. A named volume mounted here inherits
# this ownership the first time it is created.
RUN mkdir -p /data/files && chown app:app /data/files
COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080

# Container-level health for `docker run` (compose/Traefik define their own).
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8080/api/v1/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
