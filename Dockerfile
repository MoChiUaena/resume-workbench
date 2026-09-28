FROM node:24.18.0-alpine@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS backend
WORKDIR /build
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN --mount=type=cache,id=resume-workbench-maven,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -ntp -Dmaven.wagon.http.retryHandler.count=3 dependency:resolve
COPY src src
COPY --from=frontend /build/frontend/dist frontend/dist
RUN --mount=type=cache,id=resume-workbench-maven,target=/root/.m2 \
    ./mvnw -B -ntp package -DskipTests

FROM mcr.microsoft.com/playwright/java:v1.63.0-noble@sha256:013e2595272806f887d91041fbf26be71dda2f48a805c717cc7de3bbca5339c8
COPY --from=backend /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_GC=1
ENV SERVER_ADDRESS=0.0.0.0
ENV RESUME_DATA_DIR=/app/data
WORKDIR /app
RUN mkdir -p /app/data && chown pwuser:pwuser /app/data
COPY --from=backend --chown=pwuser:pwuser /build/target/resume-workbench.jar /app/app.jar
LABEL org.opencontainers.image.source="https://github.com/MoChiUaena/resume-workbench"
USER pwuser
EXPOSE 18765
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
