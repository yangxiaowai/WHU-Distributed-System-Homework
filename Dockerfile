ARG DOCKER_IMAGE_PREFIX=docker.m.daocloud.io
FROM ${DOCKER_IMAGE_PREFIX}/library/maven:3.9-eclipse-temurin-17

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Use domestic Maven mirror in container build stage.
RUN cat > /tmp/settings.xml <<'EOF'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <mirrors>
    <mirror>
      <id>aliyunmaven</id>
      <mirrorOf>*</mirrorOf>
      <name>Aliyun Maven</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF

RUN for i in 1 2 3; do \
      mvn -q -s /tmp/settings.xml -DskipTests -Dmaven.wagon.http.retryHandler.count=5 package && exit 0; \
      echo "Maven build failed, retry $i/3 ..."; \
      sleep 5; \
    done; \
    exit 1

ENV TZ=Asia/Shanghai

RUN cp /app/target/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

