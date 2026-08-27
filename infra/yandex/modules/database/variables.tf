variable "environment" {
  description = "Environment this cluster serves."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "The environment must be staging or production."
  }
}

variable "folder_id" {
  description = "Yandex Cloud folder the cluster is created in."
  type        = string
}

variable "network_id" {
  description = "Network the cluster attaches to."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnet identifier for each availability zone, keyed by zone."
  type        = map(string)
}

variable "security_group_id" {
  description = "Group that admits the application hosts and nothing else."
  type        = string
}

variable "postgresql_version" {
  description = "Major PostgreSQL version. The migrations are written against 18."
  type        = string
  default     = "18"
}

variable "resource_preset_id" {
  description = "Host size."
  type        = string
}

variable "disk_size_gib" {
  description = "Disk size in gibibytes."
  type        = number
}

variable "max_connections" {
  description = "Connection ceiling the cluster accepts."
  type        = number
  default     = 200
}

variable "backup_window_hour" {
  description = "Hour, in UTC, the daily backup starts."
  type        = number
  default     = 22
}

variable "backup_retention_days" {
  description = "How many days of backups and write-ahead logs are retained."
  type        = number

  # Recovery to a point in time is only possible as far back as the retention
  # reaches. Seven days is the floor a settlement correction can arrive within.
  validation {
    condition     = var.backup_retention_days >= 7
    error_message = "Retention must cover at least a week so a late settlement correction is recoverable."
  }
}

variable "database_name" {
  description = "Database the application connects to."
  type        = string
  default     = "marketops"
}

variable "migration_role_name" {
  description = "Role that owns the schemas and applies migrations."
  type        = string
  default     = "marketops_migration"
}

variable "application_role_name" {
  description = "Role the application connects as. It owns nothing."
  type        = string
  default     = "marketops_app"
}

variable "migration_role_password" {
  description = "Migration role password, supplied from Lockbox. Never written down."
  type        = string
  sensitive   = true
}

variable "application_role_password" {
  description = "Application role password, supplied from Lockbox. Never written down."
  type        = string
  sensitive   = true
}

variable "maintenance_day" {
  description = "Day of the week maintenance may run."
  type        = string
  default     = "SUN"
}

variable "maintenance_hour" {
  description = "Hour, in UTC, maintenance may run."
  type        = number
  default     = 23
}

variable "labels" {
  description = "Labels applied to every resource."
  type        = map(string)
  default     = {}
}
