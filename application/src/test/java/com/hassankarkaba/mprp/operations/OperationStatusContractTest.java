package com.hassankarkaba.mprp.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OperationStatusContractTest {
  @SuppressWarnings("unchecked")
  @Test
  void statusObeysRetainedSchemaAndTruthfulUncertainty() throws Exception {
    var schema =
        CanonicalJson.parse(Files.readAllBytes(Path.of("../contracts/operation-status-v1.json")));
    Db db = mock(Db.class);
    Authz auth = mock(Authz.class);
    UUID merchant = UUID.randomUUID(), id = UUID.randomUUID();
    var service =
        new OperationService(
            db, mock(AuditService.class), mock(Events.class), auth, mock(Readiness.class));
    for (String state :
        List.of("QUEUED", "DISPATCHING", "UNKNOWN", "SUCCEEDED", "FAILED", "CANCELLED")) {
      Map<String, Object> row = new HashMap<>();
      row.put("status", state);
      row.put("business_state", "PROCESSING");
      row.put("updated_at", Timestamp.from(Instant.parse("2026-11-02T00:00:00.123456Z")));
      row.put("version", 0L);
      when(db.one(anyString(), any(), any())).thenReturn(row);
      var dto = service.status(merchant, id);
      assertEquals(new HashSet<>((List<String>) schema.get("required")), dto.keySet());
      assertEquals(1, dto.get("schema_version"));
      assertEquals(3, dto.get("retry_after_seconds"));
      assertEquals("2026-11-02T00:00:00.123456Z", dto.get("updated_at"));
      assertEquals(
          Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state), dto.get("outcome_known"));
      assertEquals(id.toString(), dto.get("operation_id"));
    }
  }
}
