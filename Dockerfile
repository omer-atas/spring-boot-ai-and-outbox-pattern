# Multi-stage build for optimized image size
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
# Copy pom.xml
COPY pom.xml ./
# Copy source code
COPY src ./src
# Build application
RUN mvn clean package -Dmaven.test.skip=true \
    -Djava.net.preferIPv4Stack=true \
    -Dmaven.wagon.http.retryHandler.count=3 \
    -Dmaven.wagon.http.pool=false
# Extract layers for better caching
RUN java -Djarmode=layertools -jar target/*.jar extract
# Runtime stage
FROM eclipse-temurin:21-jre-alpine
# Add non-root user for security
RUN addgroup -g 1001 appuser && \
    adduser -D -u 1001 -G appuser appuser
WORKDIR /app
# Copy extracted layers from builder
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./
# Create logs directory with correct permissions
RUN mkdir -p /app/logs && chown -R appuser:appuser /app
# Switch to non-root user
USER appuser
# Expose port
EXPOSE 8080
# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1
# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+OptimizeStringConcat \
    -XX:+UseStringDeduplication \
    -Djava.security.egd=file:/dev/./urandom"
# Run application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
