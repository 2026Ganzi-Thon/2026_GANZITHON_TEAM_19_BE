package com.mindcare.domain.session.dto;

import java.time.Instant;
import java.util.UUID;

/** SES-09: a provider's completed sessions with the same client, excluding transcript and report content. */
public record SessionHistoryItem(UUID sessionId, Instant date, Short moodBefore, Short moodAfter, String myNote) {
}
