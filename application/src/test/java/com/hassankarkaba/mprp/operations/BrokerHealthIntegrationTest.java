package com.hassankarkaba.mprp.operations;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Actual broker topology recovery with unique test topic names; no payment, delivery or PG claim.
 */
@EnabledIfEnvironmentVariable(named = "MPRP_KAFKA_TEST_BOOTSTRAP", matches = ".+")
class BrokerHealthIntegrationTest {
  private Admin admin;
  private UUID environment;
  private List<String> topics;
  private ProbeDb db;
  private BrokerHealth health;

  private static class ProbeDb extends Db {
    Boolean ready;

    ProbeDb() {
      super(null);
    }

    @Override
    public int exec(String sql, Object... values) {
      assertTrue(sql.startsWith("update system_control set broker_ready="));
      ready = (Boolean) values[0];
      return 1;
    }
  }

  @BeforeEach
  void setup() {
    String bootstrap = System.getenv("MPRP_KAFKA_TEST_BOOTSTRAP");
    assertTrue(
        bootstrap.startsWith("127.0.0.1:"), "Only a loopback verification broker is allowed");
    environment = UUID.randomUUID();
    System.out.println("Verification broker namespace: " + environment);
    String prefix = "mprp." + environment + ".s1.";
    topics =
        List.of("work.v1", "provider-events.v1", "domain-events.v1", "failures.v1").stream()
            .map(s -> prefix + s)
            .toList();
    admin =
        Admin.create(
            Map.of(
                "bootstrap.servers",
                bootstrap,
                "request.timeout.ms",
                "5000",
                "default.api.timeout.ms",
                "10000"));
    db = new ProbeDb();
    health = new BrokerHealth(db, bootstrap, environment.toString(), true);
  }

  @AfterEach
  void cleanup() throws Exception {
    if (admin == null) return;
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos(), absentSince = 0;
    try {
      while (System.nanoTime() < deadline) {
        var present = admin.listTopics().names().get(2, TimeUnit.SECONDS);
        var owned = topics.stream().filter(present::contains).toList();
        if (!owned.isEmpty()) {
          absentSince = 0;
          try {
            admin.deleteTopics(owned).all().get(5, TimeUnit.SECONDS);
          } catch (java.util.concurrent.TimeoutException uncertainty) {
            /* inspect same names again */
          } catch (java.util.concurrent.ExecutionException alreadyGone) {
            if (!(alreadyGone.getCause()
                instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException))
              throw alreadyGone;
          }
        } else {
          if (absentSince == 0) absentSince = System.nanoTime();
          if (System.nanoTime() - absentSince >= Duration.ofSeconds(3).toNanos()) {
            System.out.println("Verification broker namespace absent: " + environment);
            return;
          }
        }
        Thread.sleep(100);
      }
      fail("Scoped verification topics remain after bounded cleanup: " + environment);
    } finally {
      admin.close(Duration.ofSeconds(2));
    }
  }

  @FunctionalInterface
  private interface Observed {
    boolean test() throws Exception;
  }

  private void awaitObserved(String description, Observed state) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    Exception last = null;
    while (System.nanoTime() < deadline) {
      try {
        if (state.test()) return;
      } catch (java.util.concurrent.TimeoutException uncertainty) {
        last = uncertainty;
      } catch (java.util.concurrent.ExecutionException missing) {
        if (!(missing.getCause()
            instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException))
          throw missing;
        last = missing;
      }
      Thread.sleep(100);
    }
    fail("Broker did not expose " + description + " within the test bound", last);
  }

  private void createInitialTopic() throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        admin
            .createTopics(List.of(new NewTopic(topics.getFirst(), 1, (short) 1).configs(policy())))
            .all()
            .get(5, TimeUnit.SECONDS);
        return;
      } catch (java.util.concurrent.TimeoutException uncertain) {
        /* Same name and policy on retry. */
      } catch (java.util.concurrent.ExecutionException outcome) {
        if (outcome.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)
          return;
        throw outcome;
      }
    }
    awaitObserved(
        "the original topic after uncertain creation",
        () ->
            admin
                .describeTopics(List.of(topics.getFirst()))
                .allTopicNames()
                .get(2, TimeUnit.SECONDS)
                .containsKey(topics.getFirst()));
  }

  private Map<String, String> policy() {
    return Map.of(
        "retention.ms", "604800000", "max.message.bytes", "262144", "min.insync.replicas", "1");
  }

  private void expectReady() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      health.probe();
      if (Boolean.TRUE.equals(db.ready)) return;
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    assertEquals(
        Boolean.TRUE,
        db.ready,
        "A recovered local topology must become ready within the bounded observation window");
  }

  private void assertTopology() throws Exception {
    var described = admin.describeTopics(topics).allTopicNames().get(10, TimeUnit.SECONDS);
    assertEquals(4, described.size());
    for (var topic : described.values()) {
      assertEquals(3, topic.partitions().size());
      for (var partition : topic.partitions()) {
        assertNotNull(partition.leader());
        assertTrue(partition.leader().id() >= 0);
        assertEquals(1, partition.replicas().size());
        assertEquals(1, partition.isr().size());
      }
    }
  }

  @Test
  void probeCreatesMissingTopicsExpandsSmallTopicAndRecreatesDeletedTopics() throws Exception {
    createInitialTopic();
    expectReady();
    assertTopology();
    admin.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
    awaitObserved(
        "deletion of the original four topics",
        () ->
            topics.stream()
                .noneMatch(admin.listTopics().names().get(2, TimeUnit.SECONDS)::contains));
    // A normal probe owns recreation after lost broker topic state; no init command is needed.
    expectReady();
    assertTopology();
  }

  @Test
  void wrongPartitionOrRetentionPolicyCannotReportReady() throws Exception {
    expectReady();
    ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topics.getFirst());
    admin
        .incrementalAlterConfigs(
            Map.of(
                resource,
                List.of(
                    new AlterConfigOp(
                        new ConfigEntry("retention.ms", "1000"), AlterConfigOp.OpType.SET))))
        .all()
        .get(10, TimeUnit.SECONDS);
    awaitObserved(
        "the changed retention policy",
        () ->
            "1000"
                .equals(
                    admin
                        .describeConfigs(List.of(resource))
                        .all()
                        .get(2, TimeUnit.SECONDS)
                        .get(resource)
                        .get("retention.ms")
                        .value()));
    health.probe();
    assertEquals(Boolean.FALSE, db.ready);
    admin
        .incrementalAlterConfigs(
            Map.of(
                resource,
                List.of(
                    new AlterConfigOp(
                        new ConfigEntry("retention.ms", "604800000"), AlterConfigOp.OpType.SET))))
        .all()
        .get(10, TimeUnit.SECONDS);
    awaitObserved(
        "the restored retention policy",
        () ->
            "604800000"
                .equals(
                    admin
                        .describeConfigs(List.of(resource))
                        .all()
                        .get(2, TimeUnit.SECONDS)
                        .get(resource)
                        .get("retention.ms")
                        .value()));
    expectReady();
    admin
        .createPartitions(Map.of(topics.getFirst(), NewPartitions.increaseTo(4)))
        .all()
        .get(10, TimeUnit.SECONDS);
    awaitObserved(
        "the fourth partition",
        () ->
            admin
                    .describeTopics(List.of(topics.getFirst()))
                    .allTopicNames()
                    .get(2, TimeUnit.SECONDS)
                    .get(topics.getFirst())
                    .partitions()
                    .size()
                == 4);
    health.probe();
    assertEquals(Boolean.FALSE, db.ready);
  }
}
