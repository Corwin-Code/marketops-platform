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

variable "application_secret_ids" {
  description = "Lockbox secrets the application may read."
  type        = list(string)
}

variable "acquisition_secret_ids" {
  description = "Lockbox secrets the acquisition worker may read."
  type        = list(string)
}

variable "migration_secret_id" {
  description = "The single Lockbox secret the migration runner may read."
  type        = string
}

variable "migration_role_password" {
  description = "Migration role password, supplied at apply time from Lockbox."
  type        = string
  sensitive   = true
}

variable "application_role_password" {
  description = "Application role password, supplied at apply time from Lockbox."
  type        = string
  sensitive   = true
}

variable "notification_channel_id" {
  description = "Channel every alert reaches."
  type        = string
}
