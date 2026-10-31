package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentEmbedding;
import com.sarna.rag.v1.Operation;
import java.util.List;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.table.data.GenericArrayData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.types.RowKind;

/** One Iceberg row for an accepted embedding. A delete is an equality tombstone. */
public final class EmbeddingRow implements MapFunction<DocumentEmbedding, RowData> {
  static final List<String> EQUALITY_FIELDS = List.of("tenant_id", "document_id", "chunk_index");

  static RowData toRow(DocumentEmbedding embedding) {
    RowKind kind =
        embedding.getOperation() == Operation.OPERATION_DELETE ? RowKind.DELETE : RowKind.INSERT;
    GenericRowData row = new GenericRowData(kind, 8);
    row.setField(0, StringData.fromString(embedding.getTenantId()));
    row.setField(1, StringData.fromString(embedding.getDocumentId()));
    row.setField(2, embedding.getChunkIndex());
    row.setField(3, embedding.getVersion());
    row.setField(4, StringData.fromString(embedding.getOperation().name()));
    row.setField(5, StringData.fromString(embedding.getHeading()));
    row.setField(6, StringData.fromString(embedding.getText()));
    float[] values = new float[embedding.getVectorCount()];
    for (int index = 0; index < values.length; index++) {
      values[index] = embedding.getVector(index);
    }
    row.setField(7, new GenericArrayData(values));
    return row;
  }

  @Override
  public RowData map(DocumentEmbedding embedding) {
    return toRow(embedding);
  }
}
