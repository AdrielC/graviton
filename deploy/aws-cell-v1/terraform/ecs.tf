locals {
  database_url = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${aws_db_instance.this.db_name}"

  common_environment = [
    { name = "AWS_REGION", value = var.aws_region },
    { name = "GRAVITON_DEPLOYMENT_PROFILE", value = "production-cluster" },
    { name = "GRAVITON_BLOB_BACKEND", value = "s3" },
    { name = "GRAVITON_S3_REGION", value = var.aws_region },
    { name = "GRAVITON_S3_BLOCK_BUCKET", value = aws_s3_bucket.blocks.id },
    { name = "GRAVITON_S3_BLOCK_PREFIX", value = "cas/blocks" },
    { name = "GRAVITON_S3_TMP_BUCKET", value = aws_s3_bucket.staging.id },
    { name = "GRAVITON_MAINTENANCE_NAMESPACE", value = var.cell_name },
    { name = "GRAVITON_MANIFEST_INTEGRITY_REQUIRED", value = "true" },
    { name = "GRAVITON_MANIFEST_INTEGRITY_KEY_ID", value = "aws-cell-v1" },
    { name = "GRAVITON_DOWNLOAD_WINDOW_REFS", value = "64" },
    { name = "GRAVITON_DOWNLOAD_MAX_IN_FLIGHT", value = "2" },
    { name = "GRAVITON_BLOCK_WRITE_PARALLELISM", value = "4" },
    { name = "GRAVITON_TRANSFER_MEMORY_MAXIMUM_BUFFERED_BYTES", value = "1073741824" },
    { name = "GRAVITON_TRANSFER_ADMISSION_MAXIMUM_TENANT_BUFFERED_BYTES", value = "268435456" },
    { name = "GRAVITON_TRANSFER_ADMISSION_MAXIMUM_CONCURRENT_TENANT_TRANSFERS", value = "16" },
    { name = "GRAVITON_TRANSFER_ADMISSION_MAXIMUM_CONCURRENT_BACKEND_TRANSFERS", value = "64" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_ENABLED", value = "true" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_CELL_ID", value = var.cell_name },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_HOST", value = aws_elasticache_replication_group.admission.primary_endpoint_address },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_PORT", value = "6379" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_TLS", value = "true" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_VERIFY_CERTIFICATE", value = "true" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_MAXIMUM_SERVICE_BUFFERED_BYTES", value = "2147483648" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_MAXIMUM_CONCURRENT_SERVICE_TRANSFERS", value = "192" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_MAXIMUM_TENANT_BUFFERED_BYTES", value = "536870912" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_MAXIMUM_CONCURRENT_TENANT_TRANSFERS", value = "32" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_MAXIMUM_CONCURRENT_BACKEND_TRANSFERS", value = "144" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_LEASE_TTL", value = "30s" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_RENEWAL_INTERVAL", value = "10s" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_ACQUISITION_TIMEOUT", value = "10s" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_RETRY_INTERVAL", value = "50ms" },
    { name = "PG_JDBC_URL", value = local.database_url },
    { name = "PG_USERNAME", value = "graviton_app" },
    { name = "PG_POOL_MAX_SIZE", value = "20" },
    { name = "PG_POOL_MIN_IDLE", value = "4" },
    { name = "GRAVITON_MULTI_TENANT_ENABLED", value = "true" },
    { name = "GRAVITON_MULTI_TENANT_CELL_ID", value = var.cell_name },
    { name = "GRAVITON_TENANT_STORAGE_ALLOW_SHARED_DEDUPLICATION", value = "false" },
    { name = "GRAVITON_SECURITY_ENABLED", value = "true" },
    { name = "GRAVITON_SECURITY_REQUIRE_TLS", value = "true" },
    { name = "GRAVITON_SECURITY_TRUST_PROXY_HEADERS", value = "true" },
    { name = "GRAVITON_SECURITY_AUDIT_BACKEND", value = "jdbc" },
    { name = "GRAVITON_SECURITY_AUTHORIZATION_BACKEND", value = "token" },
    { name = "GRAVITON_CONSOLE_ENABLED", value = "false" },
    { name = "GRAVITON_SHARDCAKE_ENABLED", value = "true" },
    { name = "GRAVITON_SHARDCAKE_MANAGER_URI", value = "http://manager.${local.namespace}:8080/api/graphql" },
    { name = "GRAVITON_SHARDCAKE_NUMBER_OF_SHARDS", value = "4096" },
    { name = "GRAVITON_SHARDCAKE_SERVER_VERSION", value = "aws-cell-v1" },
    { name = "GRAVITON_SHARDCAKE_POSTGRES_JDBC_URL", value = local.database_url },
    { name = "GRAVITON_SHARDCAKE_POSTGRES_USERNAME", value = "graviton_app" },
    { name = "GRAVITON_SHARDCAKE_POSTGRES_MAXIMUM_POOL_SIZE", value = "8" },
    { name = "GRAVITON_SHARDCAKE_POSTGRES_MINIMUM_IDLE", value = "2" },
  ]

  runtime_secrets = [
    { name = "PG_PASSWORD", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:PG_PASSWORD::" },
    { name = "GRAVITON_SHARDCAKE_POSTGRES_PASSWORD", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:PG_PASSWORD::" },
    { name = "GRAVITON_SHARDCAKE_INTERNAL_TOKEN", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:SHARDCAKE_TOKEN::" },
    { name = "GRAVITON_MANIFEST_INTEGRITY_HMAC_KEY_BASE64", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:MANIFEST_HMAC_KEY_BASE64::" },
    { name = "GRAVITON_SECURITY_OIDC_ISSUER", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:OIDC_ISSUER::" },
    { name = "GRAVITON_SECURITY_OIDC_AUDIENCE", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:OIDC_AUDIENCE::" },
    { name = "GRAVITON_SECURITY_OIDC_JWKS_URI", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:OIDC_JWKS_URI::" },
    { name = "GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:CORS_ALLOWED_ORIGINS::" },
    { name = "GRAVITON_DISTRIBUTED_ADMISSION_REDIS_PASSWORD", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:REDIS_PASSWORD::" },
  ]

  log_configuration = {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.this.name
      awslogs-region        = var.aws_region
      awslogs-stream-prefix = "graviton"
    }
  }
}

resource "aws_service_discovery_service" "manager" {
  name = "manager"

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }

  health_check_custom_config {}
}

resource "aws_service_discovery_service" "node" {
  for_each = var.node_names
  name     = each.key

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }

  health_check_custom_config {}
}

resource "aws_ecs_task_definition" "manager" {
  count = var.bootstrap_complete ? 1 : 0

  family                   = "${var.cell_name}-manager"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name       = "manager"
      image      = var.graviton_image
      essential  = true
      entryPoint = ["java"]
      command = [
        "-XX:MaxRAMPercentage=75",
        "-XX:+ExitOnOutOfMemoryError",
        "-cp",
        "/app/graviton.jar",
        "graviton.integration.shardcake.ShardcakeManagerMain",
      ]
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]
      environment = [
        { name = "GRAVITON_SHARDCAKE_MANAGER_API_PORT", value = "8080" },
        { name = "GRAVITON_SHARDCAKE_POSTGRES_JDBC_URL", value = local.database_url },
        { name = "GRAVITON_SHARDCAKE_POSTGRES_USERNAME", value = "graviton_app" },
        { name = "GRAVITON_SHARDCAKE_POSTGRES_MAXIMUM_POOL_SIZE", value = "8" },
        { name = "GRAVITON_SHARDCAKE_POSTGRES_MINIMUM_IDLE", value = "2" },
      ]
      secrets = [
        { name = "GRAVITON_SHARDCAKE_POSTGRES_PASSWORD", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:PG_PASSWORD::" },
        { name = "GRAVITON_SHARDCAKE_INTERNAL_TOKEN", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:SHARDCAKE_TOKEN::" },
      ]
      logConfiguration       = local.log_configuration
      readonlyRootFilesystem = true
      mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
      linuxParameters = {
        initProcessEnabled = true
        capabilities       = { drop = ["ALL"] }
      }
    }
  ])
}

resource "aws_ecs_task_definition" "node" {
  for_each = var.bootstrap_complete ? var.node_names : toset([])

  family                   = "${var.cell_name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.node.arn

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name      = "graviton"
      image     = var.graviton_image
      essential = true
      portMappings = [
        { containerPort = 8081, protocol = "tcp" },
        { containerPort = 9090, protocol = "tcp" },
        { containerPort = 54321, protocol = "tcp" },
        { containerPort = 54322, protocol = "tcp" },
      ]
      environment = concat(local.common_environment, [
        { name = "GRAVITON_HTTP_PORT", value = "8081" },
        { name = "GRAVITON_GRPC_PORT", value = "9090" },
        { name = "GRAVITON_SHARDCAKE_HOST", value = "${each.key}.${local.namespace}" },
        { name = "GRAVITON_SHARDCAKE_CONTROL_PORT", value = "54321" },
        { name = "GRAVITON_SHARDCAKE_UPLOAD_PORT", value = "54322" },
      ])
      secrets                = local.runtime_secrets
      logConfiguration       = local.log_configuration
      readonlyRootFilesystem = true
      mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
      linuxParameters = {
        initProcessEnabled = true
        capabilities       = { drop = ["ALL"] }
      }
      healthCheck = {
        command = [
          "CMD",
          "java",
          "-cp",
          "/app/graviton.jar",
          "graviton.server.HealthProbeMain",
          "http://127.0.0.1:8081/api/health/ready",
        ]
        interval    = 30
        timeout     = 8
        retries     = 5
        startPeriod = 60
      }
      stopTimeout = 120
    }
  ])
}

