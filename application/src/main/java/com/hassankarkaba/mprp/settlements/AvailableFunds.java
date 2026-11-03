package com.hassankarkaba.mprp.settlements;

import java.math.BigInteger;

/** Exact merchant-scoped settled funds after reservations; clearing never participates. */
public final class AvailableFunds {
  private AvailableFunds() {}

  public static long cents(
      long payable, long bank, long payoutReservations, long refundReservations) {
    if (payoutReservations < 0 || refundReservations < 0)
      throw new IllegalArgumentException("Negative reservation");
    return BigInteger.valueOf(Math.min(payable, bank))
        .subtract(BigInteger.valueOf(payoutReservations))
        .subtract(BigInteger.valueOf(refundReservations))
        .max(BigInteger.ZERO)
        .longValueExact();
  }
}
