# Synthetic plan only. No credentials, provider API calls or apply operations.
mock_provider "yandex" {
  override_during = plan
}
variables {
  cloud_id                          = "cccccccccccccccccccc"
  folder_id                         = "ffffffffffffffffffff"
  availability_zones                = { "ru-central1-a" : "10.90.1.0/24", "ru-central1-b" : "10.90.2.0/24", "ru-central1-d" : "10.90.3.0/24" }
  database_resource_preset_id       = "s3-c2-m8"
  database_disk_size_gib            = 186
  database_backup_retention_days    = 7
  evidence_bucket_name              = "marketops-offline-plan-evidence"
  evidence_retention_days           = 365
  evidence_kms_key_id               = "kkkkkkkkkkkkkkkkkkkk"
  evidence_certificate_id           = "tttttttttttttttttttt"
  migration_secret_id               = "mmmmmmmmmmmmmmmmmmmm"
  notification_channel_id           = "nnnnnnnnnnnnnnnnnnnn"
  migration_password_version        = 1
  application_password_version      = 1
  infrastructure_service_account_id = "iiiiiiiiiiiiiiiiiiii"
  container_registry_id             = "rrrrrrrrrrrrrrrrrrrr"
  host_image_id                     = "hhhhhhhhhhhhhhhhhhhh"
  oidc_issuer_uri                   = "https://identity.example.invalid"
  oidc_jwk_set_uri                  = "https://identity.example.invalid/jwks"
  oidc_audience                     = "marketops-offline-plan"
  public_hostname                   = "console.example.invalid"
  console_certificate_id            = "tttttttttttttttttttt"
  dns_zone_id                       = "zzzzzzzzzzzzzzzzzzzz"
  backend_image                     = "cr.yandex/rrrrrrrrrrrrrrrrrrrr/marketops-api@sha256:1111111111111111111111111111111111111111111111111111111111111111"
  console_image                     = "cr.yandex/rrrrrrrrrrrrrrrrrrrr/marketops-console@sha256:2222222222222222222222222222222222222222222222222222222222222222"
  runtime_secrets                   = { "application" : { "config/spring.datasource.password" : { "secret_id" : "ssssssssssssssssssss", "version_id" : "vvvvvvvvvvvvvvvvvvvv", "key" : "database" }, "config/marketops.object-storage.access-key-id" : { "secret_id" : "ssssssssssssssssssss", "version_id" : "vvvvvvvvvvvvvvvvvvvv", "key" : "access-id" }, "credentials/object-storage/signing-key" : { "secret_id" : "ssssssssssssssssssss", "version_id" : "vvvvvvvvvvvvvvvvvvvv", "key" : "signing-key" } }, "worker" : { "config/spring.datasource.password" : { "secret_id" : "ssssssssssssssssssss", "version_id" : "vvvvvvvvvvvvvvvvvvvv", "key" : "database" }, "config/marketops.object-storage.access-key-id" : { "secret_id" : "ssssssssssssssssssss", "version_id" : "vvvvvvvvvvvvvvvvvvvv", "key" : "access-id" }, "credentials/object-storage/signing-key" : { "secret_id" : "ssssssssssssssssssss", "version_id" : "vvvvvvvvvvvvvvvvvvvv", "key" : "signing-key" } } }
  migration_role_password           = format("synthetic-only-%s-never-real", "migration")
  application_role_password         = format("synthetic-only-%s-never-real", "application")
}
run "foundation_plan" {
  command = plan
  assert {
    condition     = output.https_origin == null && length(output.instance_groups) == 0
    error_message = "Foundation must not create a runtime or public application ingress before migration."
  }
}

run "unproven_runtime_rejected" {
  command = plan
  variables { runtime_enabled = true }
  expect_failures = [var.migration_evidence]
}

run "full_environment_plan" {
  command = plan
  variables {
    runtime_enabled = true
    migration_evidence = {
      document = file("tests/migration-result.fixture.json")
      sha256   = filesha256("tests/migration-result.fixture.json")
    }
  }
  assert {
    condition     = output.https_origin == "https://console.example.invalid"
    error_message = "The console and authenticated API must share the reviewed HTTPS origin."
  }
  assert {
    condition     = length(toset(values(output.workload_identities))) == 3
    error_message = "Application, worker and migration identities must remain distinct."
  }
  assert {
    condition     = output.alert_configuration_required.verified == false && length(output.alerts) == 6
    error_message = "An offline plan cannot verify six real notification controls."
  }
}

override_resource {
  target          = module.workload_identity.yandex_iam_service_account.roles["application"]
  values          = { id = "aaaaaaaaaaaaaaaaaaaa" }
  override_during = plan
}
override_resource {
  target          = module.workload_identity.yandex_iam_service_account.roles["acquisition"]
  values          = { id = "wwwwwwwwwwwwwwwwwwww" }
  override_during = plan
}
override_resource {
  target          = module.workload_identity.yandex_iam_service_account.roles["migration"]
  values          = { id = "mmmmmmmmmmmmmmmmmmmm" }
  override_during = plan
}
override_resource {
  target          = module.workload_identity.yandex_iam_service_account.roles["group-manager"]
  values          = { id = "gggggggggggggggggggg" }
  override_during = plan
}
override_resource {
  target          = module.workload_identity.yandex_iam_service_account.roles["audit"]
  values          = { id = "uuuuuuuuuuuuuuuuuuuu" }
  override_during = plan
}
override_resource {
  target          = module.database.yandex_mdb_postgresql_cluster.this
  values          = { id = "dddddddddddddddddddd" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_network.this
  values          = { id = "nnnnnnnnnnnnnnnnnnng" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_route_table.private
  values          = { id = "rrrrrrrrrrrrrrrrrrrg" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_security_group.groups["application"]
  values          = { id = "aaaaaaaaaaaaaaaaaaag" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_security_group.groups["worker"]
  values          = { id = "wwwwwwwwwwwwwwwwwwwg" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_security_group.groups["migration"]
  values          = { id = "mmmmmmmmmmmmmmmmmmmg" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_security_group.groups["load-balancer"]
  values          = { id = "lllllllllllllllllllg" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_security_group.groups["database"]
  values          = { id = "dddddddddddddddddddg" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_subnet.private["ru-central1-a"]
  values          = { id = "xxxxxxxxxxxxxxxxxxxg" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_subnet.private["ru-central1-b"]
  values          = { id = "yyyyyyyyyyyyyyyyyyyg" }
  override_during = plan
}

override_resource {
  target          = module.network.yandex_vpc_subnet.private["ru-central1-d"]
  values          = { id = "zzzzzzzzzzzzzzzzzzzg" }
  override_during = plan
}
