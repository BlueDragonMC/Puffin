# This Dockerfile must be run with BuildKit enabled
# see https://docs.docker.com/engine/reference/builder/#buildkit

# Build the project into an executable JAR + separate dependency JARs
FROM gradle:9.5.1-jdk25 AS build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN --mount=target=/home/gradle/.gradle,type=cache \
    /usr/bin/gradle --console=plain --stacktrace --no-daemon check installThinDist

# Run the built application
FROM eclipse-temurin:25-jre-alpine

LABEL com.bluedragonmc.image=puffin
LABEL com.bluedragonmc.environment=dev

EXPOSE 50051
EXPOSE 8080
EXPOSE 8082
WORKDIR /puffin
COPY --from=build /work/build/thin-dist/lib /service/lib
COPY --from=build /work/build/thin-dist/Puffin-*.jar /service/puffin.jar
CMD ["java", "-cp", "/service/puffin.jar:/service/lib/*", "com.bluedragonmc.puffin.app.MainKt"]
