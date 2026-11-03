FROM maven:3.9.15-eclipse-temurin-21@sha256:d6e32a254897415a445654a3c43c30fbc731a4d946cf5c66d1cb9184141c20c1 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xms64m -Xmx384m"
ENV JAVA_TOOL_OPTIONS="-Xmx384m"
COPY . .
RUN --mount=type=cache,id=mprp-java-central-v1,target=/root/.m2,sharing=locked java -version && mvn -version && mvn -B verify
FROM eclipse-temurin:21.0.13_9-jre-alpine@sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372 AS runtime
RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app && mkdir /data && chown app:app /data
WORKDIR /app
ARG MODULE=application
COPY --from=build /build/${MODULE}/target/${MODULE}-1.0.0.jar /app/application.jar
USER 10001:10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/application.jar"]
