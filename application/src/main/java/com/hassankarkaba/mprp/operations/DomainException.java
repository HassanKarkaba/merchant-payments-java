package com.hassankarkaba.mprp.operations;

public class DomainException extends RuntimeException {
  private final int status;

  public DomainException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static void require(boolean condition, int status, String message) {
    if (!condition) throw new DomainException(status, message);
  }
}
