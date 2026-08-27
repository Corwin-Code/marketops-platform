# What the platform tells an operator when something is wrong.
#
# The alerts here are chosen so that each one names something a person can act
# on within the hour. An alert nobody can act on gets muted, and a muted channel
# is how the alert that mattered goes unread.
#
# The two that matter most are the ones about not knowing. A command whose
# outcome cannot be classified may have changed a real price, and a readback
# that observed something unintended means the marketplace holds a value nobody
# decided. Both are quiet failures: nothing is down, nothing is throwing, and
# the product is wrong.

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "~> 0.140"
    }
  }
}

resource "yandex_logging_group" "this" {
  name             = "${var.environment}-marketops"
  description      = "MarketOps ${var.environment} application logs"
  folder_id        = var.folder_id
  retention_period = var.log_retention

  labels = var.labels
}

resource "yandex_monitoring_dashboard" "operations" {
  name        = "${var.environment}-marketops-operations"
  title       = "MarketOps ${var.environment}"
  description = "The state an operator checks first"
  folder_id   = var.folder_id

  labels = var.labels

  parametrization {
    parameters {
      id          = "store"
      title       = "Store"
      hidden      = false
      label_values {
        default_values  = ["*"]
        label_key       = "store_id"
        multiselectable = true
        selector_name   = "store"
      }
    }
  }

  widgets {
    text {
      text = <<-EOT
        A command in UNKNOWN_REQUIRES_READBACK may already have changed a real
        price. It is never retried automatically; a person resolves it.
      EOT
    }

    position {
      h = 2
      w = 12
      x = 0
      y = 0
    }
  }
}

# --- Alerts ---------------------------------------------------------------

locals {
  # Every alert reaches the same channel. Splitting them across channels by
  # severity is how the low-severity channel stops being read and a real
  # problem lands in it.
  notification_channels = [var.notification_channel_id]
}

resource "yandex_monitoring_alert" "commands_awaiting_a_person" {
  name        = "${var.environment}-marketops-commands-awaiting-a-person"
  description = "A price command cannot resolve itself and needs an operator."
  folder_id   = var.folder_id

  annotations = {
    runbook = "docs/06-runbooks/price-command-resolution.md"
    meaning = "The marketplace may hold a price nobody decided."
  }

  type {
    from_selectors {
      selectors = "service=\"marketops\", name=\"price_command_awaiting_operator\""
    }
  }

  # Any at all. There is no acceptable number of price changes whose outcome
  # nobody knows.
  annotation_targets  = ["runbook", "meaning"]
  notification_channels = local.notification_channels

  severity                     = "ALARM"
  window_seconds               = 300
  delay_seconds                = 0
  escalate_notification_channels = true
}

resource "yandex_monitoring_alert" "readback_mismatch" {
  name        = "${var.environment}-marketops-readback-mismatch"
  description = "A readback observed a price other than the one intended."
  folder_id   = var.folder_id

  annotations = {
    runbook = "docs/06-runbooks/price-command-resolution.md"
    meaning = "Either the write went wrong or something else moved the price."
  }

  type {
    from_selectors {
      selectors = "service=\"marketops\", name=\"price_command_readback_mismatch\""
    }
  }

  annotation_targets  = ["runbook", "meaning"]
  notification_channels = local.notification_channels

  severity       = "ALARM"
  window_seconds = 300
  delay_seconds  = 0
}

resource "yandex_monitoring_alert" "acquisition_backlog" {
  name        = "${var.environment}-marketops-acquisition-backlog"
  description = "Marketplace facts are falling behind, so every figure ages."
  folder_id   = var.folder_id

  annotations = {
    runbook = "docs/06-runbooks/acquisition-backlog.md"
    meaning = "Metrics will begin refusing to support a write as inputs go stale."
  }

  type {
    from_selectors {
      selectors = "service=\"marketops\", name=\"ingestion_run_backlog_age_seconds\""
    }
  }

  annotation_targets  = ["runbook", "meaning"]
  notification_channels = local.notification_channels

  severity       = "WARN"
  window_seconds = 900
  delay_seconds  = 0
}

resource "yandex_monitoring_alert" "write_gate_closed_unexpectedly" {
  name        = "${var.environment}-marketops-write-gate-closed"
  description = "Approved commands are being refused by the write gate."
  folder_id   = var.folder_id

  annotations = {
    runbook = "docs/06-runbooks/price-command-resolution.md"
    meaning = "Somebody approved work the platform will not perform; they should know why."
  }

  type {
    from_selectors {
      selectors = "service=\"marketops\", name=\"price_command_gate_closed\""
    }
  }

  annotation_targets  = ["runbook", "meaning"]
  notification_channels = local.notification_channels

  severity       = "WARN"
  window_seconds = 900
  delay_seconds  = 0
}

resource "yandex_monitoring_alert" "evidence_write_failed" {
  name        = "${var.environment}-marketops-evidence-write-failed"
  description = "A marketplace answer could not be stored, so it cannot be proven later."
  folder_id   = var.folder_id

  annotations = {
    runbook = "docs/06-runbooks/evidence-custody.md"
    meaning = "Acquisition stops rather than continuing without custody."
  }

  type {
    from_selectors {
      selectors = "service=\"marketops\", name=\"raw_custody_write_failed\""
    }
  }

  annotation_targets  = ["runbook", "meaning"]
  notification_channels = local.notification_channels

  severity       = "ALARM"
  window_seconds = 300
  delay_seconds  = 0
}

resource "yandex_monitoring_alert" "database_unreachable" {
  name        = "${var.environment}-marketops-database-unreachable"
  description = "The application cannot reach its database."
  folder_id   = var.folder_id

  annotations = {
    runbook = "docs/06-runbooks/troubleshooting.md"
    meaning = "Nothing works, and the console says so rather than showing stale figures."
  }

  type {
    from_selectors {
      selectors = "service=\"marketops\", name=\"database_readiness_failed\""
    }
  }

  annotation_targets  = ["runbook", "meaning"]
  notification_channels = local.notification_channels

  severity       = "ALARM"
  window_seconds = 180
  delay_seconds  = 0
}
