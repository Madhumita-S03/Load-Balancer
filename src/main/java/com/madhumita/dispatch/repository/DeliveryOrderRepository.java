package com.madhumita.dispatch.repository;

import com.madhumita.dispatch.domain.DeliveryOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {
    List<DeliveryOrder> findAllByOrderIdIn(Collection<String> orderIds);
}
