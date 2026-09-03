FROM maven:3.9-eclipse-temurin-25 AS build
RUN mkdir -p /usr/src/app/
WORKDIR /usr/src/app
ADD . /usr/src/app/
RUN mvn package

FROM eclipse-temurin:25
RUN mkdir -p /opt/cc-workernode/data
COPY --from=build /usr/src/app/target/workernode-jar-with-dependencies.jar /opt/cc-workernode/workernode.jar
WORKDIR /opt/cc-workernode/data
ENTRYPOINT ["java", "-jar", "/opt/cc-workernode/workernode.jar"]
