# The staging environment, composed from the reviewed modules.
#
# This file is the only place a concrete value appears, and it carries no
# secret: every credential is named by the Lockbox secret it lives under, and
# the value is read at apply time by whoever is authorized to apply.
#
# NOTHING HERE HAS BEEN APPLIED. Applying it is a separate act that only the
# Owner can authorize; it is not implied by this code existing, by a branch
# merging, or by any check passing.

terraform {
  required_version = ">= 1.14.9, < 2.0.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "= 0.220.0"
    }
  }

  # Bootstrap the separate versioned KMS-encrypted state bucket and YDB lock table
  # first; backend.hcl supplies their reviewed identifiers. No credentials here.
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
    encrypt                     = true
    dynamodb_table              = "marketops-terraform-locks"
  }
}

provider "yandex" {
  cloud_id  = var.cloud_id
  folder_id = var.folder_id
  zone      = var.default_zone
}

locals {
  environment = "staging"

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
  application_secret_ids = distinct([for binding in var.runtime_secrets.application : binding.secret_id])
  acquisition_secret_ids = distinct([for binding in var.runtime_secrets.worker : binding.secret_id])
  migration_secret_id    = var.migration_secret_id
  container_registry_id  = var.container_registry_id
}

module "database" {
  source = "../../modules/database"

  environment                  = local.environment
  folder_id                    = var.folder_id
  network_id                   = module.network.network_id
  subnet_ids                   = module.network.subnet_ids
  security_group_id            = module.network.database_security_group_id
  resource_preset_id           = var.database_resource_preset_id
  disk_size_gib                = var.database_disk_size_gib
  backup_retention_days        = var.database_backup_retention_days
  migration_role_password      = var.migration_role_password
  application_role_password    = var.application_role_password
  migration_password_version   = var.migration_password_version
  application_password_version = var.application_password_version
  labels                       = local.labels
}

module "object_storage" {
  source = "../../modules/object-storage"

  bucket_name                       = var.evidence_bucket_name
  folder_id                         = var.folder_id
  retention_days                    = var.evidence_retention_days
  kms_key_id                        = var.evidence_kms_key_id
  certificate_id                    = var.evidence_certificate_id
  permitted_service_account_ids     = module.workload_identity.evidence_writer_ids
  infrastructure_service_account_id = var.infrastructure_service_account_id
  labels                            = local.labels
}

module "observability" {
  source = "../../modules/observability"

  environment              = local.environment
  folder_id                = var.folder_id
  notification_channel_id  = var.notification_channel_id
  audit_service_account_id = module.workload_identity.audit_service_account_id
  labels                   = local.labels
}

module "workload" {
  count                            = var.runtime_enabled ? 1 : 0
  migration_evidence               = var.migration_evidence
  source                           = "../../modules/workload"
  environment                      = local.environment
  folder_id                        = var.folder_id
  network_id                       = module.network.network_id
  subnet_ids                       = module.network.subnet_ids
  application_security_group_id    = module.network.application_security_group_id
  worker_security_group_id         = module.network.worker_security_group_id
  load_balancer_security_group_id  = module.network.load_balancer_security_group_id
  application_service_account_id   = module.workload_identity.application_service_account_id
  worker_service_account_id        = module.workload_identity.acquisition_service_account_id
  group_manager_service_account_id = module.workload_identity.group_manager_service_account_id
  database_cluster_id              = module.database.cluster_id
  evidence_bucket_name             = module.object_storage.bucket_name
  host_image_id                    = var.host_image_id
  backend_image                    = var.backend_image
  console_image                    = var.console_image
  application_instances            = var.application_instances
  worker_instances                 = var.worker_instances
  oidc_issuer_uri                  = var.oidc_issuer_uri
  oidc_jwk_set_uri                 = var.oidc_jwk_set_uri
  oidc_audience                    = var.oidc_audience
  runtime_secrets                  = var.runtime_secrets
  public_hostname                  = var.public_hostname
  certificate_id                   = var.console_certificate_id
  dns_zone_id                      = var.dns_zone_id
  log_group_id                     = module.observability.log_group_id
  labels                           = local.labels
  depends_on                       = [module.workload_identity, module.database, module.object_storage]
}
