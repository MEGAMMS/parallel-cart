FROM eclipse-temurin:21-jdk
WORKDIR /app

COPY mvnw mvnw
COPY .mvn .mvn
COPY pom.xml pom.xml
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -q -DskipTests package

EXPOSE 8080
CMD ["java", "-jar", "target/parallel-cart-0.0.1-SNAPSHOT.jar"]
