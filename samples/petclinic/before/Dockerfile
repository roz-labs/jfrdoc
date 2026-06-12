FROM eclipse-temurin:25-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git . \
 && ./mvnw -q -DskipTests package \
 && cp target/spring-petclinic-*.jar /app.jar
ENTRYPOINT ["java"]
