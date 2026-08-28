variable "environment" {
  description = "Environment this observability serves."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "The environment must be staging or production."
  }
}

variable "folder_id" {
  description = "Yandex Cloud folder the resources are created in."
  type        = string
}

variable "notification_channel_id" {
  description = "Channel every alert reaches. One channel, so none of them stops being read."
  type        = string
}

variable "log_retention" {
  description = "How long application logs are kept."
  type        = string
  default     = "720h"
}

variable "labels" {
  description = "Labels applied to every resource."
  type        = map(string)
  default     = {}
}

variable "audit_service_account_id" {
  type = string
}
