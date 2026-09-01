package com.hawkins.gallery.config;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain gallerySecurity(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/actuator/health").permitAll()
                        .requestMatchers("/assets/**", "/albums/**", "/faces/**", "/review/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(login -> login.defaultSuccessUrl("/", true))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService galleryUsers(
            PasswordEncoder encoder,
            @Value("${app.security.admin-username:gallery}") String username,
            @Value("${app.security.admin-password:}") String configuredPassword) {
        String password = configuredPassword == null || configuredPassword.isBlank()
                ? UUID.randomUUID().toString() : configuredPassword;
        if (configuredPassword == null || configuredPassword.isBlank()) {
            log.warn("No GALLERY_ADMIN_PASSWORD configured. Generated one-time password for '{}': {}", username, password);
        }
        var admin = User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
