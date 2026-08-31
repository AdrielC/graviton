locals {
  availability_zones = slice(data.aws_availability_zones.available.names, 0, 3)
  node_names         = sort(tolist(var.node_names))
  namespace          = "internal.${var.cell_name}"
  runtime_secret     = "${var.cell_name}/runtime"
  tags = merge(
    {
      Application = "graviton"
      Cell        = var.cell_name
      ManagedBy   = "terraform"
    },
    var.tags,
  )
}

check "bootstrap_gate" {
  assert {
    condition     = !var.bootstrap_complete || var.bootstrap_image != ""
    error_message = "bootstrap_complete requires a pinned bootstrap_image."
  }
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.6.1"

  name = var.cell_name
  cidr = var.vpc_cidr
  azs  = local.availability_zones

  public_subnets  = [for index, _ in local.availability_zones : cidrsubnet(var.vpc_cidr, 4, index)]
  private_subnets = [for index, _ in local.availability_zones : cidrsubnet(var.vpc_cidr, 4, index + 3)]

  enable_nat_gateway     = true
  one_nat_gateway_per_az = true
  single_nat_gateway     = false
  enable_dns_hostnames   = true
  enable_dns_support     = true

  public_subnet_tags = {
    "kubernetes.io/role/elb" = "1"
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = "1"
  }
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = module.vpc.private_route_table_ids
}

resource "aws_kms_key" "data" {
  description             = "${var.cell_name} Graviton data encryption"
  deletion_window_in_days = 30
  enable_key_rotation     = true
}

resource "aws_kms_alias" "data" {
  name          = "alias/${var.cell_name}-data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_s3_bucket" "blocks" {
  bucket_prefix = "${var.cell_name}-blocks-"
  force_destroy = !var.deletion_protection

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket" "staging" {
  bucket_prefix = "${var.cell_name}-staging-"
  force_destroy = !var.deletion_protection
}

resource "aws_s3_bucket_public_access_block" "data" {
  for_each = {
    blocks  = aws_s3_bucket.blocks.id
    staging = aws_s3_bucket.staging.id
  }

  bucket                  = each.value
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  for_each = {
    blocks  = aws_s3_bucket.blocks.id
    staging = aws_s3_bucket.staging.id
  }

  bucket = each.value
  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.data.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "blocks" {
  bucket = aws_s3_bucket.blocks.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "staging" {
  bucket = aws_s3_bucket.staging.id

  rule {
    id     = "expire-abandoned-upload-staging"
    status = "Enabled"

    filter {}

    expiration {
      days = 2
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "bucket_tls" {
  for_each = {
    blocks  = aws_s3_bucket.blocks.arn
    staging = aws_s3_bucket.staging.arn
  }

  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      each.value,
      "${each.value}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "data" {
  for_each = {
    blocks = {
      id     = aws_s3_bucket.blocks.id
      policy = data.aws_iam_policy_document.bucket_tls["blocks"].json
    }
    staging = {
      id     = aws_s3_bucket.staging.id
      policy = data.aws_iam_policy_document.bucket_tls["staging"].json
    }
  }

  bucket = each.value.id
  policy = each.value.policy
}

resource "aws_security_group" "database" {
  name_prefix = "${var.cell_name}-db-"
  description = "PostgreSQL ingress from Graviton tasks"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_security_group" "tasks" {
  name_prefix = "${var.cell_name}-tasks-"
  description = "Graviton manager and node tasks"
  vpc_id      = module.vpc.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_tasks" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.tasks.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "task_mesh" {
  for_each = toset(["8080", "54321", "54322"])

  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.tasks.id
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  ip_protocol                  = "tcp"
}

resource "aws_db_subnet_group" "this" {
  name       = var.cell_name
  subnet_ids = module.vpc.private_subnets
}

resource "aws_db_instance" "this" {
  identifier = var.cell_name

  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class
  db_name        = "graviton"
  username       = "graviton_admin"
  port           = 5432

  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.data.arn

  allocated_storage     = 100
  max_allocated_storage = 1000
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.data.arn
  multi_az              = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.database.id]
  publicly_accessible    = false

  backup_retention_period         = 14
  backup_window                   = "04:00-05:00"
  maintenance_window              = "sun:05:00-sun:06:00"
  auto_minor_version_upgrade      = true
  deletion_protection             = var.deletion_protection
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.cell_name}-final"
  copy_tags_to_snapshot           = true
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_secretsmanager_secret" "runtime" {
  name                    = local.runtime_secret
  description             = "Runtime-only Graviton credentials populated by operator.sh"
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30
}

resource "aws_ecr_repository" "bootstrap" {
  name                 = "${var.cell_name}/bootstrap"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.data.arn
  }
}

resource "aws_ecs_cluster" "this" {
  name = var.cell_name

  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name = local.namespace
  vpc  = module.vpc.vpc_id
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/graviton/${var.cell_name}"
  retention_in_days = var.log_retention_days
}
