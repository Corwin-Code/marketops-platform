output "bucket_name" {
  description = "Bucket evidence is written to."
  value       = yandex_storage_bucket.evidence.bucket
}

output "bucket_domain_name" {
  description = "Host the bucket is addressed by."
  value       = yandex_storage_bucket.evidence.bucket_domain_name
}

output "retention_days" {
  description = "How long a stored object cannot be removed."
  value       = var.retention_days
}
