FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
ARG MODULE
RUN mvn -B -pl ${MODULE} -am package -DskipTests
RUN javac -d /tmp docker/Healthcheck.java
RUN cp ${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar /application.jar
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system bank && useradd --system --gid bank bank
WORKDIR /app
COPY --from=build /application.jar app.jar
COPY --from=build /tmp/Healthcheck.class /app/Healthcheck.class
USER bank
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
