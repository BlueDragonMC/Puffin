# This Dockerfile must be run with BuildKit enabled
# see https://docs.docker.com/engine/reference/builder/#buildkit

# Build the project into an executable JAR
FROM gradle:9.5.1-jdk25 AS build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN --mount=target=/home/gradle/.gradle,type=cache \
    /usr/bin/gradle --console=rich --warn --stacktrace --no-daemon --build-cache build

# Run the built JAR and expose port 25565
FROM eclipse-temurin:25-jre-alpine

LABEL com.bluedragonmc.image=puffin
LABEL com.bluedragonmc.environment=dev

EXPOSE 25565
WORKDIR /puffin
COPY --from=build /work/build/libs/Puffin-*-all.jar /service/puffin.jar
CMD ["java", "-jar", "/service/puffin.jar"]