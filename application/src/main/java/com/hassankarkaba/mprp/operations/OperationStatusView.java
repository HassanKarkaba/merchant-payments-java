package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;

/** Public inspection contract, independent of persistence entities. */
public record OperationStatusView(
    UUID operation,
    State state,
    String businessState,
    String message,
    Instant updatedAt,
    long version,
    String inspectionUrl) {
  public enum State {
    QUEUED,
    DISPATCHING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
      return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
  }

  public Map<String, Object> fields() {
    var dto = new LinkedHashMap<String, Object>();
    dto.put("schema_version", 1);
    dto.put("operation_id", operation.toString());
    dto.put("execution_state", state.name());
    dto.put("business_state", businessState);
    dto.put("outcome_known", state.terminal());
    dto.put("safe_message", message);
    dto.put("updated_at", CanonicalJson.instant(updatedAt));
    dto.put("version", version);
    dto.put("inspection_url", inspectionUrl);
    dto.put("retry_after_seconds", 3);
    return Collections.unmodifiableMap(dto);
  }
}
