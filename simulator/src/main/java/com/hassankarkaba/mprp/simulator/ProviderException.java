package com.hassankarkaba.mprp.simulator;

public final class ProviderException extends RuntimeException {
  final int status;
  final String code;

  ProviderException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  static ProviderException invalid(String message) {
    return new ProviderException(400, "invalid_input", message);
  }
}
