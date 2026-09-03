FROM eclipse-temurin:25 AS build
WORKDIR /usr/src/app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:25
RUN mkdir -p /opt/cc-workernode/data
COPY --from=build /usr/src/app/build/libs/workernode.jar /opt/cc-workernode/workernode.jar
WORKDIR /opt/cc-workernode/data
ENTRYPOINT ["java", "-jar", "/opt/cc-workernode/workernode.jar"]
