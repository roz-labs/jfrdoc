FROM eclipse-temurin:25-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git . \
 && ./mvnw -q -DskipTests package \
 && mkdir -p /app \
 && (cd /app && jar -xf /src/target/spring-petclinic-*.jar)
WORKDIR /app
ENTRYPOINT ["java"]
