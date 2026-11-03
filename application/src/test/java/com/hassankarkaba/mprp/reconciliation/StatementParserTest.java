package com.hassankarkaba.mprp.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StatementParserTest {
  private final Instant start = Instant.parse("2026-10-01T00:00:00Z"),
      end = Instant.parse("2026-11-01T00:00:00Z");
  private static final String ACCOUNT = "55fc782a-7ec2-4b50-8b71-641e86c3aca0";

  private String header() {
    return String.join(",", StatementParser.PROCESSOR) + "\r\n";
  }

  private String row(String id, String event, String amount, String time) {
    return "1,"
        + id
        + ","
        + ACCOUNT
        + ","
        + event
        + ",source_"
        + id
        + ",batch_1,"
        + time
        + ",USD,"
        + amount
        + ",source_"
        + id
        + "\r\n";
  }

  private StatementParser.Parsed parse(String body, int count, long net) {
    return StatementParser.parse(
        body.getBytes(StandardCharsets.UTF_8),
        "PROCESSOR_ECONOMIC",
        ACCOUNT,
        start,
        end,
        count,
        net,
        0,
        net);
  }

  @Test
  void exactFeeAndCaptureEvidenceParsesWithoutCreatingMoney() {
    var p =
        parse(
            header()
                + row("capture", "CAPTURE", "10000", "2026-10-05T00:00:00Z")
                + row("fee", "FEE", "-320", "2026-10-05T00:00:00Z"),
            2,
            9680);
    assertTrue(p.valid(), p.errors().toString());
    assertEquals(10000L, p.rows().getFirst().get("amount_minor"));
  }

  @Test
  void invalidMiddleRowMakesWholeFileInvalid() {
    var p =
        parse(
            header()
                + row("a", "CAPTURE", "10000", "2026-10-01T00:00:00Z")
                + row("b", "REFUND", "+2500", "2026-10-02T00:00:00Z")
                + row("c", "FEE", "-320", "2026-10-03T00:00:00Z"),
            3,
            7180);
    assertFalse(p.valid());
    assertTrue(p.errors().stream().anyMatch(s -> s.contains("Row 2")));
  }

  @Test
  void exclusiveEndAndForeignAccountAreRejected() {
    assertFalse(
        parse(header() + row("a", "CAPTURE", "100", "2026-11-01T00:00:00Z"), 1, 100).valid());
    assertFalse(
        parse(
                (header() + row("a", "CAPTURE", "100", "2026-10-01T00:00:00Z"))
                    .replace(ACCOUNT, "00000000-0000-0000-0000-000000000000"),
                1,
                100)
            .valid());
  }

  @Test
  void zeroActivityStillHasExactHeaderAndBalance() {
    assertTrue(parse(header(), 0, 0).valid());
    assertFalse(parse(header().replace("reference", "description"), 0, 0).valid());
  }

  @Test
  void rejectsMalformedUtf8AndDuplicateIds() {
    var invalid =
        StatementParser.parse(
            new byte[] {(byte) 0xc3, 0x28}, "BANK", ACCOUNT, start, end, 0, 0, 0, 0);
    assertFalse(invalid.valid());
    assertFalse(
        parse(
                header()
                    + row("same", "CAPTURE", "100", "2026-10-01T00:00:00Z")
                    + row("same", "CAPTURE", "100", "2026-10-01T00:00:00Z"),
                2,
                200)
            .valid());
  }

  @Test
  void tenThousandRowsIsAcceptedAndNextRowRejected() {
    StringBuilder content = new StringBuilder(header());
    for (int i = 0; i < 10000; i++)
      content.append(row("line_" + i, "CAPTURE", "1", "2026-10-01T00:00:00Z"));
    assertTrue(parse(content.toString(), 10000, 10000).valid());
    content.append(row("extra", "CAPTURE", "1", "2026-10-01T00:00:00Z"));
    assertFalse(parse(content.toString(), 10000, 10000).valid());
  }

  @Test
  void negativeEconomicEventsCannotBePositive() {
    assertFalse(
        parse(header() + row("refund", "REFUND", "2500", "2026-10-01T00:00:00Z"), 1, 2500).valid());
  }
}
