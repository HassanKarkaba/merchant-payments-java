package com.hassankarkaba.mprp.payments;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
  List<Payment> findByMerchantIdOrderByIdDesc(UUID merchantId, Pageable page);
}
