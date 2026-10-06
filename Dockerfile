# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY socket-http-core/pom.xml socket-http-core/
COPY socket-http-demo/pom.xml socket-http-demo/
COPY socket-http-bench/pom.xml socket-http-bench/
COPY socket-http-core/src socket-http-core/src
COPY socket-http-demo/src socket-http-demo/src
COPY socket-http-bench/src socket-http-bench/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package -pl socket-http-demo -am

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home socket
WORKDIR /app
COPY --from=build /src/socket-http-demo/target/socket-http-demo-1.0.0.jar app.jar
COPY --from=build /src/socket-http-demo/target/lib lib
USER socket
ENV PORT=8203 CONCURRENCY_MODEL=VIRTUAL_THREADS
EXPOSE 8203
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
