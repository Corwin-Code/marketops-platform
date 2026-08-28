# Run only under separately approved infrastructure bootstrap authority.
# This isolated stack bootstraps state custody; it has no application credentials.
terraform {
  required_version = ">= 1.14.9, < 2.0.0"
  required_providers {
    yandex = { source = "yandex-cloud/yandex", version = "= 0.220.0" }
  }
}
provider "yandex" { folder_id = var.folder_id }
resource "yandex_kms_symmetric_key" "state" {
  name                = "marketops-terraform-state"
  folder_id           = var.folder_id
  default_algorithm   = "AES_256"
  rotation_period     = "2160h"
  deletion_protection = true
}
resource "yandex_kms_symmetric_key_iam_binding" "state" {
  symmetric_key_id = yandex_kms_symmetric_key.state.id
  role             = "kms.keys.encrypterDecrypter"
  members          = ["serviceAccount:${var.infrastructure_service_account_id}"]
}
resource "yandex_storage_bucket" "state" {
  bucket        = var.state_bucket_name
  folder_id     = var.folder_id
  force_destroy = false
  versioning { enabled = true }
  anonymous_access_flags {
    read        = false
    list        = false
    config_read = false
  }
  server_side_encryption_configuration {
    rule {
      apply_server_side_encryption_by_default {
        kms_master_key_id = yandex_kms_symmetric_key.state.id
        sse_algorithm     = "aws:kms"
      }
    }
  }
  lifecycle { prevent_destroy = true }
}
resource "yandex_storage_bucket_policy" "state" {
  bucket = yandex_storage_bucket.state.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Deny", Principal = "*", Action = "s3:*", Resource = ["arn:aws:s3:::${var.state_bucket_name}", "arn:aws:s3:::${var.state_bucket_name}/*"], Condition = { Bool = { "aws:SecureTransport" = "false" } } },
      { Effect = "Deny", Principal = "*", Action = "s3:*", Resource = ["arn:aws:s3:::${var.state_bucket_name}", "arn:aws:s3:::${var.state_bucket_name}/*"], Condition = { StringNotEquals = { "aws:userid" = var.infrastructure_service_account_id } } },
      { Effect = "Allow", Principal = { CanonicalUser = var.infrastructure_service_account_id }, Action = ["s3:ListBucket"], Resource = ["arn:aws:s3:::${var.state_bucket_name}"] },
      { Effect = "Allow", Principal = { CanonicalUser = var.infrastructure_service_account_id }, Action = ["s3:GetObject", "s3:PutObject"], Resource = ["arn:aws:s3:::${var.state_bucket_name}/*"] }
    ]
  })
}
resource "yandex_ydb_database_serverless" "locks" {
  name                = "marketops-terraform-locks"
  folder_id           = var.folder_id
  location_id         = "ru-central1"
  deletion_protection = true
  serverless_database {
    enable_throttling_rcu_limit = true
    throttling_rcu_limit        = 10
    storage_size_limit          = 1
  }
}
resource "yandex_ydb_database_iam_binding" "locks" {
  database_id = yandex_ydb_database_serverless.locks.id
  role        = "ydb.editor"
  members     = ["serviceAccount:${var.infrastructure_service_account_id}"]
}
# The Document API table is created with the checked-in create-lock-table.json
# only after this stack has been independently approved. A YDB SQL table is not
# interchangeable with a DynamoDB-compatible Document API table.
resource "yandex_logging_group" "audit" {
  name             = "marketops-state-access"
  folder_id        = var.folder_id
  retention_period = "720h"
}
resource "yandex_iam_service_account" "audit" {
  name      = "marketops-state-audit"
  folder_id = var.folder_id
}
resource "yandex_resourcemanager_folder_iam_member" "audit" {
  for_each  = toset(["logging.writer", "audit-trails.viewer"])
  folder_id = var.folder_id
  role      = each.value
  member    = "serviceAccount:${yandex_iam_service_account.audit.id}"
}
resource "yandex_audit_trails_trail" "state" {
  name               = "marketops-terraform-state"
  folder_id          = var.folder_id
  service_account_id = yandex_iam_service_account.audit.id
  logging_destination { log_group_id = yandex_logging_group.audit.id }
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
  }
  depends_on = [yandex_resourcemanager_folder_iam_member.audit]
}
