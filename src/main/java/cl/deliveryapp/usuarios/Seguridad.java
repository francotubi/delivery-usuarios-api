package cl.deliveryapp.usuarios;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import java.io.IOException;
import java.security.interfaces.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
@SecurityScheme(name = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class Seguridad {
    @Bean
    RSAKey firma(@Value("${app.jwt.private-key}") Resource privada,
                 @Value("${app.jwt.public-key}") Resource publica) throws IOException {
        try (var privateInput = privada.getInputStream(); var publicInput = publica.getInputStream()) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(privateInput);
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(publicInput);
            if (!publicKey.getModulus().equals(privateKey.getModulus()) || publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("Configura un par RSA válido de al menos 2048 bits.");
            }
            var jwk = new RSAKey.Builder(publicKey).privateKey(privateKey)
                    .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).build();
            try {
                return new RSAKey.Builder(jwk).keyID(jwk.computeThumbprint().toString()).build();
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new IllegalStateException("No se pudo calcular el identificador de la clave.", e);
            }
        }
    }

    @Bean
    JwtEncoder encoder(RSAKey firma) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(firma)));
    }

    @Bean
    JwtDecoder decoder(RSAKey firma, @Value("${app.jwt.issuer}") String issuer,
                       @Value("${app.jwt.audience}") String audience) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(firma.toRSAPublicKey()).build();
        var validacion = new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                validacion));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource cors(@Value("${app.frontend-origin}") String origin) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Delivery-Session"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource cors) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("rol");
        roles.setAuthorityPrefix("ROLE_");
        var jwt = new JwtAuthenticationConverter();
        jwt.setJwtGrantedAuthoritiesConverter(roles);

        // La cookie solo se usa en rutas que exigen una cabecera personalizada y CORS restringido.
        return http.cors(config -> config.configurationSource(cors))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(config -> config
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/jwks", "/api/auth/health",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(config -> config.jwt(options -> options.jwtAuthenticationConverter(jwt)))
                .build();
    }
}
