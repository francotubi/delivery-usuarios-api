package cl.deliveryapp.usuarios;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name = "jwt")
class UsuariosController {
    private final UsuariosService usuarios;

    UsuariosController(UsuariosService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/api/users/me")
    UsuarioDto.Vista perfil(@AuthenticationPrincipal Jwt jwt) {
        return usuarios.perfil(UUID.fromString(jwt.getSubject()));
    }

    @PatchMapping("/api/users/me")
    UsuarioDto.Vista editarPerfil(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UsuarioDto.Perfil perfil) {
        return usuarios.editarPerfil(UUID.fromString(jwt.getSubject()), perfil);
    }

    record Pagina(List<UsuarioDto.Vista> usuarios, int pagina, boolean haySiguiente) {}

    @GetMapping("/api/admin/users")
    Pagina listar(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") @Min(0) int pagina,
                  @RequestParam(defaultValue = "25") @Min(1) @Max(100) int cantidad) {
        var resultado = usuarios.listar(UUID.fromString(jwt.getSubject()), pagina, cantidad);
        return new Pagina(resultado.getContent(), pagina, resultado.hasNext());
    }

    @GetMapping("/api/admin/users/{id}")
    UsuarioDto.Vista ver(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return usuarios.ver(UUID.fromString(jwt.getSubject()), id);
    }

    @PostMapping("/api/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    UsuarioDto.Vista crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UsuarioDto.Admin datos) {
        return usuarios.crearDesdeAdmin(UUID.fromString(jwt.getSubject()), datos);
    }

    @PutMapping("/api/admin/users/{id}")
    UsuarioDto.Vista actualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @Valid @RequestBody UsuarioDto.Admin datos) {
        return usuarios.actualizar(UUID.fromString(jwt.getSubject()), id, datos);
    }

    @DeleteMapping("/api/admin/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        usuarios.eliminar(UUID.fromString(jwt.getSubject()), id);
    }
}
