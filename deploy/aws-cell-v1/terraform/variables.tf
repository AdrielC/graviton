variable "aws_region" {
  description = "AWS region for the complete cell."
  type        = string
  default     = "us-east-1"
}

variable "cell_name" {
  description = "Lowercase deployment-cell identity used in resource and DNS names."
  type        = string
  default     = "graviton-v1"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,30}$", var.cell_name))
    error_message = "cell_name must be 3 through 31 lowercase letters, digits, or hyphens and start with a letter."
  }
}

variable "vpc_cidr" {
  description = "Private address space reserved for this cell."
  type        = string
  default     = "10.42.0.0/16"
}

variable "certificate_arn" {
  description = "ACM certificate used by the public HTTPS listener."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:acm:", var.certificate_arn))
    error_message = "certificate_arn must be an ACM certificate ARN."
  }
}

variable "ingress_cidrs" {
  description = "IPv4 CIDRs allowed to reach the ALB."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "graviton_image" {
  description = "Immutable Graviton OCI image reference. Tags are rejected."
  type        = string

  validation {
    condition     = can(regex("^.+@sha256:[0-9a-f]{64}$", var.graviton_image))
    error_message = "graviton_image must be pinned as repository@sha256:<64 lowercase hex characters>."
  }
}

variable "initial_tenant_id" {
  description = "Canonical lowercase organization UUID admitted into the new isolated cell."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.initial_tenant_id))
    error_message = "initial_tenant_id must be a canonical lowercase UUID."
  }
}

variable "initial_tenant_max_retained_bytes" {
  description = "Retained logical-byte ceiling for the initial isolated tenant."
  type        = number
  default     = 1099511627776

  validation {
    condition     = var.initial_tenant_max_retained_bytes >= 1099511627776 && var.initial_tenant_max_retained_bytes <= 9223372036854775807
    error_message = "initial_tenant_max_retained_bytes must be between 1 TiB and signed 64-bit storage."
  }
}

variable "bootstrap_image" {
  description = "Immutable ECR image for the schema bootstrap task. Leave empty before the image is built."
  type        = string
  default     = ""

  validation {
    condition     = var.bootstrap_image == "" || can(regex("^.+@sha256:[0-9a-f]{64}$", var.bootstrap_image))
    error_message = "bootstrap_image must be empty or pinned as repository@sha256:<64 lowercase hex characters>."
  }
}

variable "bootstrap_complete" {
  description = "Creates the long-running manager and nodes only after secrets and schema bootstrap succeed."
  type        = bool
  default     = false
}

variable "node_names" {
  description = "Stable Shardcake node identities. ALB weighted forwarding supports two through five nodes."
  type        = set(string)
  default     = ["node-0", "node-1", "node-2"]

  validation {
    condition = (
      length(var.node_names) >= 2 &&
      length(var.node_names) <= 5 &&
      alltrue([for name in var.node_names : can(regex("^node-[0-9]+$", name))])
    )
    error_message = "node_names must contain two through five unique node-N identities."
  }
}

variable "task_cpu" {
  description = "Fargate CPU units per Graviton node."
  type        = number
  default     = 2048
}

variable "task_memory" {
  description = "Fargate memory MiB per Graviton node."
  type        = number
  default     = 4096
}

variable "db_instance_class" {
  description = "Multi-AZ PostgreSQL instance class."
  type        = string
  default     = "db.r7g.large"
}

variable "db_engine_version" {
  description = "RDS PostgreSQL major version. AWS selects a current supported minor."
  type        = string
  default     = "16"
}

variable "admission_cache_node_type" {
  description = "ElastiCache Valkey node type for cluster admission leases and policy events."
  type        = string
  default     = "cache.r7g.large"

  validation {
    condition     = can(regex("^cache\\.[a-z0-9]+\\.[a-z0-9]+$", var.admission_cache_node_type))
    error_message = "admission_cache_node_type must be an ElastiCache node type such as cache.r7g.large."
  }
}

variable "deletion_protection" {
  description = "Protect the database and buckets from ordinary destroy operations."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "CloudWatch application log retention."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Additional resource tags."
  type        = map(string)
  default     = {}
}
