package com.hassankarkaba.mprp.accounts;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real-browser checks against an explicitly isolated, already migrated/seeded composition. */
@EnabledIfEnvironmentVariable(named = "MPRP_BROWSER_BASE_URL", matches = ".+")
class BrowserJourneyTest {
  private record Credentials(String email, String password, String seed) {}

  private static String base, merchant;
  private static Path evidence;
  private static final Map<String, Credentials> credentials = new HashMap<>();
  private static final Map<String, Long> usedCounters = new HashMap<>();

  @BeforeAll
  static void configuration() throws Exception {
    assertEquals(
        "true",
        System.getenv("MPRP_BROWSER_ISOLATED"),
        "Browser mutation tests require an isolated composition.");
    assertTrue(
        java.util.Set.of("full", "registration")
            .contains(System.getenv().getOrDefault("MPRP_BROWSER_JOURNEY", "full")),
        "Unknown browser journey scope");
    base = System.getenv("MPRP_BROWSER_BASE_URL").replaceAll("/$", "");
    assertTrue(
        base.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}"),
        "Only local sandbox origins are permitted.");
    var lines = Files.readAllLines(Path.of(System.getenv("MPRP_DEMO_ACCESS_PATH")));
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.startsWith("Merchant: "))
        merchant = UUID.fromString(line.substring(10).trim()).toString();
      if (line.endsWith("@example.test") && i + 2 < lines.size())
        credentials.put(
            line,
            new Credentials(
                line,
                lines.get(i + 1).substring("Password: ".length()),
                lines.get(i + 2).substring("TOTP seed: ".length())));
    }
    assertNotNull(merchant, "Private demo file must identify the isolated merchant.");
    evidence =
        Path.of(
            System.getenv()
                .getOrDefault(
                    "MPRP_BROWSER_EVIDENCE_DIR",
                    System.getProperty("java.io.tmpdir") + "/mprp-browser-evidence"));
    Files.createDirectories(evidence);
  }

  @ParameterizedTest(name = "{0}: no-JavaScript browser journeys and responsive inspection")
  @ValueSource(strings = {"chromium", "firefox", "webkit"})
  void noJavaScriptProductJourneys(String engine) throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getenv("MPRP_BROWSER_ENGINE") == null
            || engine.equals(System.getenv("MPRP_BROWSER_ENGINE")));
    try (Playwright playwright = Playwright.create()) {
      BrowserType type =
          switch (engine) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
          };
      try (Browser browser = type.launch(new BrowserType.LaunchOptions().setHeadless(true));
          BrowserContext owner =
              browser.newContext(
                  new Browser.NewContextOptions()
                      .setJavaScriptEnabled(false)
                      .setAcceptDownloads(true))) {
        Page page = owner.newPage();
        page.setDefaultTimeout(functionalTimeout());
        login(page, "owner@example.test");
        for (int width : new int[] {360, 768, 1440}) {
          page.setViewportSize(width, 1000);
          navigate(page, "/m/" + merchant + "/");
          assertTrue(
              page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Juniper Goods"))
                  .isVisible());
          assertTrue(
              page.getByRole(
                      AriaRole.LINK,
                      new Page.GetByRoleOptions().setName("Payments and links").setExact(true))
                  .isVisible());
          page.keyboard().press("Tab");
          assertTrue(page.locator(":focus").count() > 0, "Keyboard focus must remain reachable.");
          page.screenshot(
              new Page.ScreenshotOptions()
                  .setPath(evidence.resolve(engine + "-merchant-" + width + ".png"))
                  .setFullPage(true));
        }
        Response denied = page.navigate(base + "/m/" + UUID.randomUUID() + "/payments/");
        assertEquals(404, denied.status(), "Unknown tenant must not disclose merchant data.");
        APIResponse csrf =
            owner
                .request()
                .post(
                    base + "/m/" + merchant + "/links/new/",
                    RequestOptions.create()
                        .setForm(
                            com.microsoft.playwright.options.FormData.create()
                                .set("amount", "100.00")
                                .set("capture_mode", "MANUAL")
                                .set("expiry_minutes", "1440")));
        assertEquals(403, csrf.status(), "Browser mutations require CSRF.");
        if (engine.equals(
            System.getenv().getOrDefault("MPRP_BROWSER_FINANCE_ENGINE", "chromium"))) {
          if ("registration".equals(System.getenv("MPRP_BROWSER_JOURNEY")))
            registrationJourney(browser);
          else financialJourney(browser, page);
        }
        Files.writeString(
            evidence.resolve(engine + "-receipt.txt"),
            "PASS: JavaScript disabled; verified login and TOTP; merchant pages at 360, 768 and"
                + " 1440; keyboard focus reachable; wrong tenant404; CSRF403. Browser="
                + browser.version()
                + "\n");
      }
    } catch (Throwable failure) {
      throw new AssertionError(
          "Browser verification failed: " + redact(String.valueOf(failure.getMessage())));
    }
  }

  private static void financialJourney(Browser browser, Page owner) throws Exception {
    navigate(owner, "/m/" + merchant + "/balances/");
    long beforePayable = payable(owner);
    UUID payment = checkout(browser, owner, "tok_demo_success", "MANUAL");
    navigate(owner, "/m/" + merchant + "/payments/" + payment + "/");
    await(
        () -> {
          owner.reload();
          return owner.locator("h1").innerText().equals("AUTHORIZED");
        },
        60000,
        "Authorization must finish durably.");
    navigate(owner, "/m/" + merchant + "/payments/" + payment + "/capture/");
    owner.locator("[name=amount]").fill("100.00");
    owner.locator("button[type=submit]").click();
    awaitOperation(owner, "SUCCEEDED");
    navigate(owner, "/m/" + merchant + "/payments/" + payment + "/");
    assertTrue(owner.locator("main").innerText().contains("10000"));
    navigate(owner, "/m/" + merchant + "/payments/" + payment + "/refunds/new/");
    owner.locator("[name=amount]").fill("25.00");
    owner.locator("[name=reason]").fill("Fictional browser acceptance refund");
    owner.locator("button[type=submit]").click();
    awaitOperation(owner, "SUCCEEDED");
    navigate(owner, "/m/" + merchant + "/payments/" + payment + "/");
    assertTrue(owner.locator("main").innerText().contains("2500"));
    navigate(owner, "/m/" + merchant + "/balances/");
    assertEquals(
        7180,
        payable(owner) - beforePayable,
        "10000 capture less320 fee and2500 refund adds7180 payable.");
    UUID timeoutPayment = checkout(browser, owner, "tok_demo_timeout_after_commit", "AUTOMATIC");
    navigate(owner, "/m/" + merchant + "/payments/" + timeoutPayment + "/");
    await(
        () -> {
          owner.reload();
          return owner.locator("h1").innerText().equals("CAPTURED");
        },
        90000,
        "Provider timeout-after-commit must resolve to its original captured effect.");
    try (BrowserContext analystContext =
            browser.newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false));
        BrowserContext otherOwnerContext =
            browser.newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false))) {
      Page analyst = analystContext.newPage();
      login(analyst, "analyst@example.test");
      Response forbidden = analyst.navigate(base + "/m/" + merchant + "/links/new/");
      assertEquals(403, forbidden.status(), "Analyst cannot create links.");
      navigate(analyst, "/m/" + merchant + "/reports/");
      analyst.locator("[name=kind]").selectOption("ledger");
      analyst
          .getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Request export"))
          .click();
      String exportUrl = analyst.url();
      await(
          () -> {
            analyst.reload();
            return analyst.locator("a[href$='/download/']").count() == 1;
          },
          60000,
          "Durable export should become retrievable.");
      Page checker = otherOwnerContext.newPage();
      login(checker, "owner2@example.test");
      Response wrongRequester = checker.navigate(exportUrl);
      assertEquals(
          404, wrongRequester.status(), "Another owner cannot retrieve the requester's export.");
      navigate(owner, "/m/" + merchant + "/team/");
      Locator member =
          owner
              .locator("section")
              .filter(new Locator.FilterOptions().setHasText("analyst@example.test"));
      member.locator("select[name=active]").selectOption("false");
      member
          .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Save member"))
          .click();
      Response revoked = analyst.navigate(exportUrl + "download/");
      assertEquals(
          404, revoked.status(), "Revoked membership must block retained export download.");
    }
    Files.writeString(
        evidence.resolve(
            System.getenv().getOrDefault("MPRP_BROWSER_FINANCE_ENGINE", "chromium")
                + "-financial-receipt.txt"),
        "PASS: browser-scoped guest checkout without JavaScript; manual authorization/capture10000;"
            + " fee320 and refund2500 =>7180 payable; independent timeout-after-commit resolved;"
            + " analyst mutation403; requester-owned export404 for other owner; revoked membership"
            + " download404. This does not prove forced intermediate crash or every acceptance"
            + " interleaving.\n");
    if (System.getenv("MPRP_MAILPIT_BASE_URL") != null) registrationJourney(browser);
  }

  private static void registrationJourney(Browser browser) throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12),
        email = "browser-" + suffix + "@demo.invalid",
        password = "Private!" + SecretCodec.randomToken(),
        business = "Fictional Browser " + suffix;
    credentials.put(email, new Credentials(email, password, ""));
    try (BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false));
        BrowserContext operations =
            browser.newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false))) {
      Page page = context.newPage();
      navigate(page, "/accounts/register/");
      page.locator("[name=email]").fill(email);
      page.locator("[name=password]").fill(password);
      page.locator("[name=merchant_name]").fill(business);
      page.locator("[name=timezone]").fill("America/Detroit");
      page.locator("button[type=submit]").click();
      assertTrue(page.locator("main").innerText().contains("eligible"));
      String verification = verificationPath(email);
      navigate(page, verification);
      page.locator("button[type=submit]").click();
      navigate(page, "/accounts/login/");
      page.locator("[name=email]").fill(email);
      page.locator("[name=password]").fill(password);
      page.locator("button[type=submit]").click();
      assertTrue(page.url().endsWith("/accounts/mfa/enroll/"));
      String seed = page.locator("main code").innerText().trim();
      credentials.put(email, new Credentials(email, password, seed));
      page.locator("[name=code]")
          .fill(SecretCodec.totp(seed, Instant.now().getEpochSecond() / 30 + 1));
      page.locator("button[type=submit]").click();
      assertEquals(
          10, page.locator("main li code").count(), "Enrollment issues ten recovery codes once.");
      navigate(page, "/accounts/profile/");
      Locator merchantLink =
          page.locator("main li a").filter(new Locator.FilterOptions().setHasText(business));
      String path = merchantLink.getAttribute("href");
      merchantLink.click();
      assertTrue(page.locator("main").innerText().contains("PENDING"));
      Page operator = operations.newPage();
      login(operator, "operations@example.test");
      navigate(operator, "/accounts/step-up/");
      Credentials op = credentials.get("operations@example.test");
      operator.locator("[name=password]").fill(op.password());
      operator.locator("[name=code]").fill(SecretCodec.totp(op.seed(), nextCounter(op.email())));
      operator.locator("button[type=submit]").click();
      navigate(operator, "/operations/merchants/");
      Locator section =
          operator.locator("section").filter(new Locator.FilterOptions().setHasText(business));
      section
          .getByRole(
              AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("activate").setExact(true))
          .click();
      navigate(page, path);
      assertTrue(page.locator("main").innerText().contains("ACTIVE"));
      Files.writeString(
          evidence.resolve(
              System.getenv().getOrDefault("MPRP_BROWSER_FINANCE_ENGINE", "chromium")
                  + "-registration-receipt.txt"),
          "PASS: register fictional account/merchant, captured local verification email, POST token"
              + " consumption, login and authenticator enrollment with ten recovery codes, pending"
              + " merchant inspection, separate operations actor step-up and activation."
              + " Credentials, seeds and bearer token were not retained in evidence.\n");
    }
  }

  @SuppressWarnings("unchecked")
  private static String verificationPath(String email) throws Exception {
    String mail = System.getenv("MPRP_MAILPIT_BASE_URL").replaceAll("/$", "");
    assertTrue(mail.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}"));
    var client =
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(2))
            .build();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    long deadline = System.nanoTime() + 60_000_000_000L;
    do {
      var response =
          client.send(
              java.net.http.HttpRequest.newBuilder(java.net.URI.create(mail + "/api/v1/messages"))
                  .timeout(java.time.Duration.ofSeconds(5))
                  .GET()
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        Map<String, Object> data = mapper.readValue(response.body(), Map.class);
        Object records = data.get("messages");
        if (records instanceof java.util.List<?> list)
          for (Object item : list) {
            Map<String, Object> row = (Map<String, Object>) item;
            if (row.get("To").toString().contains(email)) {
              var message =
                  client.send(
                      java.net.http.HttpRequest.newBuilder(
                              java.net.URI.create(mail + "/api/v1/message/" + row.get("ID")))
                          .timeout(java.time.Duration.ofSeconds(5))
                          .GET()
                          .build(),
                      java.net.http.HttpResponse.BodyHandlers.ofString());
              Map<String, Object> details = mapper.readValue(message.body(), Map.class);
              var matcher =
                  java.util.regex.Pattern.compile("/accounts/verify/[A-Za-z0-9_-]{43}/")
                      .matcher(String.valueOf(details.get("Text")));
              if (matcher.find()) return matcher.group();
            }
          }
      }
      Thread.sleep(500);
    } while (System.nanoTime() < deadline);
    throw new AssertionError(
        "Local captured verification email was not delivered within60seconds.");
  }

  private static UUID checkout(Browser browser, Page owner, String behavior, String mode)
      throws Exception {
    navigate(owner, "/m/" + merchant + "/links/new/");
    owner.locator("[name=amount]").fill("100.00");
    owner.locator("[name=capture_mode]").fill(mode);
    owner.locator("[name=expiry_minutes]").fill("1440");
    owner.locator("button[type=submit]").click();
    String guestPath = owner.locator("main a[href^='/checkout/']").getAttribute("href");
    try (BrowserContext guest =
        browser.newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false))) {
      Page page = guest.newPage();
      navigate(page, guestPath);
      page.locator("[name=customer_name]").fill("Fictional Browser Customer");
      page.locator("[name=payment_token]").selectOption(behavior);
      page.getByRole(
              AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Confirm simulated payment"))
          .click();
      UUID id = UUID.fromString(page.locator("main code").innerText().trim());
      try (BrowserContext outsider = browser.newContext()) {
        Response denied = outsider.newPage().navigate(page.url());
        assertEquals(404, denied.status(), "Guest receipt is bound to its browser secret.");
      }
      return id;
    }
  }

  private static void login(Page page, String email) throws Exception {
    Credentials credential = credentials.get(email);
    assertNotNull(credential, "Missing private demo identity");
    navigate(page, "/accounts/login/");
    page.setDefaultTimeout(functionalTimeout());
    page.locator("[name=email]").fill(credential.email());
    page.locator("[name=password]").fill(credential.password());
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in").setExact(true))
        .click();
    for (int attempt = 0; attempt < 3; attempt++) {
      long counter = nextCounter(email);
      navigate(page, "/accounts/mfa/");
      page.locator("[name=code]").fill(SecretCodec.totp(credential.seed(), counter));
      page.locator("button[type=submit]").click();
      usedCounters.put(email, counter);
      if (!page.url().contains("/accounts/mfa/")
          && !page.locator("h1").innerText().contains("could not")) return;
      Thread.sleep(Math.max(1, 30 - Instant.now().getEpochSecond() % 30) * 1000);
    }
    fail("MFA did not complete; inspect redacted application logs without exposing private codes.");
  }

  private static long nextCounter(String email) throws Exception {
    long counter = Instant.now().getEpochSecond() / 30 + 1,
        last = usedCounters.getOrDefault(email, -1L);
    if (counter <= last) {
      long until = (last * 30) - Instant.now().getEpochSecond() + 1;
      if (until > 0) Thread.sleep(Math.min(until, 30) * 1000);
      counter = Instant.now().getEpochSecond() / 30 + 1;
    }
    usedCounters.put(email, counter);
    return counter;
  }

  private static void awaitOperation(Page page, String expected) throws Exception {
    await(
        () -> {
          page.reload();
          return page.locator("h1").innerText().equals(expected);
        },
        90000,
        "Accepted operation must reach " + expected);
  }

  private static long payable(Page page) {
    return Long.parseLong(
        page.locator(".stat")
            .filter(new Locator.FilterOptions().setHasText("merchant payable"))
            .locator("strong")
            .innerText()
            .trim());
  }

  private static int functionalTimeout() {
    int value = Integer.parseInt(System.getenv().getOrDefault("MPRP_BROWSER_TIMEOUT_MS", "15000"));
    assertTrue(
        value >= 15000 && value <= 60000,
        "Functional timeout must be between15 and60seconds; this is not a latency qualification.");
    return value;
  }

  private static String redact(String message) {
    for (var credential : credentials.values()) {
      if (!credential.password().isEmpty())
        message = message.replace(credential.password(), "[REDACTED]");
      if (!credential.seed().isEmpty()) message = message.replace(credential.seed(), "[REDACTED]");
    }
    return message
        .replaceAll("/checkout/[A-Za-z0-9_-]{32,128}/", "/checkout/[REDACTED]/")
        .replaceAll("/accounts/(verify|reset)/[A-Za-z0-9_-]{32,128}/", "/accounts/$1/[REDACTED]/")
        .replaceAll("\\b[0-9]{6}\\b", "[REDACTED]");
  }

  private static void navigate(Page page, String path) {
    Response response = page.navigate(base + path);
    assertNotNull(response);
    assertTrue(response.status() < 400, "Unexpected HTTP " + response.status() + " for " + path);
  }

  private static void await(BooleanSupplier done, long milliseconds, String message)
      throws Exception {
    long end = System.nanoTime() + milliseconds * 1_000_000;
    do {
      if (done.getAsBoolean()) return;
      Thread.sleep(500);
    } while (System.nanoTime() < end);
    fail(message);
  }
}
