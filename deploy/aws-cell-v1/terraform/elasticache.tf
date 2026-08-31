resource "aws_elasticache_subnet_group" "admission" {
  name       = "${var.cell_name}-admission"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_security_group" "admission" {
  name_prefix = "${var.cell_name}-admission-"
  description = "TLS Valkey admission coordinator ingress from Graviton tasks"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "admission_from_tasks" {
  security_group_id            = aws_security_group.admission.id
  referenced_security_group_id = aws_security_group.tasks.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_cloudwatch_log_group" "admission_slow" {
  name              = "/graviton/${var.cell_name}/admission/slow"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "admission_engine" {
  name              = "/graviton/${var.cell_name}/admission/engine"
  retention_in_days = var.log_retention_days
}

resource "aws_elasticache_replication_group" "admission" {
  replication_group_id = "${substr(var.cell_name, 0, 29)}-adm"
  description          = "${var.cell_name} cluster admission and fairness leases"

  engine                     = "valkey"
  node_type                  = var.admission_cache_node_type
  port                       = 6379
  num_cache_clusters         = 3
  automatic_failover_enabled = true
  multi_az_enabled           = true
  auto_minor_version_upgrade = true
  apply_immediately          = false

  subnet_group_name  = aws_elasticache_subnet_group.admission.name
  security_group_ids = [aws_security_group.admission.id]

  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.data.arn

  snapshot_retention_limit = 7
  snapshot_window          = "03:00-04:00"
  maintenance_window       = "sun:06:00-sun:07:00"

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.admission_slow.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.admission_engine.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "engine-log"
  }

  lifecycle {
    prevent_destroy = true
  }
}

# Terraform owns the replication group, but operator.sh installs its AUTH token
# directly after the password has been written to Secrets Manager. The token is
# intentionally absent from this resource so it never enters Terraform state.
data "aws_elasticache_replication_group" "admission" {
  replication_group_id = aws_elasticache_replication_group.admission.id
  depends_on           = [aws_elasticache_replication_group.admission]
}

check "admission_auth_gate" {
  assert {
    condition     = !var.bootstrap_complete || data.aws_elasticache_replication_group.admission.auth_token_enabled
    error_message = "bootstrap_complete requires operator.sh to install and verify the Valkey AUTH token first."
  }
}
