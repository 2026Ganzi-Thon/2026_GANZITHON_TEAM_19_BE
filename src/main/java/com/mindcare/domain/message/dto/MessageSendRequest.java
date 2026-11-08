package com.mindcare.domain.message.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record MessageSendRequest(@NotBlank @Size(max = 4000) String body, @NotBlank @Size(max = 100) String clientMsgId) { }
