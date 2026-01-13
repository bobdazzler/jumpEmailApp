# -------- Build stage --------
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline

COPY src ./src
RUN mvn clean package -DskipTests

# -------- Runtime stage --------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app


# Install Chrome and Chromium dependencies for Selenium
RUN apk add --no-cache \
    chromium \
    chromium-chromedriver \
    ttf-freefont \
    font-noto-emoji \
    && rm -rf /var/cache/apk/*

# Set Chrome binary location for Selenium
ENV CHROME_BIN=/usr/bin/chromium-browser
ENV CHROMEDRIVER_PATH=/usr/bin/chromedriver

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

