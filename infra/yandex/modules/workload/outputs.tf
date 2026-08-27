output "https_origin" {
  value = "https://${var.public_hostname}"
}
output "instance_group_ids" {
  value = { for role, group in yandex_compute_instance_group.runtime : role => group.id }
}
