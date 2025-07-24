#### Build stage
FROM maven:3.9.6-amazoncorretto-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q package -DskipTests && \
    cp $(ls target/*.jar | grep -v "original") app.jar

#### Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
# Railway sets $PORT; bind Spring directly to it
ENV JAVA_OPTS=""
# Railway maps the container port automatically; no EXPOSE needed
CMD ["sh", "-c", "java -jar app.jar --server.port=$PORT"]