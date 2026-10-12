FROM eclipse-temurin:17-jre
WORKDIR /app
COPY example-0.0.2.jar app.jar
COPY dependency/ lib/
ENV AGENTKIT_BIND_HOST=0.0.0.0 PORT=8000
EXPOSE 8000
USER 10001:10001
ENTRYPOINT ["java", "-cp", "/app/app.jar:/app/lib/*", "com.volcengine.veadk.example.agentkit.AgentKitRuntimeExample"]
