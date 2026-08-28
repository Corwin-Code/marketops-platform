output "cluster_id" {
  description = "Identifier of the managed cluster."
  value       = yandex_mdb_postgresql_cluster.this.id
}

output "database_name" {
  description = "Database the application connects to."
  value       = yandex_mdb_postgresql_database.this.name
}

output "connection_hosts" {
  description = "Fully qualified host names, one per availability zone."
  value       = [for host in yandex_mdb_postgresql_cluster.this.host : host.fqdn]
}

output "backup_retention_days" {
  description = "How far back a point-in-time recovery can reach."
  value       = var.backup_retention_days
}
