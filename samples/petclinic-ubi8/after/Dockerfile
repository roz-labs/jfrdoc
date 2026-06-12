FROM registry.access.redhat.com/ubi8/openjdk-17
USER root
RUN microdnf install -y git curl \
 && microdnf clean all
WORKDIR /src
RUN git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git . \
 && ./mvnw -q -DskipTests package \
 && cp target/spring-petclinic-*.jar /app.jar
ENTRYPOINT ["java"]
