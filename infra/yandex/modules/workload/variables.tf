variable "environment" {
  type = string
}

variable "folder_id" {
  type = string
}

variable "network_id" {
  type = string
}

variable "application_service_account_id" {
  type = string
}

variable "worker_service_account_id" {
  type = string
}

variable "group_manager_service_account_id" {
  type = string
}

variable "application_security_group_id" {
  type = string
}

variable "worker_security_group_id" {
  type = string
}

variable "load_balancer_security_group_id" {
  type = string
}

variable "database_cluster_id" {
  type = string
}

variable "evidence_bucket_name" {
  type = string
}

variable "host_image_id" {
  type = string
}

variable "backend_image" {
  type = string
}

variable "console_image" {
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
}

variable "public_hostname" {
  type = string
}

variable "certificate_id" {
  type = string
}

variable "dns_zone_id" {
  type = string
}

variable "log_group_id" {
  type = string
}

variable "subnet_ids" {
  type = map(string)
}
variable "labels" {
  type = map(string)
}
variable "runtime_secrets" {
  description = "Pinned Lockbox references only. Values are fetched inside each VM into tmpfs, never by Terraform."
  type        = map(map(object({ secret_id = string, version_id = string, key = string })))
}

variable "application_instances" {
  type    = number
  default = 3
  validation {
    condition     = var.application_instances >= 1 && floor(var.application_instances) == var.application_instances
    error_message = "A positive integral size is required."
  }
}

variable "worker_instances" {
  type    = number
  default = 1
  validation {
    condition     = var.worker_instances >= 1 && floor(var.worker_instances) == var.worker_instances
    error_message = "A positive integral size is required."
  }
}

variable "instance_cores" {
  type    = number
  default = 2
  validation {
    condition     = var.instance_cores >= 1 && floor(var.instance_cores) == var.instance_cores
    error_message = "A positive integral size is required."
  }
}

variable "instance_memory_gib" {
  type    = number
  default = 4
  validation {
    condition     = var.instance_memory_gib >= 1 && floor(var.instance_memory_gib) == var.instance_memory_gib
    error_message = "A positive integral size is required."
  }
}


variable "migration_evidence" {
  description = "Hash-pinned successful migration result checked before any runtime credential access."
  type        = object({ document = string, sha256 = string })
}
