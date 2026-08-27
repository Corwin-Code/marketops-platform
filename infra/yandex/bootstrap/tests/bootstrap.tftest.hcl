mock_provider "yandex" { override_during = plan }
run "state_custody_plan" {
  command = plan
  variables {
    folder_id                         = "ffffffffffffffffffff"
    infrastructure_service_account_id = "iiiiiiiiiiiiiiiiiiii"
    state_bucket_name                 = "marketops-offline-plan-state"
  }
  assert {
    condition     = yandex_storage_bucket.state.versioning[0].enabled && !yandex_storage_bucket.state.force_destroy
    error_message = "Remote state must be versioned and protected from force deletion."
  }
  assert {
    condition     = yandex_storage_bucket.state.server_side_encryption_configuration[0].rule[0].apply_server_side_encryption_by_default[0].sse_algorithm == "aws:kms"
    error_message = "State encryption is required, not just display redaction."
  }
  assert {
    condition     = yandex_ydb_database_serverless.locks.deletion_protection
    error_message = "The lock database cannot be disposable."
  }
}
