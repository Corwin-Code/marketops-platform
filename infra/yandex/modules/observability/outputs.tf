output "log_group_id" {
  value = yandex_logging_group.this.id
}
output "alert_names" {
  value = [for control in local.alert_requirements.controls : control.name]
}
output "alert_configuration_required" {
  value = { channel_id = var.notification_channel_id, controls = local.alert_requirements.controls, verified = false }
}
