# dist is built and verified with the lockfile and public per-environment OIDC settings.
FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:93722936b82ec8a1178d48448e619226680d2de3706a1640800e186cd5fa7fd3
COPY --chown=10001:10001 --chmod=0444 infra/yandex/runtime/nginx.conf /etc/nginx/nginx.conf
COPY --chown=10001:10001 frontend/marketops-console/dist/ /usr/share/nginx/html/
USER 10001:10001
EXPOSE 8088
ENTRYPOINT ["nginx", "-g", "daemon off;"]
