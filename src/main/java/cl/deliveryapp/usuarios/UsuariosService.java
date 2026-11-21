package cl.deliveryapp.usuarios;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.*;

@Service
class UsuariosService {
    static final Duration VIDA_SESION = Duration.ofDays(7);
    private static final Duration VIDA_ACCESO = Duration.ofMinutes(10);

    private final UsuariosRepository usuarios;
    private final SesionesRepository sesiones;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwt;
    private final String issuer;
    private final String audience;
    private final SecureRandom random = new SecureRandom();
    private final String hashFicticio;

    UsuariosService(UsuariosRepository usuarios, SesionesRepository sesiones, PasswordEncoder passwords,
                    JwtEncoder jwt, @Value("${app.jwt.issuer}") String issuer,
                    @Value("${app.jwt.audience}") String audience) {
        this.usuarios = usuarios;
        this.sesiones = sesiones;
        this.passwords = passwords;
        this.jwt = jwt;
        this.issuer = issuer;
        this.audience = audience;
        this.hashFicticio = passwords.encode(UUID.randomUUID().toString());
    }

    record Credenciales(UsuarioDto.Acceso acceso, String renovacion, Duration duracionCookie) {}

    @Transactional
    public UsuarioDto.Vista registrar(UsuarioDto.Registro datos) {
        return crear(datos.nombre(), datos.correo(), datos.password(), Rol.CLIENTE);
    }

    @Transactional
    public Credenciales entrar(UsuarioDto.Login datos) {
        String correo = normalizar(datos.correo());
        Usuario usuario = usuarios.buscarId(correo).flatMap(usuarios::bloquear).orElse(null);
        String hash = usuario == null ? hashFicticio : usuario.passwordHash;
        if (datos.password().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(datos.password(), hash)
                || usuario == null || !usuario.correo.equals(correo)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Correo o contraseña incorrectos.");
        }
        return emitir(usuario, Instant.now().plus(VIDA_SESION));
    }

    @Transactional
    public Credenciales renovar(String token) {
        String hash = hashToken(token);
        UUID id = sesiones.buscarUsuario(hash).orElseThrow(UsuariosService::sesionInvalida);
        Usuario usuario = usuarios.bloquear(id).orElseThrow(UsuariosService::sesionInvalida);
        Instant ahora = Instant.now();
        Instant vence = sesiones.findById(hash).map(s -> s.vence).orElseThrow(UsuariosService::sesionInvalida);
        if (sesiones.consumir(hash, ahora) != 1) {
            throw sesionInvalida();
        }
        return emitir(usuario, vence);
    }

    @Transactional
    public void salir(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return;
        }
        String hash = hashToken(token);
        sesiones.buscarUsuario(hash).flatMap(usuarios::bloquear)
                .ifPresent(usuario -> sesiones.consumir(hash, Instant.now()));
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void limpiarSesiones() {
        sesiones.limpiar(Instant.now());
    }

    @Transactional(readOnly = true)
    public UsuarioDto.Vista perfil(UUID id) {
        return UsuarioDto.Vista.de(usuarios.findById(id).orElseThrow(UsuariosService::sesionInvalida));
    }

    @Transactional
    public UsuarioDto.Vista editarPerfil(UUID id, UsuarioDto.Perfil perfil) {
        Usuario usuario = usuarios.bloquear(id).orElseThrow(UsuariosService::sesionInvalida);
        aplicarPerfil(usuario, perfil);
        return UsuarioDto.Vista.de(usuario);
    }

    @Transactional(readOnly = true)
    public Slice<UsuarioDto.Vista> listar(UUID actor, int pagina, int cantidad) {
        verificarAdmin(usuarios.findById(actor));
        return usuarios.findAll(PageRequest.of(pagina, cantidad, Sort.by("nombre", "id")))
                .map(UsuarioDto.Vista::de);
    }

    @Transactional(readOnly = true)
    public UsuarioDto.Vista ver(UUID actor, UUID id) {
        verificarAdmin(usuarios.findById(actor));
        return UsuarioDto.Vista.de(usuarios.findById(id).orElseThrow(UsuariosService::usuarioInexistente));
    }

    @Transactional
    public UsuarioDto.Vista crearDesdeAdmin(UUID actor, UsuarioDto.Admin datos) {
        verificarAdmin(usuarios.bloquear(actor));
        Usuario usuario = nuevo(datos.nombre(), datos.correo(), datos.password(), datos.rol());
        aplicarPerfil(usuario, perfilAdmin(datos));
        return UsuarioDto.Vista.de(usuarios.saveAndFlush(usuario));
    }

