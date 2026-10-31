package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sarna.rag.v1.DocumentEmbedding;
import com.sarna.rag.v1.Operation;
import org.apache.flink.table.data.GenericArrayData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.types.RowKind;
import org.junit.jupiter.api.Test;

class EmbeddingRowTest {
  @Test
  void insertRowCarriesTheEqualityFieldsAndTheVector() {
    RowData row =
        EmbeddingRow.toRow(
            DocumentEmbedding.newBuilder()
                .setTenantId("tenant-alpha")
                .setDocumentId("doc")
                .setVersion(4)
                .setOperation(Operation.OPERATION_INSERT)
                .setChunkIndex(2)
                .setHeading("Deploy")
                .setText("steps")
                .addVector(0.5f)
                .addVector(-1f)
                .build());

    assertEquals(RowKind.INSERT, row.getRowKind());
    assertEquals(StringData.fromString("tenant-alpha"), row.getString(0));
    assertEquals(StringData.fromString("doc"), row.getString(1));
    assertEquals(2, row.getInt(2));
    assertEquals(4L, row.getLong(3));
    GenericArrayData vector = (GenericArrayData) ((org.apache.flink.table.data.GenericRowData) row).getField(7);
    assertEquals(0.5f, vector.getFloat(0));
    assertEquals(-1f, vector.getFloat(1));
  }

  @Test
  void deleteRowIsAnEqualityTombstone() {
    RowData row =
        EmbeddingRow.toRow(
            DocumentEmbedding.newBuilder()
                .setTenantId("tenant-alpha")
                .setDocumentId("doc")
                .setVersion(5)
                .setOperation(Operation.OPERATION_DELETE)
                .setChunkIndex(2)
                .build());

    assertEquals(RowKind.DELETE, row.getRowKind());
    assertEquals(2, row.getInt(2));
    assertEquals(5L, row.getLong(3));
  }
}
