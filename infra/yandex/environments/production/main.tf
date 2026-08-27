# The production environment, composed from the reviewed modules.
#
# This file is the only place a concrete value appears, and it carries no
# secret: every credential is named by the Lockbox secret it lives under, and
# the value is read at apply time by whoever is authorized to apply.
#
# NOTHING HERE HAS BEEN APPLIED. Applying it is a separate act that only the
# Owner can authorize; it is not implied by this code existing, by a branch
# merging, or by any check passing.

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "~> 0.140"
    }
  }

  # State lives in the same account as the resources it describes, versioned and
  # locked. A state file on somebody's laptop is how two people apply at once.
  backend "s3" {
    endpoints = {
      s3       = "https://storage.yandexcloud.net"
      dynamodb = "https://docapi.serverless.yandexcloud.net"
    }
    region                      = "ru-central1"
    skip_region_validation      = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_s3_checksum            = true
  }
}

provider "yandex" {
  cloud_id  = var.cloud_id
  folder_id = var.folder_id
  zone      = var.default_zone
}

locals {
  environment = "production"

  labels = {
    product     = "marketops"
    environment = local.environment
    managed_by  = "terraform"
  }
}

module "network" {
  source = "../../modules/network"

  environment        = local.environment
  folder_id          = var.folder_id
  availability_zones = var.availability_zones
  labels             = local.labels
}

module "workload_identity" {
  source = "../../modules/workload-identity"

  environment            = local.environment
  folder_id              = var.folder_id
  application_secret_ids = var.application_secret_ids
  acquisition_secret_ids = var.acquisition_secret_ids
  migration_secret_id    = var.migration_secret_id
}

module "database" {
  source = "../../modules/database"

  environment               = local.environment
  folder_id                 = var.folder_id
  network_id                = module.network.network_id
  subnet_ids                = module.network.subnet_ids
  security_group_id         = module.network.database_security_group_id
  resource_preset_id        = var.database_resource_preset_id
  disk_size_gib             = var.database_disk_size_gib
  backup_retention_days     = var.database_backup_retention_days
  migration_role_password   = var.migration_role_password
  application_role_password = var.application_role_password
  labels                    = local.labels
}

module "object_storage" {
  source = "../../modules/object-storage"

  bucket_name                   = var.evidence_bucket_name
  folder_id                     = var.folder_id
  retention_days                = var.evidence_retention_days
  kms_key_id                    = var.evidence_kms_key_id
  certificate_id                = var.evidence_certificate_id
  permitted_service_account_ids = module.workload_identity.evidence_writer_ids
  labels                        = local.labels
}

module "observability" {
  source = "../../modules/observability"

  environment             = local.environment
  folder_id               = var.folder_id
  notification_channel_id = var.notification_channel_id
  labels                  = local.labels
}