    @Transactional
    public UsuarioDto.Vista actualizar(UUID actor, UUID id, UsuarioDto.Admin datos) {
        verificarAdmin(usuarios.bloquear(actor));
        Usuario usuario = usuarios.bloquear(id).orElseThrow(UsuariosService::usuarioInexistente);
        if (actor.equals(id) && datos.rol() != Rol.ADMIN) {
            throw new ResponseStatusException(CONFLICT, "No puedes quitarte tu propio rol de administrador.");
        }
        String correo = normalizar(datos.correo());
        boolean cambiaCorreo = !usuario.correo.equals(correo);
        if (cambiaCorreo && usuarios.existsByCorreo(correo)) {
            throw correoDuplicado();
        }
        if (cambiaCorreo || usuario.rol != datos.rol() || datos.password() != null) {
            sesiones.revocarUsuario(id);
        }
        usuario.correo = correo;
        usuario.rol = datos.rol();
        if (datos.password() != null) {
            usuario.passwordHash = codificar(datos.password());
        }
        aplicarPerfil(usuario, perfilAdmin(datos));
        usuarios.flush();
        return UsuarioDto.Vista.de(usuario);
    }

    @Transactional
    public void eliminar(UUID actor, UUID id) {
        verificarAdmin(usuarios.bloquear(actor));
        if (actor.equals(id)) {
            throw new ResponseStatusException(CONFLICT, "No puedes eliminar tu propia cuenta.");
        }
        usuarios.delete(usuarios.bloquear(id).orElseThrow(UsuariosService::usuarioInexistente));
    }

    @Bean
    static ApplicationRunner administradorInicial(UsuariosService service,
            @Value("${app.admin.email}") String correo, @Value("${app.admin.password}") String password) {
        return args -> {
            if (!correo.isBlank() || !password.isBlank()) {
                service.inicializarAdmin(correo, password);
            }
        };
    }

    @Transactional
    public void inicializarAdmin(String correo, String password) {
        String normalizado = normalizar(correo);
        if (normalizado.length() > 100 || !normalizado.matches(UsuarioDto.DOMINIOS)) {
            throw new IllegalArgumentException("ADMIN_EMAIL debe usar un dominio admitido.");
        }
        if (usuarios.existsByCorreo(normalizado)) {
            if (usuarios.findByCorreo(normalizado).orElseThrow().rol != Rol.ADMIN) {
                throw new IllegalStateException("El correo configurado ya pertenece a un cliente.");
            }
            return;
        }
        crear("Administrador", normalizado, password, Rol.ADMIN);
    }

    private Credenciales emitir(Usuario usuario, Instant vence) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sesiones.save(new Sesion(hashToken(token), usuario.id, vence));

        Instant ahora = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(usuario.id.toString()).issuedAt(ahora).expiresAt(ahora.plus(VIDA_ACCESO))
                .claim("rol", usuario.rol.name()).build();
        var headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String acceso = jwt.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
        return new Credenciales(new UsuarioDto.Acceso(acceso, "Bearer", VIDA_ACCESO.toSeconds(),
                UsuarioDto.Vista.de(usuario)), token, Duration.between(ahora, vence));
    }

    private UsuarioDto.Vista crear(String nombre, String correo, String password, Rol rol) {
        return UsuarioDto.Vista.de(usuarios.saveAndFlush(nuevo(nombre, correo, password, rol)));
    }

    private Usuario nuevo(String nombre, String correo, String password, Rol rol) {
        String normalizado = normalizar(correo);
        if (usuarios.existsByCorreo(normalizado)) {
            throw correoDuplicado();
        }
        Usuario usuario = new Usuario();
        usuario.nombre = nombre.trim();
        usuario.correo = normalizado;
        usuario.passwordHash = codificar(password);
        usuario.rol = rol;
        return usuario;
    }

    private String codificar(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(BAD_REQUEST, "Usa al menos 8 caracteres y un máximo de 72 bytes.");
        }
        return passwords.encode(password);
    }

    private static void aplicarPerfil(Usuario u, UsuarioDto.Perfil p) {
        u.nombre = p.nombre().trim();
        u.telefono = p.telefono();
        u.direccion = p.direccion();
        u.region = p.region();
        u.comuna = p.comuna();
    }

    private static UsuarioDto.Perfil perfilAdmin(UsuarioDto.Admin datos) {
        return new UsuarioDto.Perfil(datos.nombre(), datos.telefono(), datos.direccion(), datos.region(), datos.comuna());
    }

    private static void verificarAdmin(Optional<Usuario> actor) {
        if (actor.filter(u -> u.rol == Rol.ADMIN).isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN, "Se requiere el rol de administrador.");
        }
    }

    private static String normalizar(String correo) {
        return correo.trim().toLowerCase(Locale.ROOT);
    }

    private static String hashToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw sesionInvalida();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException sesionInvalida() {
        return new ResponseStatusException(UNAUTHORIZED, "Sesión inválida o vencida.");
    }

    private static ResponseStatusException usuarioInexistente() {
        return new ResponseStatusException(NOT_FOUND, "Usuario no encontrado.");
    }

    private static ResponseStatusException correoDuplicado() {
        return new ResponseStatusException(CONFLICT, "Ya existe una cuenta con ese correo.");
    }
}

