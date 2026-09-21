FROM eclipse-temurin:24-jdk
WORKDIR /app
COPY target/rwa-supply-chain-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
