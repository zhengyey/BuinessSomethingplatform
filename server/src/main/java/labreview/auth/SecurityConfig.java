package labreview.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    @Bean
    UserDetailsService users(@Value("${labreview.username}") String username,
                             @Value("${labreview.password}") String password) {
        if (username.isBlank() || username.length() > 100 || password.isBlank()) {
            throw new IllegalArgumentException("请设置有效的本地收货员账号和密码");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(new BCryptPasswordEncoder().encode(password))
                .roles("RECEIVER")
                .build());
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    SecurityFilterChain workerSecurity(HttpSecurity http, ObjectMapper json,
                                       @Value("${labreview.worker-token}") String token) throws Exception {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher("/internal/parse-tasks", "/internal/parse-tasks/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
                        if (token.isBlank() || authorization == null ||
                                !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            error(json, response, 401, "WORKER_UNAUTHORIZED", "Worker 凭据无效");
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
        return http
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/session").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/login").permitAll()
                        .anyRequest().hasRole("RECEIVER"))
                .requestCache(RequestCacheConfigurer::disable)
                .formLogin(login -> login.loginProcessingUrl("/api/login")
                        .successHandler((request, response, auth) -> response.setStatus(204))
                        .failureHandler((request, response, error) ->
                                error(json, response, 401, "BAD_CREDENTIALS", "用户名或密码有误")))
                .logout(logout -> logout.logoutUrl("/api/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, reason) ->
                                error(json, response, 401, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, reason) -> {
                            var auth = org.springframework.security.core.context.SecurityContextHolder
                                    .getContext().getAuthentication();
                            boolean guest = auth == null ||
                                    auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken;
                            var path = request.getServletPath();
                            boolean businessRequest = path.startsWith("/api/orders")
                                    || path.startsWith("/api/order-imports")
                                    || path.startsWith("/api/tasks") || path.startsWith("/api/documents");
                            if (guest && businessRequest) {
                                error(json, response, 401, "UNAUTHENTICATED", "请先登录");
                            } else {
                                error(json, response, 403, "FORBIDDEN", "请求校验失败，请刷新页面后重试");
                            }
                        }))
                .build();
    }

    private void error(ObjectMapper json, HttpServletResponse response, int status, String code,
                       String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), Map.of("code", code, "message", message));
    }
}
