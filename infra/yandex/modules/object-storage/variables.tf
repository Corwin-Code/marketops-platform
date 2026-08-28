variable "bucket_name" {
  description = "Globally unique bucket name."
  type        = string
}

variable "folder_id" {
  description = "Yandex Cloud folder the bucket is created in."
  type        = string
}

variable "retention_days" {
  description = "How long an object cannot be deleted or shortened, in days."
  type        = number

  # Evidence supports a claim about a marketplace interaction. A dispute that
  # surfaces after a settlement cycle has to find the bytes still there.
  validation {
    condition     = var.retention_days >= 365
    error_message = "Evidence retention must cover at least a year of settlement and dispute."
  }
}

variable "kms_key_id" {
  description = "Key objects are encrypted with at rest."
  type        = string
}

variable "certificate_id" {
  description = "Certificate the bucket is served over HTTPS with."
  type        = string
}

variable "permitted_service_account_ids" {
  description = "The only identities permitted to touch the bucket at all."
  type        = list(string)

  validation {
    condition     = length(var.permitted_service_account_ids) > 0
    error_message = "A bucket nobody may reach cannot hold evidence anybody can write."
  }
}

variable "labels" {
  description = "Tags applied to the bucket."
  type        = map(string)
  default     = {}
}

variable "infrastructure_service_account_id" {
  description = "Separately authorized Terraform identity; not attached to any application VM."
  type        = string
}
