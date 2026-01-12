FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the Maven-built JAR
COPY target/app-0.0.1-SNAPSHOT.jar app.jar

# Expose port 8080
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
