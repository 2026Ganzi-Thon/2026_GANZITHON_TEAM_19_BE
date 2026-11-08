package com.mindcare.domain.intake.repository;

import com.mindcare.domain.intake.entity.Match;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only matching projection. Provider profiles stay owned by the provider domain. */
public interface MatchingCandidateRepository extends Repository<Match, UUID> {
    @Query(value = """
            select pp.user_id as providerId, pp.display_name as displayName, pp.track as providerTrack,
                   pp.specialties as specialties, min(s.start_at) as earliestSlot,
                   pp.rating_avg as ratingAvg, pp.rating_count as ratingCount
              from provider_profiles pp
              join users u on u.id = pp.user_id
              join availability_slots s on s.provider_id = pp.user_id
             where pp.track = :track and u.status = 'active' and s.status = 'open' and s.start_at < :until
               and not exists (select 1 from complaints c where c.reporter_id = :clientId and c.target_id = pp.user_id)
             group by pp.user_id, pp.display_name, pp.track, pp.specialties, pp.rating_avg, pp.rating_count
            """, nativeQuery = true)
    List<Candidate> findEligible(@Param("track") String track, @Param("until") Instant until,
            @Param("clientId") UUID clientId);

    interface Candidate {
        UUID getProviderId();
        String getDisplayName();
        String getProviderTrack();
        String[] getSpecialties();
        Instant getEarliestSlot();
        Double getRatingAvg();
        Integer getRatingCount();
    }
}
