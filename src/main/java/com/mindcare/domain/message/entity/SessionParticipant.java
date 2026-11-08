package com.mindcare.domain.message.entity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
@Entity @Table(name = "sessions") @Getter
public class SessionParticipant { @Id private UUID id; private UUID clientId; private UUID providerId; }
