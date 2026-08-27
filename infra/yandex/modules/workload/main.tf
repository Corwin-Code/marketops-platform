terraform {
  required_version = ">= 1.14.9, < 2.0.0"
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "= 0.220.0"
    }
  }
}

locals {
  roles = {
    application = { size = var.application_instances, identity = var.application_service_account_id, group = var.application_security_group_id }
    worker      = { size = var.worker_instances, identity = var.worker_service_account_id, group = var.worker_security_group_id }
  }
  environment = {
    SPRING_PROFILES_ACTIVE                        = "production"
    SPRING_DATASOURCE_URL                         = "jdbc:postgresql://c-${var.database_cluster_id}.rw.mdb.yandexcloud.net:6432/marketops?sslmode=verify-full&sslrootcert=/opt/marketops/certs/yandex-root.crt&targetServerType=primary"
    SPRING_FLYWAY_ENABLED                         = "false"
    SPRING_CONFIG_IMPORT                          = "configtree:/run/marketops/config/"
    MARKETOPS_SECRET_MOUNT_DIRECTORY              = "/run/marketops/credentials"
    MARKETOPS_ENVIRONMENT                         = var.environment
    MARKETOPS_OBJECT_STORAGE_ENDPOINT             = "https://storage.yandexcloud.net"
    MARKETOPS_OBJECT_STORAGE_REGION               = "ru-central1"
    MARKETOPS_OBJECT_STORAGE_BUCKET               = var.evidence_bucket_name
    MARKETOPS_OBJECT_STORAGE_CREDENTIAL_REFERENCE = "secret-ref://object-storage/signing-key"
    MARKETOPS_OIDC_ISSUER_URI                     = var.oidc_issuer_uri
    MARKETOPS_OIDC_JWK_SET_URI                    = var.oidc_jwk_set_uri
    MARKETOPS_OIDC_AUDIENCE                       = var.oidc_audience
    # Deploying infrastructure never enables acquisition or marketplace writes.
    MARKETOPS_ACQUISITION_SCHEDULER_ENABLED = "false"
    MARKETOPS_PRICE_WRITE_WORKER_ENABLED    = "false"
  }
}

resource "yandex_compute_instance_group" "runtime" {
  for_each                     = local.roles
  name                         = "${var.environment}-marketops-${each.key}"
  folder_id                    = var.folder_id
  service_account_id           = var.group_manager_service_account_id
  deletion_protection          = var.environment == "production"
  max_checking_health_duration = 900
  labels                       = var.labels
  instance_template {
    platform_id        = "standard-v3"
    service_account_id = each.value.identity
    resources {
      cores  = var.instance_cores
      memory = var.instance_memory_gib
    }
    boot_disk {
      mode = "READ_WRITE"
      initialize_params {
        image_id = var.host_image_id
        size     = 30
        type     = "network-ssd"
      }
    }
    network_interface {
      network_id         = var.network_id
      subnet_ids         = values(var.subnet_ids)
      nat                = false
      security_group_ids = [each.value.group]
    }
    metadata_options {
      gce_http_endpoint    = 1
      gce_http_token       = 1
      aws_v1_http_endpoint = 2
      aws_v1_http_token    = 2
    }
    metadata = {
      user-data = "#cloud-config\n${jsonencode({
        write_files = [
          { path = "/usr/local/lib/marketops-bootstrap.py", permissions = "0700", owner = "root:root", encoding = "b64", content = base64encode(file("${path.module}/../../runtime/bootstrap.py")) },
          { path = "/usr/local/lib/marketops-telemetry.py", permissions = "0755", owner = "root:root", encoding = "b64", content = base64encode(file("${path.module}/../../runtime/telemetry.py")) },
          { path = "/etc/marketops/telemetry.json", permissions = "0644", owner = "root:root", content = jsonencode({ folder_id = var.folder_id, environment = var.environment, role = each.key }) },
          { path = "/etc/systemd/system/marketops-telemetry.service", permissions = "0644", owner = "root:root", content = file("${path.module}/../../runtime/marketops-telemetry.service") },
          { path = "/etc/systemd/system/marketops-telemetry.timer", permissions = "0644", owner = "root:root", content = file("${path.module}/../../runtime/marketops-telemetry.timer") },
          { path = "/etc/marketops/runtime.json", permissions = "0600", owner = "root:root", content = jsonencode({
            role               = each.key, backend_image = var.backend_image, console_image = var.console_image,
            environment        = local.environment, secrets = var.runtime_secrets[each.key],
            migration_evidence = var.migration_evidence
          }) },
          { path = "/etc/systemd/system/marketops.service", permissions = "0644", owner = "root:root", content = file("${path.module}/../../runtime/marketops.service") }
        ]
        runcmd = [["systemctl", "daemon-reload"], ["systemctl", "enable", "--now", "marketops.service"], ["systemctl", "enable", "--now", "marketops-telemetry.timer"]]
      })}"
    }
    labels = merge(var.labels, { role = each.key })
  }
  scale_policy {
    fixed_scale { size = each.value.size }
  }
  allocation_policy { zones = keys(var.subnet_ids) }
  deploy_policy {
    max_unavailable  = 0
    max_expansion    = 1
    max_creating     = 1
    max_deleting     = 1
    startup_duration = 300
  }
  health_check {
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    http_options {
      port = 8080
      path = "/actuator/health/readiness"
    }
  }
  dynamic "application_load_balancer" {
    for_each = each.key == "application" ? [true] : []
    content {
      target_group_name    = "${var.environment}-marketops-application"
      ignore_health_checks = false
    }
  }
}

