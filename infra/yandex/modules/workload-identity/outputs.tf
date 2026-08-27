output "application_service_account_id" {
  description = "Identity the application runs as."
  value       = yandex_iam_service_account.roles["application"].id
}

output "acquisition_service_account_id" {
  description = "Identity the acquisition worker runs as."
  value       = yandex_iam_service_account.roles["acquisition"].id
}

output "migration_service_account_id" {
  description = "Identity the migration runner runs as."
  value       = yandex_iam_service_account.roles["migration"].id
}

output "evidence_writer_ids" {
  description = "The only identities the evidence bucket admits."
  value = [
    yandex_iam_service_account.roles["application"].id,
    yandex_iam_service_account.roles["acquisition"].id,
  ]
}

output "group_manager_service_account_id" {
  value = yandex_iam_service_account.roles["group-manager"].id
}
output "audit_service_account_id" {
  value = yandex_iam_service_account.roles["audit"].id
}
