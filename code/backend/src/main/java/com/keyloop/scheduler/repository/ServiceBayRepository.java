package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.ServiceBay;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceBayRepository extends JpaRepository<ServiceBay, Long> {
    List<ServiceBay> findAllByDealership_IdOrderByIdAsc(Long dealershipId);
}