resource "yandex_alb_backend_group" "runtime" {
  for_each  = { api = 8080, console = 8088 }
  name      = "${var.environment}-marketops-${each.key}"
  folder_id = var.folder_id
  http_backend {
    name             = each.key
    port             = each.value
    weight           = 1
    target_group_ids = [yandex_compute_instance_group.runtime["application"].application_load_balancer[0].target_group_id]
    healthcheck {
      timeout             = "5s"
      interval            = "15s"
      healthcheck_port    = 8080
      healthy_threshold   = 2
      unhealthy_threshold = 3
      http_healthcheck { path = "/actuator/health/readiness" }
    }
  }
  labels = var.labels
}
resource "yandex_alb_http_router" "this" {
  name      = "${var.environment}-marketops"
  folder_id = var.folder_id
  labels    = var.labels
}
resource "yandex_alb_virtual_host" "this" {
  name           = "marketops"
  http_router_id = yandex_alb_http_router.this.id
  authority      = [var.public_hostname]
  modify_response_headers {
    name    = "Strict-Transport-Security"
    replace = "max-age=31536000"
  }
  # Explicit routes ensure maintenance, health and arbitrary APIs never leave the private network.
  route {
    name = "console-api"
    http_route {
      http_match {
        path { prefix = "/api/v1/console/" }
      }
      http_route_action {
        backend_group_id = yandex_alb_backend_group.runtime["api"].id
        timeout          = "35s"
      }
    }
  }
  route {
    name = "public-metadata"
    http_route {
      http_match {
        http_method = ["GET"]
        path { exact = "/api/v1/meta/status" }
      }
      http_route_action {
        backend_group_id = yandex_alb_backend_group.runtime["api"].id
        timeout          = "5s"
      }
    }
  }
  dynamic "route" {
    for_each = toset(["/api/", "/actuator/"])
    content {
      name = route.value == "/api/" ? "deny-other-apis" : "deny-actuator"
      http_route {
        http_match {
          path { prefix = route.value }
        }
        direct_response_action { status = 404 }
      }
    }
  }
  route {
    name = "console"
    http_route {
      http_match {
        path { prefix = "/" }
      }
      http_route_action {
        backend_group_id = yandex_alb_backend_group.runtime["console"].id
        timeout          = "5s"
      }
    }
  }
}
resource "yandex_alb_load_balancer" "this" {
  name               = "${var.environment}-marketops"
  folder_id          = var.folder_id
  network_id         = var.network_id
  security_group_ids = [var.load_balancer_security_group_id]
  allocation_policy {
    dynamic "location" {
      for_each = var.subnet_ids
      content {
        zone_id   = location.key
        subnet_id = location.value
      }
    }
  }
  listener {
    name = "https"
    endpoint {
      ports = [443]
      address {
        external_ipv4_address {}
      }
    }
    tls {
      default_handler {
        certificate_ids = [var.certificate_id]
        http_handler { http_router_id = yandex_alb_http_router.this.id }
      }
    }
  }
  log_options {
    log_group_id = var.log_group_id
    disable      = false
  }
  labels = var.labels
}
resource "yandex_dns_recordset" "console" {
  zone_id = var.dns_zone_id
  name    = "${var.public_hostname}."
  type    = "A"
  ttl     = 300
  data    = [yandex_alb_load_balancer.this.listener[0].endpoint[0].address[0].external_ipv4_address[0].address]
}
