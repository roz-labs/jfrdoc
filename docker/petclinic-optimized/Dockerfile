# Same Spring PetClinic build, but instead of running the fat jar via
# `java -jar app.jar` (which forces every classpath lookup through Spring
# Boot's nested-JAR loader), we extract the jar once at build time and run
# from a plain filesystem layout. BOOT-INF/lib/*.jar then load as ordinary
# JARs through the JDK's standard URLClassLoader — no UrlNestedJarFile,
# no UrlJarFiles$Cache, no per-request URL parsing storm.
#
# See samples/petclinic-case-study.md for the before/after comparison.
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
