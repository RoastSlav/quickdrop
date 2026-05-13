package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for the application.
 *
 * <p>When {@code appPasswordEnabled} is set in application settings, all routes
 * except the login page, static assets, and share-download endpoints require
 * form-based authentication. When the setting is disabled, all requests are
 * permitted without authentication.
 *
 * <p>The {@link SecurityFilterChain} bean is {@link RefreshScope}-scoped so that
 * toggling the app password in the admin settings panel takes effect without a
 * restart.
 *
 * <p>CSRF protection uses a cookie-based token repository (readable by JavaScript)
 * to support the htmx/Alpine.js frontend. The {@code X-Frame-Options} header is
 * disabled and replaced with a permissive {@code Content-Security-Policy:
 * frame-ancestors *} to allow embedding.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);
    private final ApplicationSettingsService applicationSettingsService;

    public SecurityConfig(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    /**
     * Builds the security filter chain. When the app password is enabled, only the
     * listed public endpoints are accessible without authentication; all others
     * redirect to the login page.
     *
     * @param http the Spring Security HTTP builder
     * @return the configured filter chain
     * @throws Exception if the security configuration cannot be applied
     */
    @Bean
    @RefreshScope
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (applicationSettingsService.isAppPasswordEnabled()) {
            http.authorizeHttpRequests(authz -> authz
                    .requestMatchers(
                            "/password/login",
                            "/favicon.ico",
                            "/error",
                            "/share/**",
                            "/file/share/**",
                            "/api/file/download/**",
                            "/css/**",
                            "/js/**",
                            "/images/**",
                            "/webjars/**"
                    ).permitAll()
                    .anyRequest().authenticated()
            ).formLogin(form -> form
                    .loginPage("/password/login")
                    .permitAll()
                    .failureUrl("/password/login?error")
                    .defaultSuccessUrl("/", true)
            ).authenticationProvider(authenticationProvider()
            );
        } else {
            http.authorizeHttpRequests(authz -> authz
                    .anyRequest().permitAll());
        }

        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        ).headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors *;"))
        ).cors(Customizer.withDefaults());

        return http.build();
    }

    /**
     * CORS configuration that allows all origins and the standard request headers.
     * Credentials are allowed so that cookie-based sessions work cross-origin.
     *
     * @return the CORS configuration source
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOriginPattern("*");
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-CSRF-TOKEN", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Content-Disposition"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Authentication provider that validates the site-wide app password stored as a
     * BCrypt hash in the application settings.
     *
     * @return the authentication provider
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                String providedPassword = (String) authentication.getCredentials();
                if (BCrypt.checkpw(providedPassword, applicationSettingsService.getAppPasswordHash())) {
                    logger.info("Valid login - {}", authentication.getDetails());
                    return new UsernamePasswordAuthenticationToken("appUser", providedPassword, List.of());
                } else {
                    logger.warn("Invalid password provided - {}", authentication.getDetails());
                    throw new BadCredentialsException("Invalid password");
                }
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
            }
        };
    }

    /**
     * Password encoder used for hashing and verifying file-level access passwords.
     *
     * @return BCrypt password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
