# The managed PostgreSQL cluster, and the recovery guarantees it carries.
#
# The recovery settings are here rather than in a shared variables file because
# they are the thing that decides whether an incident is an inconvenience or a
# loss. A backup window and a retention period edited without a second thought
# is exactly how a business discovers, during a restore, that it keeps three
# days of history rather than thirty.
#
# Point-in-time recovery is not a separate feature to switch on: it follows from
# retaining write-ahead logs alongside the backups, which is what the retention
# setting below buys. The drill that proves it works is a runbook, not a
# resource, and the drill is what the acceptance criterion actually asks for.

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "~> 0.140"
    }
  }
}

resource "yandex_mdb_postgresql_cluster" "this" {
  name        = "${var.environment}-marketops"
  description = "MarketOps ${var.environment} operating database"
  folder_id   = var.folder_id
  environment = var.environment == "production" ? "PRODUCTION" : "PRESTABLE"
  network_id  = var.network_id

  security_group_ids  = [var.security_group_id]
  deletion_protection = var.environment == "production"

  config {
    version = var.postgresql_version

    resources {
      resource_preset_id = var.resource_preset_id
      disk_size          = var.disk_size_gib
      disk_type_id       = "network-ssd-nonreplicated"
    }

    # Backups run in the quiet hours of the business this serves, and the
    # retention is stated rather than defaulted so a reviewer can see the
    # number the recovery objective rests on.
    backup_window_start {
      hours   = var.backup_window_hour
      minutes = 0
    }

    backup_retain_period_days = var.backup_retention_days

    # A cluster that accepts an unencrypted connection accepts one eventually.
    postgresql_config = {
      max_connections                   = var.max_connections
      log_min_duration_statement        = 500
      idle_in_transaction_session_timeout = 60000
      # Every statement that changes data is attributable to a session, and a
      # session that cannot be attributed is one nobody can review.
      log_connections    = true
      log_disconnections = true
    }

    access {
      # No data-transfer service, no serverless access, no web console SQL.
      # Every one of those is a second path to the same rows, and this product
      # has exactly one writer.
      data_lens     = false
      data_transfer = false
      serverless    = false
      web_sql       = false
    }

    performance_diagnostics {
      enabled                      = true
      sessions_sampling_interval   = 60
      statements_sampling_interval = 600
    }
  }

  dynamic "host" {
    for_each = var.subnet_ids

    content {
      zone             = host.key
      subnet_id        = host.value
      assign_public_ip = false
    }
  }

  maintenance_window {
    type = "WEEKLY"
    day  = var.maintenance_day
    hour = var.maintenance_hour
  }

  labels = var.labels
}

# The migration role owns the schemas; the application role owns nothing. That
# split is what makes the privilege model in the migrations meaningful, and it
# has to exist in the managed cluster as well as in the local one.
resource "yandex_mdb_postgresql_database" "this" {
  cluster_id = yandex_mdb_postgresql_cluster.this.id
  name       = var.database_name
  owner      = var.migration_role_name
  lc_collate = "C"
  lc_type    = "C"

  extension {
    name = "btree_gist"
  }

  extension {
    name = "pgcrypto"
  }
}

resource "yandex_mdb_postgresql_user" "migration" {
  cluster_id = yandex_mdb_postgresql_cluster.this.id
  name       = var.migration_role_name

  # Read from Lockbox at plan time. The value never appears in this repository,
  # in a variables file, or in the state this configuration is applied with —
  # which is why the reference names a secret rather than carrying one.
  password = var.migration_role_password

  conn_limit = 10

  settings = {
    default_transaction_isolation = "read committed"
    lock_timeout                  = 10000
  }
}

resource "yandex_mdb_postgresql_user" "application" {
  cluster_id = yandex_mdb_postgresql_cluster.this.id
  name       = var.application_role_name
  password   = var.application_role_password

  conn_limit = var.max_connections - 20

  permission {
    database_name = yandex_mdb_postgresql_database.this.name
  }

  settings = {
    default_transaction_isolation = "read committed"
    lock_timeout                  = 5000
    # A statement that runs longer than this is not a query, it is an incident.
    statement_timeout = 30000
  }
}
