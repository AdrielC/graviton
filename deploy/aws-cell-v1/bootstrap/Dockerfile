FROM postgres:16@sha256:e17e86066e5ef83e0952a9347f5c792b7ece00972e2aa787a6986f471b3dd3d5

COPY --chmod=0555 scripts/migrate-postgres.sh /opt/graviton/migrate-postgres.sh
COPY --chmod=0555 deploy/production/init-postgres-app-role.sh /opt/graviton/init-postgres-app-role.sh
COPY --chmod=0555 scripts/provision-tenant.sh /opt/graviton/provision-tenant.sh
COPY --chmod=0444 modules/backend/graviton-pg/src/main/resources/ddl.sql /opt/graviton/ddl.sql
COPY --chmod=0555 deploy/aws-cell-v1/bootstrap/bootstrap.sh /opt/graviton/bootstrap.sh

USER 999:999
ENTRYPOINT ["/opt/graviton/bootstrap.sh"]
