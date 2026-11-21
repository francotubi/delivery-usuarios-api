package cl.deliveryapp.usuarios;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class UsuarioDto {
    static final String DOMINIOS = "(?i)^[^@\\s]+@(duoc\\.cl|profesor\\.duoc\\.cl|gmail\\.com)$";

    private UsuarioDto() {}

    public record Registro(
            @NotBlank @Size(max = 150) String nombre,
            @NotBlank @Email @Size(max = 100) @Pattern(regexp = DOMINIOS) String correo,
            @NotBlank @Size(min = 8, max = 72) String password) {}

    public record Login(
            @NotBlank @Email @Size(max = 100) String correo,
            @NotBlank @Size(max = 72) String password) {}

    public record Perfil(
            @NotBlank @Size(max = 150) String nombre,
            @Size(max = 30) String telefono,
            @Size(max = 200) String direccion,
            @Size(max = 100) String region,
            @Size(max = 100) String comuna) {}

    public record Admin(
            @NotBlank @Size(max = 150) String nombre,
            @NotBlank @Email @Size(max = 100) @Pattern(regexp = DOMINIOS) String correo,
            @Size(min = 8, max = 72) String password,
            @NotNull Rol rol,
            @Size(max = 30) String telefono,
            @Size(max = 200) String direccion,
            @Size(max = 100) String region,
            @Size(max = 100) String comuna) {}

    public record Vista(UUID id, String nombre, String correo, Rol rol, String telefono,
                        String direccion, String region, String comuna) {
        static Vista de(Usuario u) {
            return new Vista(u.id, u.nombre, u.correo, u.rol, u.telefono, u.direccion, u.region, u.comuna);
        }
    }

    public record Acceso(String accessToken, String tokenType, long expiresIn, Vista usuario) {}
}
