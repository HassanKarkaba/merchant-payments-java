package com.hassankarkaba.mprp.protocol;

import java.math.BigInteger;

public final class Money {
  private Money() {}

  public static long parse(String input, long minimum, long maximum) {
    if (input == null || input.length() > 16 || !input.matches("[0-9]+(?:\\.[0-9]{1,2})?"))
      throw new IllegalArgumentException("Use ASCII digits and at most two decimal places");
    String[] parts = input.split("\\.");
    BigInteger cents = new BigInteger(parts[0]).multiply(BigInteger.valueOf(100));
    if (parts.length == 2)
      cents = cents.add(new BigInteger(parts[1].length() == 1 ? parts[1] + "0" : parts[1]));
    long result = cents.longValueExact();
    if (result < minimum || result > maximum)
      throw new IllegalArgumentException("Amount is outside its allowed range");
    return result;
  }

  public static long fee(long principal) {
    if (principal < 100 || principal > 100000000)
      throw new IllegalArgumentException("Capture amount outside bounds");
    return BigInteger.valueOf(principal)
        .multiply(BigInteger.valueOf(29))
        .add(BigInteger.valueOf(500))
        .divide(BigInteger.valueOf(1000))
        .add(BigInteger.valueOf(30))
        .longValueExact();
  }
}
