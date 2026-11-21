package cl.deliveryapp.usuarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:usuarios;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
        "app.admin.email=admin@duoc.cl", "app.admin.password=PruebaAdmin123"
})
@AutoConfigureMockMvc
class UsuariosIntegracionTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void claves(DynamicPropertyRegistry properties) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        properties.add("app.jwt.private-key", () -> pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        properties.add("app.jwt.public-key", () -> pem("PUBLIC KEY", keys.getPublic().getEncoded()));
    }

    private static String pem(String tipo, byte[] contenido) {
        try {
            Path path = Files.createTempFile("delivery-jwt-", ".pem");
            path.toFile().deleteOnExit();
            String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(contenido);
            Files.writeString(path, "-----BEGIN " + tipo + "-----\n" + base64 + "\n-----END " + tipo + "-----\n");
            return path.toUri().toString();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void clientePuedeEditarPerfilYRenovarUnaVezPorCookie() throws Exception {
        String datos = """
                {"nombre":"Ana","correo":"ANA@gmail.com","password":"ClaveAna123","rol":"ADMIN"}
                """;
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(datos))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rol").value("CLIENTE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(datos))
                .andExpect(status().isConflict());

        var login = login("ana@gmail.com", "ClaveAna123");
        String bearer = bearer(login);
        mvc.perform(patch("/api/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""" 
                                {"nombre":"Ana Pérez","region":"Metropolitana","comuna":"Santiago"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Ana Pérez"));
        mvc.perform(get("/api/admin/users").header("Authorization", bearer)).andExpect(status().isForbidden());

        Cookie anterior = cookieDe(login);
        mvc.perform(post("/api/auth/refresh").cookie(anterior)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/refresh").header("Origin", "https://otro.example")
                .header("X-Delivery-Session", "1").cookie(anterior)).andExpect(status().isForbidden());
        var renovacion = mvc.perform(post("/api/auth/refresh").header("X-Delivery-Session", "1").cookie(anterior))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/auth/refresh").header("X-Delivery-Session", "1").cookie(anterior))
                .andExpect(status().isUnauthorized());
        Cookie vigente = cookieDe(renovacion);
        mvc.perform(post("/api/auth/logout").header("X-Delivery-Session", "1").cookie(vigente))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").header("X-Delivery-Session", "1").cookie(vigente))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminPuedeCompletarCrudYSwaggerPublicaElContrato() throws Exception {
        String auth = bearer(login("admin@duoc.cl", "PruebaAdmin123"));
        String datos = """
                {"nombre":"Raúl","correo":"raul@duoc.cl","password":"ClaveRaul123","rol":"CLIENTE"}
                """;
        var alta = mvc.perform(post("/api/admin/users").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(datos))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(alta.getResponse().getContentAsString()).get("id").asText();
        String ruta = "/api/admin/users/" + id;
        mvc.perform(get(ruta).header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(put(ruta).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"nombre":"Raúl Soto","correo":"raul@duoc.cl","rol":"VENDEDOR"}
                                 """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Raúl Soto")).andExpect(jsonPath("$.rol").value("VENDEDOR"));
        mvc.perform(get("/api/admin/users").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usuarios").isArray());
        mvc.perform(delete(ruta).header("Authorization", auth)).andExpect(status().isNoContent());
        mvc.perform(get(ruta).header("Authorization", auth)).andExpect(status().isNotFound());

        var me = mvc.perform(get("/api/users/me").header("Authorization", auth)).andReturn();
        String adminId = json.readTree(me.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(delete("/api/admin/users/" + adminId).header("Authorization", auth))
                .andExpect(status().isConflict());

        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/admin/users']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.jwt.scheme").value("bearer"));
        mvc.perform(get("/api/auth/jwks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    private MvcResult login(String correo, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").header("X-Delivery-Session", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UsuarioDto.Login(correo, password))))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("delivery_refresh", true))
                .andExpect(cookie().secure("delivery_refresh", true)).andReturn();
    }

    private String bearer(MvcResult response) throws Exception {
        return "Bearer " + json.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static Cookie cookieDe(MvcResult response) {
        return response.getResponse().getCookie("delivery_refresh");
    }
}



