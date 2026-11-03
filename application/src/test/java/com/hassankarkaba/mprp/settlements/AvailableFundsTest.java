package com.hassankarkaba.mprp.settlements;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AvailableFundsTest {
  @Test
  void clearingCannotFundPayoutAndRefundReservationReducesBankMoney() {
    assertEquals(0, AvailableFunds.cents(9680, 0, 0, 0));
    assertEquals(4680, AvailableFunds.cents(9680, 7180, 1000, 1500));
  }

  @Test
  void deficitRemainsVisibleButAvailabilityNeverNegative() {
    assertEquals(0, AvailableFunds.cents(-146, 0, 0, 0));
    assertEquals(
        0, AvailableFunds.cents(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
  }

  @Test
  void negativeReservationIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> AvailableFunds.cents(1000, 1000, -1, 0));
  }
}
