variable "environment" {
  description = "Environment these identities serve."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "The environment must be staging or production."
  }
}

variable "folder_id" {
  description = "Yandex Cloud folder the identities are created in."
  type        = string
}

variable "application_secret_ids" {
  description = "Lockbox secrets the application may read. Kept explicit rather than folder-wide."
  type        = list(string)
}

variable "acquisition_secret_ids" {
  description = "Lockbox secrets the acquisition worker may read, including marketplace credentials."
  type        = list(string)
}

variable "migration_secret_id" {
  description = "The single Lockbox secret the migration runner may read."
  type        = string
}
