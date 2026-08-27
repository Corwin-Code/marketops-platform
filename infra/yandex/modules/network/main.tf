# The network MarketOps runs inside.
#
# Nothing in this topology is reachable from the internet except the load
# balancer in front of the console and the API. The database, the object store
# and the workers sit on subnets that have no public address at all, so a
# misconfigured security group cannot expose them: there is no route to expose.
#
# Three subnets, one per availability zone, because the managed database places
# its hosts across zones and a subnet missing from one zone silently reduces the
# cluster to the zones that have one.

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "~> 0.140"
    }
  }
}

resource "yandex_vpc_network" "this" {
  name        = "${var.environment}-marketops"
  description = "MarketOps ${var.environment} network"
  folder_id   = var.folder_id

  labels = var.labels
}

resource "yandex_vpc_subnet" "private" {
  for_each = var.availability_zones

  name           = "${var.environment}-marketops-private-${each.key}"
  description    = "Private subnet for MarketOps ${var.environment} in ${each.key}"
  folder_id      = var.folder_id
  network_id     = yandex_vpc_network.this.id
  zone           = each.key
  v4_cidr_blocks = [each.value]

  labels = var.labels
}

# Outbound access for the workers, which have to reach marketplace APIs and the
# identity provider. A NAT gateway rather than public addresses on the hosts:
# the workers can start a conversation, and nothing outside can.
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

# The application's own group. Ingress is only from the load balancer, and the
# rule names the balancer's group rather than a CIDR, so moving the balancer
# does not silently open the application to whatever else lands on that range.
resource "yandex_vpc_security_group" "application" {
  name        = "${var.environment}-marketops-application"
  description = "MarketOps ${var.environment} application hosts"
  folder_id   = var.folder_id
  network_id  = yandex_vpc_network.this.id

  ingress {
    protocol          = "TCP"
    description       = "Console and API traffic from the load balancer only"
    port              = var.application_port
    security_group_id = yandex_vpc_security_group.load_balancer.id
  }

  ingress {
    protocol       = "TCP"
    description    = "Health checks from the load balancer's own ranges"
    port           = var.application_port
    v4_cidr_blocks = var.health_check_cidrs
  }

  egress {
    protocol       = "ANY"
    description    = "Outbound to marketplaces, the identity provider and managed services"
    from_port      = 0
    to_port        = 65535
    v4_cidr_blocks = ["0.0.0.0/0"]
  }

  labels = var.labels
}

resource "yandex_vpc_security_group" "load_balancer" {
  name        = "${var.environment}-marketops-load-balancer"
  description = "MarketOps ${var.environment} public entry point"
  folder_id   = var.folder_id
  network_id  = yandex_vpc_network.this.id

  ingress {
    protocol       = "TCP"
    description    = "HTTPS from the internet"
    port           = 443
    v4_cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    protocol          = "TCP"
    description       = "To the application hosts only"
    port              = var.application_port
    predefined_target = "self_security_group"
  }

  labels = var.labels
}

# The database's group accepts connections from the application group and from
# nowhere else. There is deliberately no rule admitting an operator's laptop: a
# person who needs the database reaches it through a bastion session that is
# recorded, not through a hole in this group.
resource "yandex_vpc_security_group" "database" {
  name        = "${var.environment}-marketops-database"
  description = "MarketOps ${var.environment} managed PostgreSQL"
  folder_id   = var.folder_id
  network_id  = yandex_vpc_network.this.id

  ingress {
    protocol          = "TCP"
    description       = "PostgreSQL from the application hosts only"
    port              = 6432
    security_group_id = yandex_vpc_security_group.application.id
  }

  labels = var.labels
}
