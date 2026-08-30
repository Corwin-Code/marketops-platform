variable "cloud_id" {
  description = "Yandex Cloud this environment lives in."
  type        = string
}

variable "folder_id" {
  description = "Folder every resource is created in."
  type        = string
}

variable "default_zone" {
  description = "Zone used where a resource does not name one."
  type        = string
  default     = "ru-central1-a"
}

variable "availability_zones" {
  description = "Private subnet CIDR for each availability zone."
  type        = map(string)
}

variable "database_resource_preset_id" {
  description = "Managed PostgreSQL host size."
  type        = string
}

variable "database_disk_size_gib" {
  description = "Managed PostgreSQL disk size."
  type        = number
}

variable "database_backup_retention_days" {
  description = "How far back a point-in-time recovery can reach."
  type        = number
}

variable "evidence_bucket_name" {
  description = "Bucket marketplace evidence is written to."
  type        = string
}

variable "evidence_retention_days" {
  description = "How long a stored evidence object cannot be removed."
  type        = number
}

variable "evidence_kms_key_id" {
  description = "Key evidence objects are encrypted with at rest."
  type        = string
}

variable "evidence_certificate_id" {
  description = "Certificate the bucket is served over HTTPS with."
  type        = string
}



variable "migration_secret_id" {
  description = "The single Lockbox secret the migration runner may read."
  type        = string
}

variable "migration_role_password" {
  description = "Migration role password, supplied at apply time from Lockbox."
  type        = string
  sensitive   = true
  ephemeral   = true
}

variable "application_role_password" {
  description = "Application role password, supplied at apply time from Lockbox."
  type        = string
  sensitive   = true
  ephemeral   = true
}

variable "notification_channel_id" {
  description = "Channel every alert reaches."
  type        = string
}

variable "migration_password_version" {
  type = number
}
variable "application_password_version" {
  type = number
}

variable "infrastructure_service_account_id" {
  type = string
}

variable "container_registry_id" {
  type = string
}

variable "host_image_id" {
  type = string
}

variable "oidc_issuer_uri" {
  type = string
}

variable "oidc_jwk_set_uri" {
  type = string
}

variable "oidc_audience" {
  type = string
  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}$", var.oidc_audience))
    error_message = "A nonblank bounded OIDC audience is required."
  }
}

variable "public_hostname" {
  type = string
}

variable "console_certificate_id" {
  type = string
}

variable "dns_zone_id" {
  type = string
}

variable "backend_image" {
  type = string
  validation {
    condition     = can(regex("^cr[.]yandex/[a-z0-9/_.-]+@sha256:[0-9a-f]{64}$", var.backend_image))
    error_message = "An immutable digest-pinned Yandex registry image is required."
  }
}

variable "console_image" {
  type = string
  validation {
    condition     = can(regex("^cr[.]yandex/[a-z0-9/_.-]+@sha256:[0-9a-f]{64}$", var.console_image))
    error_message = "An immutable digest-pinned Yandex registry image is required."
  }
}

variable "application_instances" {
  type    = number
  default = 3
}
variable "worker_instances" {
  type    = number
  default = 1
}
variable "runtime_secrets" {
  description = "Pinned Lockbox references, keyed by runtime role and relative tmpfs destination. No payloads."
  type        = map(map(object({ secret_id = string, version_id = string, key = string })))
  validation {
    condition     = toset(keys(var.runtime_secrets)) == toset(["application", "worker"])
    error_message = "Exactly application and worker secret mappings are required."
  }
}


variable "runtime_enabled" {
  description = "Foundation first. A separate reviewed migration result is required before creating runtime resources. Not production-write authority."
  type        = bool
  default     = false
}

variable "migration_evidence" {
  description = "Exact redacted managed migration result JSON and its release-pinned SHA-256; never a credential or Terraform plan."
  type        = object({ document = string, sha256 = string })
  default     = null
  validation {
    condition = !var.runtime_enabled || try(
      var.migration_evidence.sha256 == sha256(var.migration_evidence.document) &&
      jsondecode(var.migration_evidence.document).migrationResult == "SUCCESS" &&
    jsondecode(var.migration_evidence.document).serviceProfile == "YANDEX_MANAGED", false)
    error_message = "Runtime requires the exact hash-pinned successful managed migration result."
  }
}
