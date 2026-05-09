FROM azul/zulu-openjdk:21.0.2-jre

WORKDIR /app

COPY target/spring-batch-cleanup-job-1.0.0.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]