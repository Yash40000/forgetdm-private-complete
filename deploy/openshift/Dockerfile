# ForgeTDM — production container image for OpenShift / Kubernetes (RFP §3.1.2)
#
# Multi-stage: build the Spring Boot jar, then run it on a slim JRE as a non-root user.
# OpenShift runs containers with an arbitrary (random) UID in the root group (GID 0), so all
# runtime paths are owned by group 0 and group-writable.

# ---- build stage ---------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Cache dependencies first for faster incremental builds.
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests clean package && \
    cp target/*.jar /src/app.jar

# ---- runtime stage -------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# App + writable staging/vault data root (mounted as a PVC in the cluster).
ENV APP_HOME=/opt/forgetdm \
    DATA_ROOT=/data \
    # Container-aware heap + point the storage pool / tmp at the persistent volume.
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/data/tmp"

WORKDIR ${APP_HOME}
COPY --from=build /src/app.jar app.jar

# OpenShift-friendly permissions: non-root user, group 0, group-writable data dirs.
RUN mkdir -p ${DATA_ROOT}/tmp ${DATA_ROOT}/pool ${DATA_ROOT}/vault && \
    chgrp -R 0 ${APP_HOME} ${DATA_ROOT} && \
    chmod -R g=u ${APP_HOME} ${DATA_ROOT}

USER 1001
EXPOSE 8088 8090

# Application port 8088, management/health port 8090 (see application.yml).
# The PVC mounts over /data at runtime, so (re)create the writable subdirs before launch.
ENTRYPOINT ["sh", "-c", "mkdir -p /data/tmp /data/vault && exec java $JAVA_OPTS -jar app.jar"]
