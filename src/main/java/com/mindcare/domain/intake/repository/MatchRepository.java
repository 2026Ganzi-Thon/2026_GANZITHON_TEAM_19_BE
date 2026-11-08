package com.mindcare.domain.intake.repository;
import com.mindcare.domain.intake.entity.Match;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MatchRepository extends JpaRepository<Match, UUID> { List<Match> findByIntakeIdOrderByRank(UUID intakeId); }
