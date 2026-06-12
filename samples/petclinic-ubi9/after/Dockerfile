FROM registry.access.redhat.com/ubi9/openjdk-21
USER root
RUN microdnf install -y git \
 && microdnf clean all
WORKDIR /src
RUN git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git . \
 && ./mvnw -q -DskipTests package \
 && cp target/spring-petclinic-*.jar /app.jar
ENTRYPOINT ["java"]
