package com.mindcare.domain.kpi.repository;
import com.mindcare.domain.kpi.entity.AnalyticsEvent;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent,Long>{ boolean existsByEid(String eid); }
