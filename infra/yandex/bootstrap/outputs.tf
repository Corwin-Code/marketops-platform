output "state_bucket_name" {
  value = yandex_storage_bucket.state.bucket
}
output "state_kms_key_id" {
  value = yandex_kms_symmetric_key.state.id
}
output "lock_document_api_endpoint" {
  value = yandex_ydb_database_serverless.locks.document_api_endpoint
}
output "lock_table_name" {
  value = "marketops-terraform-locks"
}
