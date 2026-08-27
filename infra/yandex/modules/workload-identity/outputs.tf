output "application_service_account_id" {
  description = "Identity the application runs as."
  value       = yandex_iam_service_account.application.id
}

output "acquisition_service_account_id" {
  description = "Identity the acquisition worker runs as."
  value       = yandex_iam_service_account.acquisition.id
}

output "migration_service_account_id" {
  description = "Identity the migration runner runs as."
  value       = yandex_iam_service_account.migration.id
}

output "evidence_writer_ids" {
  description = "The only identities the evidence bucket admits."
  value = [
    yandex_iam_service_account.application.id,
    yandex_iam_service_account.acquisition.id,
  ]
}
