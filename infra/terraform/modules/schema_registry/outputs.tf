output "registry_name" {
  description = "Glue Schema Registry name."
  value       = aws_glue_registry.this.registry_name
}

output "schema_arns" {
  description = "Schema ARNs keyed by schema name."
  value       = { for name, schema in aws_glue_schema.this : name => schema.arn }
}
