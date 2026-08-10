package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for the application.
 *
 * <p>When {@code appPasswordEnabled} is set in application settings, all routes
 * except the login page, static assets, and share-download endpoints require
 * form-based authentication. When the setting is disabled, all requests are
 * permitted without authentication. This is re-evaluated on every request (not
 * baked in once at filter-chain build time) so a settings change takes effect
 * immediately, with no dependency on a config refresh rebuilding the chain.
 *
 * <p>CSRF protection uses a cookie-based token repository (readable by JavaScript).
 * {@code X-Frame-Options} is disabled; {@code Content-Security-Policy: frame-ancestors *}
 * is set instead.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);
    private final ApplicationSettingsService applicationSettingsService;

    @Value("${quickdrop.cors.allowed-origins:*}")
    private String corsAllowedOrigins;

    public SecurityConfig(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    /**
     * Whether the app password is required is decided per-request by
     * {@link #appPasswordGate()}, not baked in at build time, so toggling the setting
     * takes effect on the very next request.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                        "/branding/**",
                        "/webjars/**"
                ).permitAll()
                .anyRequest().access(appPasswordGate())
        ).formLogin(form -> form
                .loginPage("/password/login")
                .permitAll()
                .failureUrl("/password/login?error")
                .defaultSuccessUrl("/", true)
        ).authenticationProvider(authenticationProvider());

        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
        ).headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors *;"))
        ).cors(Customizer.withDefaults())
        // CsrfToken resolution is deferred by default: the XSRF-TOKEN cookie is only
        // written if something actually calls csrfToken.getToken() during the request.
        // Our responses stream as chunked (no Content-Length), so headers commit as
        // soon as the body starts flushing -- if the token isn't resolved until
        // Thymeleaf reaches it deep in the page, the response is already committed
        // and CookieCsrfTokenRepository's Set-Cookie silently never gets written,
        // leaving the JS client with no cookie to echo back and every state-changing
        // request (uploads included) failing CSRF validation with a 403. Force
        // eager resolution on every request so the cookie is always written up front.
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }

    /**
     * Permits every request when the app password is disabled, otherwise defers to the
     * standard authenticated-session check. Reads
     * {@link ApplicationSettingsService#isAppPasswordEnabled()} fresh on every request
     * rather than once at filter-chain construction time.
     */
    @Bean
    public AuthorizationManager<RequestAuthorizationContext> appPasswordGate() {
        AuthorizationManager<RequestAuthorizationContext> authenticated = AuthenticatedAuthorizationManager.authenticated();
        return (authentication, context) -> applicationSettingsService.isAppPasswordEnabled()
                ? authenticated.authorize(authentication, context)
                : new AuthorizationDecision(true);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        if ("*".equals(corsAllowedOrigins)) {
            // Wildcard origin is incompatible with credentials=true; use open/anonymous CORS.
            configuration.addAllowedOriginPattern("*");
            configuration.setAllowCredentials(false);
        } else {
            for (String origin : corsAllowedOrigins.split(",")) {
                configuration.addAllowedOrigin(origin.trim());
            }
            configuration.setAllowCredentials(true);
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-CSRF-TOKEN", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Content-Disposition"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Validates the site-wide app password stored as a BCrypt hash in application settings. */
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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Forces the deferred {@link CsrfToken} to resolve on every request, guaranteeing
     * {@link CookieCsrfTokenRepository} writes the XSRF-TOKEN cookie before the response
     * can commit. See the comment on {@link #securityFilterChain} for why this is needed.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
