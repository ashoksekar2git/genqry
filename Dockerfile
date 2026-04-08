FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy Maven wrapper
COPY mvnw .
COPY .mvn .mvn

# Copy pom.xml and source code
COPY pom.xml .
COPY src src

# Build application
RUN ./mvnw clean package -DskipTests

# Expose port
EXPOSE 8080

# Environment variables for configuration
ENV OPENAI_API_KEY=""
ENV PINECONE_API_KEY=""
ENV PINECONE_ENVIRONMENT=""
ENV PINECONE_INDEX_NAME="rag-nl2sql"

# Run application
CMD ["java", "-jar", "target/genQry-0.0.1-SNAPSHOT.jar"]

