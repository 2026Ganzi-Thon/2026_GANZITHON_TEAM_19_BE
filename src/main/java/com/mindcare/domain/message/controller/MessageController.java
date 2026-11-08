package com.mindcare.domain.message.controller;
import com.mindcare.domain.message.dto.*; import com.mindcare.domain.message.service.MessageService; import com.mindcare.global.auth.*; import io.swagger.v3.oas.annotations.Operation; import jakarta.validation.Valid; import java.util.*; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor public class MessageController { private final MessageService service;
 @Operation(operationId="MSG-01") @GetMapping("/sessions/{sessionId}/messages") public List<MessageResponse> list(@LoginUser AuthUser user,@PathVariable UUID sessionId,@RequestParam(required=false) UUID afterId) { return service.list(user.id(),sessionId,afterId); }
 @PostMapping("/sessions/{sessionId}/messages") public MessageResponse send(@LoginUser AuthUser user,@PathVariable UUID sessionId,@Valid @RequestBody MessageSendRequest request) { return service.send(user.id(),sessionId,request); }
}
