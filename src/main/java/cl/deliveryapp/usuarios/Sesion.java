package cl.deliveryapp.usuarios;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sesiones")
class Sesion {
    @Id
    @Column(length = 64)
    String tokenHash;

    @Column(nullable = false)
    UUID usuarioId;

    @Column(nullable = false)
    Instant vence;

    protected Sesion() {}

    Sesion(String tokenHash, UUID usuarioId, Instant vence) {
        this.tokenHash = tokenHash;
        this.usuarioId = usuarioId;
        this.vence = vence;
    }
}
