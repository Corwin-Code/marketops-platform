# What each workload is allowed to do, and nothing more.
#
# Three identities rather than one, because the three do genuinely different
# things and the blast radius of each compromise should be different. The
# application serves requests and reads evidence. The acquisition worker reaches
# marketplaces and writes evidence. The migration runner changes the schema and
# runs nowhere else.
#
# The roles are the narrowest Yandex publishes for each job. Where the narrow
# role does not exist, the binding is scoped to a single resource rather than to
# the folder, so the identity can act on one thing instead of on a class of
# things.

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "~> 0.140"
    }
  }
}

resource "yandex_iam_service_account" "application" {
  name        = "${var.environment}-marketops-application"
  description = "Serves the console and API. Reads evidence; never writes it."
  folder_id   = var.folder_id
}

resource "yandex_iam_service_account" "acquisition" {
  name        = "${var.environment}-marketops-acquisition"
  description = "Reaches marketplaces and writes evidence. Never changes the schema."
  folder_id   = var.folder_id
}

resource "yandex_iam_service_account" "migration" {
  name        = "${var.environment}-marketops-migration"
  description = "Applies migrations. Runs only as a deployment step."
  folder_id   = var.folder_id
}

# --- The application ------------------------------------------------------

# Reads objects and nothing else. It has no upload role at all, so an
# application defect cannot write over a stored evidence object even before the
# bucket's own lock is considered.
resource "yandex_resourcemanager_folder_iam_member" "application_storage_read" {
  folder_id = var.folder_id
  role      = "storage.viewer"
  member    = "serviceAccount:${yandex_iam_service_account.application.id}"
}

resource "yandex_lockbox_secret_iam_binding" "application_secrets" {
  for_each = toset(var.application_secret_ids)

  secret_id = each.value
  role      = "lockbox.payloadViewer"
  members   = ["serviceAccount:${yandex_iam_service_account.application.id}"]
}

resource "yandex_resourcemanager_folder_iam_member" "application_logs" {
  folder_id = var.folder_id
  role      = "logging.writer"
  member    = "serviceAccount:${yandex_iam_service_account.application.id}"
}

resource "yandex_resourcemanager_folder_iam_member" "application_monitoring" {
  folder_id = var.folder_id
  role      = "monitoring.editor"
  member    = "serviceAccount:${yandex_iam_service_account.application.id}"
}

# --- The acquisition worker ----------------------------------------------

# Uploads evidence. It cannot delete, and the bucket's compliance lock means it
# could not delete even if the role permitted it.
resource "yandex_resourcemanager_folder_iam_member" "acquisition_storage_upload" {
  folder_id = var.folder_id
  role      = "storage.uploader"
  member    = "serviceAccount:${yandex_iam_service_account.acquisition.id}"
}

resource "yandex_resourcemanager_folder_iam_member" "acquisition_storage_read" {
  folder_id = var.folder_id
  role      = "storage.viewer"
  member    = "serviceAccount:${yandex_iam_service_account.acquisition.id}"
}

resource "yandex_lockbox_secret_iam_binding" "acquisition_secrets" {
  for_each = toset(var.acquisition_secret_ids)

  secret_id = each.value
  role      = "lockbox.payloadViewer"
  members   = ["serviceAccount:${yandex_iam_service_account.acquisition.id}"]
}

resource "yandex_resourcemanager_folder_iam_member" "acquisition_logs" {
  folder_id = var.folder_id
  role      = "logging.writer"
  member    = "serviceAccount:${yandex_iam_service_account.acquisition.id}"
}

# --- The migration runner -------------------------------------------------

# Reads exactly one secret and writes exactly one kind of log. It has no storage
# role: a migration that could write to the evidence bucket would be a second
# path to the bytes the product treats as immutable.
resource "yandex_lockbox_secret_iam_binding" "migration_secret" {
  secret_id = var.migration_secret_id
  role      = "lockbox.payloadViewer"
  members   = ["serviceAccount:${yandex_iam_service_account.migration.id}"]
}

resource "yandex_resourcemanager_folder_iam_member" "migration_logs" {
  folder_id = var.folder_id
  role      = "logging.writer"
  member    = "serviceAccount:${yandex_iam_service_account.migration.id}"
}
