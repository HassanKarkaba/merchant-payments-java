package com.hassankarkaba.mprp.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.merchants.MerchantService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ControlDecisionReplayTest {
  private PlatformTransactionManager manager() {
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    return manager;
  }

  @Test
  void releasedRecoveryReplaysOnlyForItsCurrentCheckerWithoutFreshStepUpOrProviderCall() {
    var db = mock(Db.class);
    var auth = mock(Authz.class);
    var provider = mock(ProviderClient.class);
    UUID id = UUID.randomUUID(), maker = UUID.randomUUID(), checker = UUID.randomUUID();
    var receipt =
        Map.<String, Object>of(
            "id", id, "maker_id", maker, "checker_id", checker, "status", "RELEASED");
    when(db.one("select * from recovery_inventory where id=?", id)).thenReturn(receipt);
    when(db.one("select * from recovery_inventory where id=? for update", id)).thenReturn(receipt);
    when(auth.platform("FINANCE_APPROVER")).thenReturn(checker);
    var service = new RecoveryService(db, auth, provider, mock(AuditService.class), manager());
    service.release(id);
    verify(auth, never()).stepUp();
    verifyNoInteractions(provider);
    when(auth.platform("FINANCE_APPROVER")).thenReturn(UUID.randomUUID());
    assertEquals(403, assertThrows(DomainException.class, () -> service.release(id)).status());
    verify(auth, never()).stepUp();
    verifyNoInteractions(provider);
  }

  @Test
  void approvedAndCompletedPurgeApprovalReplaysAreBoundToTheCurrentOriginalChecker() {
    for (String phase : new String[] {"APPROVED", "RETIRED", "PROVIDER_PURGED", "COMPLETED"}) {
      var db = mock(Db.class);
      var auth = mock(Authz.class);
      var provider = mock(ProviderClient.class);
      UUID id = UUID.randomUUID(),
          maker = UUID.randomUUID(),
          checker = UUID.randomUUID(),
          merchant = UUID.randomUUID();
      var receipt =
          Map.<String, Object>of(
              "id",
              id,
              "maker_id",
              maker,
              "checker_id",
              checker,
              "merchant_id",
              merchant,
              "phase",
              phase);
      when(db.one("select * from purge_receipt where id=?", id)).thenReturn(receipt);
      when(db.one("select * from purge_receipt where id=? for update", id)).thenReturn(receipt);
      when(auth.platform("FINANCE_APPROVER")).thenReturn(checker);
      var service =
          new PurgeService(
              db,
              auth,
              provider,
              mock(AuditService.class),
              mock(MerchantService.class),
              manager(),
              "unused");
      service.approve(id);
      verify(auth, never()).stepUp();
      verifyNoInteractions(provider);
      when(auth.platform("FINANCE_APPROVER")).thenReturn(UUID.randomUUID());
      assertEquals(403, assertThrows(DomainException.class, () -> service.approve(id)).status());
      verify(auth, never()).stepUp();
      verifyNoInteractions(provider);
    }
  }
}
