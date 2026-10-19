package com.shijie.resumehelper.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.auth.security.AuthTokenFilter;
import com.shijie.resumehelper.auth.security.PasswordHasher;
import com.shijie.resumehelper.auth.security.TokenService;
import com.shijie.resumehelper.auth.service.AuthService;
import com.shijie.resumehelper.auth.store.FileSystemUserStore;
import com.shijie.resumehelper.auth.store.UserStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 认证模块装配。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    @Bean
    public UserStore userStore(AuthProperties properties, ObjectMapper mapper) {
        return new FileSystemUserStore(properties, mapper);
    }

    @Bean
    public PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    @Bean
    public TokenService tokenService(AuthProperties properties, ObjectMapper mapper) {
        return new TokenService(properties, mapper);
    }

    @Bean
    public AuthService authService(UserStore userStore,
                                   PasswordHasher passwordHasher,
                                   TokenService tokenService,
                                   AuthProperties properties) {
        return new AuthService(userStore, passwordHasher, tokenService, properties);
    }

    @Bean
    public AuthTokenFilter authTokenFilter(TokenService tokenService, AuthProperties properties) {
        return new AuthTokenFilter(tokenService, properties);
    }
}
