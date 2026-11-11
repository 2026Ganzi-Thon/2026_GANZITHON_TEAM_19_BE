package com.mindcare.domain.intake.repository;
import com.mindcare.domain.intake.entity.Match;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
public interface MatchRepository extends JpaRepository<Match, UUID> {
    List<Match> findByIntakeIdOrderByRank(UUID intakeId);

    @Query(value = """
            select m.provider_id as providerId, pp.display_name as displayName, m.track as track,
                   pp.specialties as specialties, min(s.start_at) as earliestSlot,
                   m.reasons as reasons, m.rank as rank
              from matches m join provider_profiles pp on pp.user_id = m.provider_id
              left join availability_slots s on s.provider_id = m.provider_id and s.status = 'open'
             where m.intake_id = :intakeId
             group by m.provider_id, pp.display_name, m.track, pp.specialties, m.reasons, m.rank
             order by m.rank
            """, nativeQuery = true)
    List<MatchView> findDetailsByIntakeIdOrderByRank(UUID intakeId);

    interface MatchView {
        UUID getProviderId();
        String getDisplayName();
        String getTrack();
        String[] getSpecialties();
        Instant getEarliestSlot();
        String getReasons();
        short getRank();
    }
}
