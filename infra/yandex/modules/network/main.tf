# No workload or database has a public address. Only ALB receives public HTTPS.
terraform {
  required_version = ">= 1.14.9, < 2.0.0"
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "= 0.220.0"
    }
  }
}

resource "yandex_vpc_network" "this" {
  name      = "${var.environment}-marketops"
  folder_id = var.folder_id
  labels    = var.labels
}
resource "yandex_vpc_gateway" "egress" {
  name      = "${var.environment}-marketops-egress"
  folder_id = var.folder_id
  shared_egress_gateway {}
  labels = var.labels
}
resource "yandex_vpc_route_table" "private" {
  name       = "${var.environment}-marketops-private"
  folder_id  = var.folder_id
  network_id = yandex_vpc_network.this.id
  static_route {
    destination_prefix = "0.0.0.0/0"
    gateway_id         = yandex_vpc_gateway.egress.id
  }
  labels = var.labels
}
resource "yandex_vpc_subnet" "private" {
  for_each       = var.availability_zones
  name           = "${var.environment}-marketops-private-${each.key}"
  folder_id      = var.folder_id
  network_id     = yandex_vpc_network.this.id
  zone           = each.key
  v4_cidr_blocks = [each.value]
  route_table_id = yandex_vpc_route_table.private.id
  labels         = var.labels
}

# Separate rule resources allow exact reciprocal SG references without cycles.
resource "yandex_vpc_security_group" "groups" {
  for_each   = toset(["application", "worker", "migration", "load-balancer", "database"])
  name       = "${var.environment}-marketops-${each.key}"
  folder_id  = var.folder_id
  network_id = yandex_vpc_network.this.id
  labels     = var.labels
}
resource "yandex_vpc_security_group_rule" "public_https" {
  security_group_binding = yandex_vpc_security_group.groups["load-balancer"].id
  direction              = "ingress"
  protocol               = "TCP"
  port                   = 443
  v4_cidr_blocks         = ["0.0.0.0/0"]
}
resource "yandex_vpc_security_group_rule" "alb_health" {
  security_group_binding = yandex_vpc_security_group.groups["load-balancer"].id
  direction              = "ingress"
  protocol               = "TCP"
  port                   = 30080
  v4_cidr_blocks         = var.health_check_cidrs
}
resource "yandex_vpc_security_group_rule" "alb_to_application" {
  for_each               = toset(["8080", "8088"])
  security_group_binding = yandex_vpc_security_group.groups["load-balancer"].id
  direction              = "egress"
  protocol               = "TCP"
  port                   = tonumber(each.value)
  security_group_id      = yandex_vpc_security_group.groups["application"].id
}
resource "yandex_vpc_security_group_rule" "application_from_alb" {
  for_each               = toset(["8080", "8088"])
  security_group_binding = yandex_vpc_security_group.groups["application"].id
  direction              = "ingress"
  protocol               = "TCP"
  port                   = tonumber(each.value)
  security_group_id      = yandex_vpc_security_group.groups["load-balancer"].id
}
resource "yandex_vpc_security_group_rule" "group_health" {
  for_each               = toset(["application", "worker"])
  security_group_binding = yandex_vpc_security_group.groups[each.value].id
  direction              = "ingress"
  protocol               = "TCP"
  port                   = 8080
  v4_cidr_blocks         = var.health_check_cidrs
}
resource "yandex_vpc_security_group_rule" "database_ingress" {
  for_each               = toset(["application", "worker", "migration"])
  security_group_binding = yandex_vpc_security_group.groups["database"].id
  direction              = "ingress"
  protocol               = "TCP"
  port                   = 6432
  security_group_id      = yandex_vpc_security_group.groups[each.value].id
}
resource "yandex_vpc_security_group_rule" "database_egress" {
  for_each               = toset(["application", "worker", "migration"])
  security_group_binding = yandex_vpc_security_group.groups[each.value].id
  direction              = "egress"
  protocol               = "TCP"
  port                   = 6432
  security_group_id      = yandex_vpc_security_group.groups["database"].id
}
resource "yandex_vpc_security_group_rule" "database_cluster" {
  for_each               = toset(["ingress", "egress"])
  security_group_binding = yandex_vpc_security_group.groups["database"].id
  direction              = each.value
  protocol               = "ANY"
  predefined_target      = "self_security_group"
}
resource "yandex_vpc_security_group_rule" "https_egress" {
  for_each               = toset(["application", "worker", "migration"])
  security_group_binding = yandex_vpc_security_group.groups[each.value].id
  direction              = "egress"
  protocol               = "TCP"
  port                   = 443
  v4_cidr_blocks         = ["0.0.0.0/0"]
}
locals {
  dns_rules = { for pair in setproduct(["application", "worker", "migration"], ["UDP", "TCP"]) : "${pair[0]}-${pair[1]}" => pair }
}
resource "yandex_vpc_security_group_rule" "dns" {
  for_each               = local.dns_rules
  security_group_binding = yandex_vpc_security_group.groups[each.value[0]].id
  direction              = "egress"
  protocol               = each.value[1]
  port                   = 53
  v4_cidr_blocks         = values(var.availability_zones)
}
