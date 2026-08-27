# Runtime identities cannot create infrastructure, alter the database schema,
# or read one another's pinned secrets. The group manager has no payload access.
terraform {
  required_version = ">= 1.14.9, < 2.0.0"
  required_providers {
    yandex = { source = "yandex-cloud/yandex", version = "= 0.220.0" }
  }
}
resource "yandex_iam_service_account" "roles" {
  for_each  = toset(["application", "acquisition", "migration", "group-manager", "audit"])
  name      = "${var.environment}-marketops-${each.key}"
  folder_id = var.folder_id
}
locals {
  secrets = toset(concat(var.application_secret_ids, var.acquisition_secret_ids, [var.migration_secret_id]))
  secret_roles = {
    for id in local.secrets : id => concat(
      contains(var.application_secret_ids, id) ? ["serviceAccount:${yandex_iam_service_account.roles["application"].id}"] : [],
      contains(var.acquisition_secret_ids, id) ? ["serviceAccount:${yandex_iam_service_account.roles["acquisition"].id}"] : [],
      id == var.migration_secret_id ? ["serviceAccount:${yandex_iam_service_account.roles["migration"].id}"] : []
    )
  }
}
resource "yandex_lockbox_secret_iam_binding" "payloads" {
  for_each  = local.secret_roles
  secret_id = each.key
  role      = "lockbox.payloadViewer"
  members   = each.value
}
resource "yandex_container_registry_iam_binding" "image_readers" {
  registry_id = var.container_registry_id
  role        = "container-registry.images.puller"
  members     = [for role in ["application", "acquisition", "migration"] : "serviceAccount:${yandex_iam_service_account.roles[role].id}"]
}
resource "yandex_resourcemanager_folder_iam_member" "group_manager" {
  for_each  = toset(["compute.editor", "vpc.user"])
  folder_id = var.folder_id
  role      = each.value
  member    = "serviceAccount:${yandex_iam_service_account.roles["group-manager"].id}"
}
resource "yandex_iam_service_account_iam_binding" "group_manager_act_as" {
  for_each           = toset(["application", "acquisition"])
  service_account_id = yandex_iam_service_account.roles[each.key].id
  role               = "iam.serviceAccounts.user"
  members            = ["serviceAccount:${yandex_iam_service_account.roles["group-manager"].id}"]
}
resource "yandex_resourcemanager_folder_iam_member" "logs" {
  for_each  = toset(["application", "acquisition", "migration", "audit"])
  folder_id = var.folder_id
  role      = "logging.writer"
  member    = "serviceAccount:${yandex_iam_service_account.roles[each.key].id}"
}
resource "yandex_resourcemanager_folder_iam_member" "audit" {
  folder_id = var.folder_id
  role      = "audit-trails.viewer"
  member    = "serviceAccount:${yandex_iam_service_account.roles["audit"].id}"
}

# Monitoring currently offers editor as its narrowest metric-upload role.
# This is service-scoped; it grants no DB, secret, IAM or Marketplace authority.
resource "yandex_resourcemanager_folder_iam_member" "telemetry" {
  for_each  = toset(["application", "acquisition"])
  folder_id = var.folder_id
  role      = "monitoring.editor"
  member    = "serviceAccount:${yandex_iam_service_account.roles[each.key].id}"
}
