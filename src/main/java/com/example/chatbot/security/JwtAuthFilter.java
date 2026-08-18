package com.example.chatbot.security;

import com.example.chatbot.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                String userId = jwtUtil.validateAndGetUserId(token);
                userRepository.findById(UUID.fromString(userId)).ifPresent(user -> {
                    AppUserPrincipal principal = new AppUserPrincipal(user);
                    var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            } catch (JwtException | IllegalArgumentException e) {
                // Invalid/expired token -> leave unauthenticated, let Spring Security
                // reject the request downstream with 401. Do not log the raw token.
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * SSE streaming (/api/chat/stream) completes via an async dispatch, which
     * re-runs the whole filter chain. OncePerRequestFilter skips itself on that
     * second pass by default, leaving the SecurityContext empty and causing
     * AuthorizationFilter to reject the request as anonymous. Re-run the JWT
     * check on async dispatch too so the principal is still set.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
