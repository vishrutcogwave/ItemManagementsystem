package com.itemmanagement.controller;

import com.itemmanagement.dto.auth.AuthResponse;
import com.itemmanagement.dto.auth.LoginRequest;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.dto.common.MessageResponse;
import com.itemmanagement.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public AuthResponse me() {
        return authService.me();
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(HttpServletRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(new MessageResponse("Signed out"));
    }

    @PostMapping("/register-store")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse registerStore(@Valid @RequestBody RegisterStoreRequest request) {
        return authService.registerStore(request);
    }
}
