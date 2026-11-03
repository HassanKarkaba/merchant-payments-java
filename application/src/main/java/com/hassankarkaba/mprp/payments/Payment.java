package com.hassankarkaba.mprp.payments;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "payment")
public class Payment {
  @Id private UUID id;

  @Column(name = "merchant_id", nullable = false)
  private UUID merchantId;

  private String status;

  @Column(name = "captured_minor")
  private long capturedMinor;

  @Column(name = "refunded_minor")
  private long refundedMinor;

  @Version private long version;

  protected Payment() {}

  public UUID getId() {
    return id;
  }

  public String getStatus() {
    return status;
  }

  public long getCapturedMinor() {
    return capturedMinor;
  }

  public long getRefundedMinor() {
    return refundedMinor;
  }
}
