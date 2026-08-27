FROM eclipse-temurin:21-jre-noble@sha256:96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72
WORKDIR /opt/marketops
COPY --chown=10001:10001 --chmod=0444 backend/marketops-server/target/marketops-server-*.jar /opt/marketops/app.jar
ARG ARTIFACT_SHA256
RUN test "$(sha256sum /opt/marketops/app.jar | cut -d ' ' -f1)" = "$ARTIFACT_SHA256"
LABEL org.marketops.artifact-sha256=$ARTIFACT_SHA256
COPY --chown=10001:10001 --chmod=0444 infra/yandex/runtime/certs/yandex-root.crt /opt/marketops/certs/yandex-root.crt
COPY --chown=10001:10001 --chmod=0444 infra/yandex/runtime/migration-logback.xml /opt/marketops/migration-logback.xml
USER 10001:10001
ENTRYPOINT ["java", "-Dlogback.configurationFile=/opt/marketops/migration-logback.xml", "-Dloader.main=com.mimococo.marketops.shared.internal.migration.ManagedMigrationRunner", "-cp", "/opt/marketops/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
