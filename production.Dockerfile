# syntax = docker/dockerfile:1
# This Dockerfile runs on the CI/CD pipeline when Puffin is being deployed.

# Build the project into an executable JAR
FROM docker.io/library/gradle:7.4.2-jdk17-alpine as build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN /usr/bin/gradle --console=rich --warn --stacktrace --no-daemon build

# Run the built JAR and expose port 25565
FROM docker.io/library/eclipse-temurin:17-jre-alpine
EXPOSE 25565
WORKDIR /puffin
COPY --from=build /work/build/libs/Puffin-*-all.jar /service/puffin.jar
CMD ["java", "-jar", "/service/puffin.jar"]