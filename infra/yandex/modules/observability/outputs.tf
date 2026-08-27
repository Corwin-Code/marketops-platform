output "log_group_id" {
  description = "Group application logs are written to."
  value       = yandex_logging_group.this.id
}

output "alert_names" {
  description = "Every alert defined for this environment, for the runbook to reference."
  value = [
    yandex_monitoring_alert.commands_awaiting_a_person.name,
    yandex_monitoring_alert.readback_mismatch.name,
    yandex_monitoring_alert.acquisition_backlog.name,
    yandex_monitoring_alert.write_gate_closed_unexpectedly.name,
    yandex_monitoring_alert.evidence_write_failed.name,
    yandex_monitoring_alert.database_unreachable.name,
  ]
}
