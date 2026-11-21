package cl.deliveryapp.usuarios;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "usuarios")
class Usuario {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(nullable = false, length = 150)
    String nombre;

    @Column(nullable = false, unique = true, length = 100)
    String correo;

    @Column(nullable = false, length = 60)
    String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    Rol rol;

    @Column(length = 30)
    String telefono;
    @Column(length = 200)
    String direccion;
    @Column(length = 100)
    String region;
    @Column(length = 100)
    String comuna;

    protected Usuario() {}
}
