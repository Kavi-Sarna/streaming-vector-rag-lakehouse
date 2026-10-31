package com.sarna.rag.flink;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.flink.CatalogLoader;
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.types.Types;

/** Hadoop catalog on the local S3 API. The Glue catalog is a later change. */
final class IcebergTables {
  static final String NAMESPACE = "rag";
  static final String TABLE = "document_embeddings";

  private IcebergTables() {}

  static Schema schema() {
    return new Schema(
        List.of(
            Types.NestedField.required(1, "tenant_id", Types.StringType.get()),
            Types.NestedField.required(2, "document_id", Types.StringType.get()),
            Types.NestedField.required(3, "chunk_index", Types.IntegerType.get()),
            Types.NestedField.required(4, "version", Types.LongType.get()),
            Types.NestedField.required(5, "operation", Types.StringType.get()),
            Types.NestedField.optional(6, "heading", Types.StringType.get()),
            Types.NestedField.optional(7, "text", Types.StringType.get()),
            Types.NestedField.required(
                8, "vector", Types.ListType.ofRequired(9, Types.FloatType.get()))),
        Set.of(1, 2, 3));
  }

  static Map<String, String> catalogProperties(
      String warehouse, String endpoint, String accessKey, String secretKey, String region) {
    Map<String, String> properties = new HashMap<>();
    properties.put("warehouse", warehouse);
    properties.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
    properties.put("s3.endpoint", endpoint);
    properties.put("s3.path-style-access", "true");
    properties.put("s3.access-key-id", accessKey);
    properties.put("s3.secret-access-key", secretKey);
    properties.put("s3.checksum-enabled", "false");
    properties.put("client.region", region);
    return properties;
  }

  static Configuration hadoopConfiguration(
      String endpoint, String accessKey, String secretKey, String region) {
    Configuration hadoop = new Configuration(false);
    hadoop.setClassLoader(SiloFileSystem.class.getClassLoader());
    hadoop.set(SiloFileSystem.IMPL, SiloFileSystem.class.getName());
    hadoop.setBoolean(SiloFileSystem.DISABLE_CACHE, true);
    hadoop.set(SiloFileSystem.ENDPOINT, endpoint);
    hadoop.set(SiloFileSystem.ACCESS_KEY, accessKey);
    hadoop.set(SiloFileSystem.SECRET_KEY, secretKey);
    hadoop.set(SiloFileSystem.REGION, region);
    return hadoop;
  }

  static TableLoader ensure(
      String warehouse, String endpoint, String accessKey, String secretKey, String region)
      throws Exception {
    Map<String, String> properties = catalogProperties(warehouse, endpoint, accessKey, secretKey, region);
    Configuration hadoop = hadoopConfiguration(endpoint, accessKey, secretKey, region);
    CatalogLoader loader = CatalogLoader.hadoop("rag", hadoop, properties);
    Catalog catalog = loader.loadCatalog();
    try {
      Namespace namespace = Namespace.of(NAMESPACE);
      SupportsNamespaces namespaces = (SupportsNamespaces) catalog;
      if (!namespaces.namespaceExists(namespace)) {
        namespaces.createNamespace(namespace);
      }
      TableIdentifier identifier = TableIdentifier.of(namespace, TABLE);
      if (!catalog.tableExists(identifier)) {
        catalog.createTable(
            identifier,
            schema(),
            PartitionSpec.unpartitioned(),
            Map.of("format-version", "2", "write.upsert.enabled", "true"));
      }
    } finally {
      if (catalog instanceof AutoCloseable closeable) {
        closeable.close();
      }
    }
    return TableLoader.fromCatalog(loader, TableIdentifier.of(NAMESPACE, TABLE));
  }
}
