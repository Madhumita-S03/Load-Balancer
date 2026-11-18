# Build with Java 17 and run the complete automated test suite before creating the image.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q clean verify

# Small Java 17 runtime image for deployment.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/dispatch-load-balancer-1.0.0.jar app.jar
EXPOSE 8080
# Keep the JVM heap within a reasonable fraction of the hosting container memory.
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=60 -jar app.jar"]
