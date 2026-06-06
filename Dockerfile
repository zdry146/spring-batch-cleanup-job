FROM azul/zulu-openjdk:21.0.2-jre

WORKDIR /app

COPY target/spring-batch-cleanup-job-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]