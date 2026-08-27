variable "environment" {
  description = "Environment this network belongs to."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "The environment must be staging or production."
  }
}

variable "folder_id" {
  description = "Yandex Cloud folder every resource is created in."
  type        = string
}

variable "availability_zones" {
  description = "Private subnet CIDR for each availability zone, keyed by zone."
  type        = map(string)

  # A managed PostgreSQL cluster places its hosts across zones. Fewer than
  # three subnets quietly reduces the cluster to the zones that have one, which
  # is a availability decision nobody made.
  validation {
    condition     = length(var.availability_zones) >= 3
    error_message = "Three availability zones are required so the database can spread across them."
  }
}

variable "application_port" {
  description = "Port the application listens on."
  type        = number
  default     = 8080
}

variable "health_check_cidrs" {
  description = "Ranges the managed load balancer performs health checks from."
  type        = list(string)
  default     = ["198.18.235.0/24", "198.18.248.0/24"]
}

variable "labels" {
  description = "Labels applied to every resource, for cost and ownership reporting."
  type        = map(string)
  default     = {}
}
