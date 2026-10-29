package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Technician;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {
    List<Technician> findAllByDealership_IdAndServiceType_IdOrderByIdAsc(Long dealershipId, Long serviceTypeId);
}
