FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY backend/pom.xml backend/mvnw backend/mvnw.cmd /workspace/backend/
COPY backend/.mvn /workspace/backend/.mvn
COPY backend/src /workspace/backend/src
WORKDIR /workspace/backend
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/backend/target/backend-0.0.1-SNAPSHOT.jar /app/backend.jar
COPY datasets /app/datasets

ENV DATASET_PATH=/app/datasets/sample \
    OLLAMA_BASE_URL=http://ollama:11434 \
    OLLAMA_MODEL=llama3.2:1b \
    FRONTEND_ALLOWED_ORIGINS=http://localhost:4173,http://127.0.0.1:4173
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=10 \
  CMD curl --fail --silent http://localhost:8080/api/health || exit 1
ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp", "-jar", "/app/backend.jar"]
