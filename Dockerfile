FROM eclipse-temurin:21-jdk-jammy AS build
# FROM eclipse-temurin:21-jdk AS build
ENV HOME=/app
WORKDIR $HOME

# Wrapper + pom first, so the dependency layer is cached
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B clean package -DskipTests

# Stage 2: run on a slim JRE
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar", "--spring.profiles.active=prod"]