resource "aws_ecs_service" "manager" {
  count = var.bootstrap_complete ? 1 : 0

  name            = "manager"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.manager[0].arn
  desired_count   = 1
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  enable_execute_command             = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = module.vpc.private_subnets
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.manager.arn
  }
}

resource "aws_ecs_service" "node" {
  for_each = var.bootstrap_complete ? var.node_names : toset([])

  name            = each.key
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.node[each.key].arn
  desired_count   = 1
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  health_check_grace_period_seconds  = 120
  enable_execute_command             = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = module.vpc.private_subnets
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.node[each.key].arn
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.node[each.key].arn
    container_name   = "graviton"
    container_port   = 8081
  }

  depends_on = [aws_lb_listener.https, aws_ecs_service.manager]
}

resource "aws_ecs_task_definition" "bootstrap" {
  count = var.bootstrap_image == "" ? 0 : 1

  family                   = "${var.cell_name}-bootstrap"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn

  container_definitions = jsonencode([
    {
      name      = "bootstrap"
      image     = var.bootstrap_image
      essential = true
      environment = [
        { name = "PG_HOST", value = aws_db_instance.this.address },
        { name = "PG_PORT", value = tostring(aws_db_instance.this.port) },
        { name = "PG_DATABASE", value = aws_db_instance.this.db_name },
        { name = "PG_ADMIN_USERNAME", value = aws_db_instance.this.username },
        { name = "GRAVITON_INITIAL_TENANT_ID", value = var.initial_tenant_id },
        { name = "GRAVITON_CELL_ID", value = var.cell_name },
        { name = "GRAVITON_INITIAL_TENANT_MAX_RETAINED_BYTES", value = tostring(var.initial_tenant_max_retained_bytes) },
      ]
      secrets = [
        { name = "PG_ADMIN_PASSWORD", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::" },
        { name = "GRAVITON_POSTGRES_PASSWORD", valueFrom = "${aws_secretsmanager_secret.runtime.arn}:PG_PASSWORD::" },
      ]
      logConfiguration       = local.log_configuration
      readonlyRootFilesystem = false
      linuxParameters = {
        initProcessEnabled = true
        capabilities       = { drop = ["ALL"] }
      }
    }
  ])
}
