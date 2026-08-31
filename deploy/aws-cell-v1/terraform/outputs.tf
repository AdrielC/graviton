output "cell_name" {
  value = var.cell_name
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "runtime_secret_arn" {
  value = aws_secretsmanager_secret.runtime.arn
}

output "bootstrap_repository_url" {
  value = aws_ecr_repository.bootstrap.repository_url
}

output "bootstrap_task_definition_arn" {
  value = try(aws_ecs_task_definition.bootstrap[0].arn, null)
}

output "private_subnet_ids" {
  value = module.vpc.private_subnets
}

output "task_security_group_id" {
  value = aws_security_group.tasks.id
}

output "database_endpoint" {
  value = aws_db_instance.this.endpoint
}

output "admission_replication_group_id" {
  value = aws_elasticache_replication_group.admission.id
}

output "admission_primary_endpoint" {
  value = aws_elasticache_replication_group.admission.primary_endpoint_address
}

output "block_bucket" {
  value = aws_s3_bucket.blocks.id
}

output "staging_bucket" {
  value = aws_s3_bucket.staging.id
}

output "node_services" {
  value = local.node_names
}
