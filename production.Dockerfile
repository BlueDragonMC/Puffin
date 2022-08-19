# syntax = docker/dockerfile:1
# This Dockerfile runs on the CI/CD pipeline when Puffin is being deployed.
# It is much slower because `RUN mount=type=cache` is not supported by BuildKit,
# Buildah, or Kaniko in a Kubernetes cluster (docker-in-docker environment)

# Build the project into an executable JAR
FROM gradle:jdk17 as build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN /usr/bin/gradle --console=rich --warn --stacktrace --no-daemon build

# Run the built JAR and expose port 25565
FROM eclipse-temurin:17
EXPOSE 25565
WORKDIR /puffin
COPY --from=build /work/build/libs/Puffin-*-all.jar /service/puffin.jar
CMD ["java", "-jar", "/service/puffin.jar"]