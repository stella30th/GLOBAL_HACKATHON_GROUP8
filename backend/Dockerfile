# Build stage
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Resolve dependencies in their own layer so code edits do not re-download the whole repository
# on every deploy. This is the single biggest saving on Render build time.
COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080

# Tuned for Render's free instance (512 MB, shared CPU), where the dominant cost is the cold start
# after the service is spun down for inactivity:
#   MaxRAMPercentage  - use the container limit instead of the JVM's conservative default
#   UseSerialGC       - lowest startup and memory overhead on a single shared core
#   TieredStopAtLevel=1 - skip the C2 compiler, which shortens warmup for short-lived bursts
#   Xss512k           - smaller thread stacks leave more heap for the application
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Djava.security.egd=file:/dev/./urandom -Dspring.jmx.enabled=false"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
