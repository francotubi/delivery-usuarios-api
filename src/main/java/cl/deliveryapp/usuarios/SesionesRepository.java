package cl.deliveryapp.usuarios;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

interface SesionesRepository extends JpaRepository<Sesion, String> {
    @Query("select s.usuarioId from Sesion s where s.tokenHash = :hash")
    java.util.Optional<UUID> buscarUsuario(String hash);

    @Modifying
    @Query("delete from Sesion s where s.tokenHash = :hash and s.vence > :ahora")
    int consumir(String hash, Instant ahora);

    @Modifying
    @Query("delete from Sesion s where s.usuarioId = :id")
    void revocarUsuario(UUID id);

    @Modifying
    @Query("delete from Sesion s where s.vence <= :ahora")
    void limpiar(Instant ahora);
}
