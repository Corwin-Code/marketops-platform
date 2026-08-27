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
  required_version = ">= 1.14.9, < 2.0.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "= 0.220.0"
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
      id     = "store"
      title  = "Store"
      hidden = false
      label_values {
        default_values  = ["*"]
        label_key       = "store_id"
        multiselectable = true
        selectors       = "service=\"marketops\""
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

# Monitoring's pinned provider exposes dashboards, not alert resources.
# This manifest preserves the six mandatory controls. Their account-side creation,
# telemetry delivery and notification test are required environment evidence;
# a successful Terraform plan never substitutes for that evidence.
locals {
  alert_requirements = jsondecode(file("${path.module}/alert-requirements.json"))
}
resource "yandex_audit_trails_trail" "this" {
  name               = "${var.environment}-marketops"
  folder_id          = var.folder_id
  service_account_id = var.audit_service_account_id
  logging_destination { log_group_id = yandex_logging_group.this.id }
  filtering_policy {
    management_events_filter {
      resource_scope {
        resource_id   = var.folder_id
        resource_type = "resource-manager.folder"
      }
    }
    data_events_filter {
      service = "storage"
      resource_scope {
        resource_id   = var.folder_id
        resource_type = "resource-manager.folder"
      }
    }
    data_events_filter {
      service = "lockbox"
      resource_scope {
        resource_id   = var.folder_id
        resource_type = "resource-manager.folder"
      }
    }
  }
  labels = var.labels
}
