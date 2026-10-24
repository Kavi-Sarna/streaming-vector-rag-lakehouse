variable "name" {
  type        = string
  description = "Glue Schema Registry name. Schemas in this registry are Protobuf."
}

variable "schemas" {
  type        = map(string)
  description = "Protobuf schema definitions keyed by schema name. The value is the .proto file contents."
}
