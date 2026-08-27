output "database_hosts" {
  description = "Fully qualified database host names, one per availability zone."
  value       = module.database.connection_hosts
}

output "evidence_bucket" {
  description = "Bucket marketplace evidence is written to."
  value       = module.object_storage.bucket_name
}

output "recovery_reach_days" {
  description = "How far back a point-in-time recovery can reach."
  value       = module.database.backup_retention_days
}

output "evidence_retention_days" {
  description = "How long a stored evidence object cannot be removed."
  value       = module.object_storage.retention_days
}

output "workload_identities" {
  description = "The identities each workload runs as."
  value = {
    application = module.workload_identity.application_service_account_id
    acquisition = module.workload_identity.acquisition_service_account_id
    migration   = module.workload_identity.migration_service_account_id
  }
}

output "alerts" {
  description = "Every alert defined for this environment."
  value       = module.observability.alert_names
}

output "https_origin" {
  value = try(module.workload[0].https_origin, null)
}
output "instance_groups" {
  value = try(module.workload[0].instance_group_ids, {})
}
output "alert_configuration_required" {
  value = module.observability.alert_configuration_required
}
