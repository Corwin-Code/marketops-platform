output "network_id" {
  description = "Identifier of the network everything else attaches to."
  value       = yandex_vpc_network.this.id
}

output "subnet_ids" {
  description = "Private subnet identifiers, keyed by availability zone."
  value       = { for zone, subnet in yandex_vpc_subnet.private : zone => subnet.id }
}

output "application_security_group_id" {
  description = "Group the application hosts join."
  value       = yandex_vpc_security_group.application.id
}

output "database_security_group_id" {
  description = "Group the managed database is reachable through."
  value       = yandex_vpc_security_group.database.id
}

output "load_balancer_security_group_id" {
  description = "Group the public entry point joins."
  value       = yandex_vpc_security_group.load_balancer.id
}
