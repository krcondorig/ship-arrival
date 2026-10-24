FROM eclipse-temurin:21-jdk-jammy

# Install Chromium, ChromeDriver, and necessary libraries for headless/headed browser testing
RUN apt-get update && apt-get install -y \
    chromium \
    chromium-driver \
    fonts-liberation \
    libnss3 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxrandr2 \
    libgbm1 \
    libasound2 \
    libpango-1.0-0 \
    libcairo2 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create symlink so ChromeHapagLloydTrackingClient finds chromium as google-chrome
RUN ln -s /usr/bin/chromium /usr/bin/google-chrome

WORKDIR /app

# Create persistent data directory for HF Spaces storage bucket
RUN mkdir -p /data/shiparrival_db /data/hapag-chrome-profile

# Copy Maven wrapper and project files
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

# Build the Spring Boot application
RUN ./mvnw clean package -DskipTests

EXPOSE 8080

# Configure environment variables to use persistent /data storage
ENV SPRING_DATASOURCE_URL=jdbc:h2:file:/data/shiparrival_db;DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE
ENV HAPAG_LLOYD_PROFILE_DIR=/data/hapag-chrome-profile
ENV HAPAG_LLOYD_CHROME_PATH=/usr/bin/chromium

# Run the application
CMD ["java", "-jar", "target/shiparrival-0.0.1-SNAPSHOT.jar"]
