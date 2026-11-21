package cl.deliveryapp.usuarios;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private static final String COOKIE = "delivery_refresh";
    private static final String SESSION_HEADER = "X-Delivery-Session";

    private final UsuariosService usuarios;
    private final RSAKey firma;
    private final boolean cookieSecure;

    AuthController(UsuariosService usuarios, RSAKey firma, @Value("${app.cookie-secure}") boolean cookieSecure) {
        this.usuarios = usuarios;
        this.firma = firma;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UsuarioDto.Vista registrar(@Valid @RequestBody UsuarioDto.Registro datos) {
        return usuarios.registrar(datos);
    }

    @PostMapping("/login")
    ResponseEntity<UsuarioDto.Acceso> entrar(
            @RequestHeader(SESSION_HEADER) String solicitud, @Valid @RequestBody UsuarioDto.Login datos) {
        verificarSolicitud(solicitud);
        return responder(usuarios.entrar(datos));
    }

    @PostMapping("/refresh")
    ResponseEntity<UsuarioDto.Acceso> renovar(@RequestHeader(SESSION_HEADER) String solicitud,
            @CookieValue(name = COOKIE, required = false) String token) {
        verificarSolicitud(solicitud);
        return responder(usuarios.renovar(token));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> salir(@RequestHeader(SESSION_HEADER) String solicitud,
            @CookieValue(name = COOKIE, required = false) String token) {
        verificarSolicitud(solicitud);
        usuarios.salir(token);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    @GetMapping("/jwks")
    Map<String, Object> claves() {
        return new JWKSet(firma.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/health")
    Map<String, String> estado() {
        return Map.of("status", "UP");
    }

    private ResponseEntity<UsuarioDto.Acceso> responder(UsuariosService.Credenciales credenciales) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(credenciales.renovacion(), credenciales.duracionCookie()))
                .body(credenciales.acceso());
    }

    private String cookie(String valor, Duration duracion) {
        return ResponseCookie.from(COOKIE, valor).httpOnly(true).secure(cookieSecure)
                .sameSite("Lax").path("/api/auth").maxAge(duracion.isNegative() ? Duration.ZERO : duracion)
                .build().toString();
    }

    private static void verificarSolicitud(String solicitud) {
        if (!"1".equals(solicitud)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usa la cabecera X-Delivery-Session: 1.");
        }
    }
}
