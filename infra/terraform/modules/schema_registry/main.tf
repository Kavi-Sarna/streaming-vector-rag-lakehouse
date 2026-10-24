resource "aws_glue_registry" "this" {
  registry_name = var.name
  description   = "Protobuf schemas for the streaming RAG demo."
}

resource "aws_glue_schema" "this" {
  for_each = var.schemas

  schema_name       = each.key
  registry_arn      = aws_glue_registry.this.arn
  data_format       = "PROTOBUF"
  compatibility     = "BACKWARD"
  schema_definition = each.value
}
