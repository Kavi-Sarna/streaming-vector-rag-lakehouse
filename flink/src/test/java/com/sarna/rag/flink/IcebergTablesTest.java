package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.Schema;
import org.junit.jupiter.api.Test;

class IcebergTablesTest {
  @Test
  void tableIdentityIsTenantDocumentAndChunk() {
    Schema schema = IcebergTables.schema();
    assertEquals(Set.of("tenant_id", "document_id", "chunk_index"), schema.identifierFieldNames());
    assertEquals("version", schema.findField("version").name());
    assertTrue(schema.findField("vector").type().isListType());
  }

  @Test
  void catalogPointsAtTheLocalWarehouse() {
    Map<String, String> properties =
        IcebergTables.catalogProperties(
            "s3://lakehouse/warehouse", "http://minio:9000", "minio", "minio12345", "us-east-1");

    assertEquals("s3://lakehouse/warehouse", properties.get("warehouse"));
    assertEquals("org.apache.iceberg.aws.s3.S3FileIO", properties.get("io-impl"));
    assertEquals("http://minio:9000", properties.get("s3.endpoint"));
    assertEquals("true", properties.get("s3.path-style-access"));
    assertEquals("false", properties.get("s3.checksum-enabled"));
    assertEquals("us-east-1", properties.get("client.region"));
  }

  @Test
  void hadoopCatalogUsesTheSiloFilesystem() {
    Configuration hadoop =
        IcebergTables.hadoopConfiguration("http://minio:9000", "minio", "minio12345", "us-east-1");
    assertEquals(SiloFileSystem.class.getName(), hadoop.get(SiloFileSystem.IMPL));
    assertEquals("http://minio:9000", hadoop.get(SiloFileSystem.ENDPOINT));
    assertEquals("true", hadoop.get(SiloFileSystem.DISABLE_CACHE));

    Path path = new Path("s3://lakehouse/warehouse/rag");
    assertEquals("lakehouse", SiloFileSystem.bucket(path));
    assertEquals("warehouse/rag", SiloFileSystem.key(path));
    assertEquals("warehouse/rag/", SiloFileSystem.directoryKey(SiloFileSystem.key(path)));
  }
}
