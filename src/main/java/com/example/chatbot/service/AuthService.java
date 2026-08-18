package com.example.chatbot.service;

import com.example.chatbot.dto.AuthDtos.*;
import com.example.chatbot.entity.User;
import com.example.chatbot.exception.AppExceptions.DuplicateEmailException;
import com.example.chatbot.repository.UserRepository;
import com.example.chatbot.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException("An account with this email already exists");
        }
        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId().toString(), user.getEmail());
        return new AuthResponse(token, "Bearer", jwtUtil.getExpirationSeconds());
    }

    public AuthResponse login(LoginRequest request) {
        // Throws BadCredentialsException on failure - handled by GlobalExceptionHandler
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalStateException("Authenticated user vanished mid-request"));

        String token = jwtUtil.generateToken(user.getId().toString(), user.getEmail());
        return new AuthResponse(token, "Bearer", jwtUtil.getExpirationSeconds());
    }
}
