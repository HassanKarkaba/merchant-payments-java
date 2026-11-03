package com.hassankarkaba.mprp.operations;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class QuarantineService {
  private final Db db;
  private final Events events;

  public QuarantineService(Db db, Events events) {
    this.db = db;
    this.events = events;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void conflict(String kind, String digest, String reason) {
    UUID id = UUID.randomUUID();
    db.exec(
        "insert into quarantine(id,kind,digest,safe_reason) values(?,?,?,?)",
        id,
        kind,
        digest,
        reason);
    events.emit(
        null, "failures.v1", id, "TRUST_BOUNDARY_CONFLICT", Map.of("quarantine_id", id.toString()));
  }
}
