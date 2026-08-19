package com.example.chatbot.controller;

import com.example.chatbot.config.SecurityConfig;
import com.example.chatbot.dto.AuthDtos.AuthResponse;
import com.example.chatbot.dto.AuthDtos.RegisterRequest;
import com.example.chatbot.repository.UserRepository;
import com.example.chatbot.security.JwtUtil;
import com.example.chatbot.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @WebMvcTest doesn't component-scan @Configuration classes, so our custom
// SecurityConfig (permitAll on /api/auth/**, CSRF disabled) isn't picked up
// by default - Spring Security falls back to its own deny-all/CSRF-enabled
// default chain instead, which 403s every request here. Import it explicitly.
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AuthService authService;

    // Not exercised by these tests (/api/auth/** is permitAll) - only mocked so
    // JwtAuthFilter (pulled into the @WebMvcTest slice because it's a Filter)
    // can be constructed; its own dependencies aren't component-scanned here.
    @MockBean private JwtUtil jwtUtil;
    @MockBean private UserRepository userRepository;
    @MockBean private UserDetailsService userDetailsService;

    @Test
    void register_returnsToken_onValidRequest() throws Exception {
        when(authService.register(any())).thenReturn(new AuthResponse("fake-jwt", "Bearer", 86400));

        RegisterRequest req = new RegisterRequest("new@example.com", "password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("fake-jwt"));
    }

    @Test
    void register_returns400_onInvalidEmail() throws Exception {
        RegisterRequest req = new RegisterRequest("not-an-email", "password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
