# DeliveryApp · Usuarios y Autenticación

Backend de cuentas y autenticación de DeliveryApp, desarrollado con Spring Boot y PostgreSQL. La API REST gestiona el registro, el inicio de sesión, la renovación de tokens, los perfiles y la administración de usuarios. El servicio de Tienda valida los JWT emitidos por esta API. Los endpoints se documentan en Swagger.

## Iniciar en desarrollo

Requisitos: Java 21 o superior, Maven, PostgreSQL y OpenSSL para generar el par RSA. Los comandos se ejecutan desde la raíz del proyecto. El Dockerfile permite compilar y ejecutar la aplicación en un contenedor.

El servicio requiere una base PostgreSQL y un usuario con acceso. El siguiente comando inicia una base local en Docker:

```powershell
docker run --name delivery-postgres -e POSTGRES_USER=delivery_users -e POSTGRES_PASSWORD=ClaveLocalSoloDemo -e POSTGRES_DB=delivery_users -p 127.0.0.1:5432:5432 -v delivery-usuarios-db:/var/lib/postgresql/data -d postgres:16
```

El par RSA se genera una vez. La clave privada se almacena fuera del repositorio:

```powershell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
```

Los comandos de OpenSSL también se pueden ejecutar en Git Bash si OpenSSL está disponible. Configuración y arranque en PowerShell:

```powershell
$env:DB_PASSWORD = 'ClaveLocalSoloDemo'
$env:COOKIE_SECURE = 'false'
$env:ADMIN_EMAIL = 'admin@duoc.cl'
$env:ADMIN_PASSWORD = 'CambiaEstaClaveLocal'
mvn spring-boot:run
```

La documentación interactiva está disponible en [Swagger UI](http://localhost:8081/swagger-ui/index.html). Flyway crea las tablas `usuarios` y `sesiones`. Las variables `ADMIN_EMAIL` y `ADMIN_PASSWORD` crean el administrador inicial una vez. Cambiar la variable después de crearlo no cambia su contraseña en PostgreSQL.

## Contrato de la API

| Método | Ruta | Permiso |
| --- | --- | --- |
| POST | `/api/auth/register` | Público. Recibe `nombre`, `correo`, `password`. Crea un cliente. |
| POST | `/api/auth/login` | Público. Recibe `correo`, `password`. Devuelve un JWT y emite la cookie. |
| POST | `/api/auth/refresh` | Cookie de sesión. Devuelve un JWT y rota la cookie. |
| POST | `/api/auth/logout` | Revoca la cookie presentada y la elimina del navegador. |
| GET | `/api/auth/jwks` | Público. Clave de verificación en formato JWKS. |
| GET | `/api/auth/health` | Público. Comprobación HTTP de que la aplicación responde. |
| GET | `/api/users/me` | JWT. Consulta de perfil. |
| PATCH | `/api/users/me` | JWT. Envía el formulario completo: `nombre`, `telefono`, `direccion`, `region`, `comuna`. Los campos de contacto omitidos quedan vacíos. |
| GET, POST | `/api/admin/users` | ADMIN. Listado paginado y creación. |
| GET, PUT, DELETE | `/api/admin/users/{id}` | ADMIN. Consulta, actualización y eliminación. |

El registro conserva los dominios de DeliveryApp (`duoc.cl`, `profesor.duoc.cl` y `gmail.com`). El mínimo del backend es de 8 caracteres y BCrypt admite hasta 72 bytes. La integración de React requiere actualizar su validación anterior de 4 a 10 caracteres y retirar la cuenta local de demostración. Las cuentas y pedidos antiguos del navegador no se importan automáticamente.

El alta desde administración recibe `nombre`, `correo`, `password` y `rol` (`ADMIN`, `CLIENTE` o `VENDEDOR`). Durante la edición se puede omitir `password` y conservar la actual. El administrador conectado no puede eliminarse ni quitarse su propio rol. Las respuestas nunca incluyen contraseñas o hashes. El listado devuelve `usuarios`, `pagina` y `haySiguiente`, con parámetros `pagina` (base cero) y `cantidad` (máximo 100).

## Integración con React y Tienda

1. Las solicitudes de login, refresh y logout incluyen la cabecera `X-Delivery-Session: 1` y la opción `credentials: 'include'` de `fetch`. Login requiere además `Content-Type: application/json`. En Swagger se ingresa `1` en la cabecera solicitada.
2. Login y refresh devuelven `accessToken`, `tokenType`, `expiresIn` y `usuario`. React mantiene el token de acceso en memoria y envía `Authorization: Bearer <token>` en las rutas protegidas. En Swagger el token se ingresa en **Authorize**.
3. Al cargar la aplicación, React llama una vez a refresh. Una respuesta 401 indica que no hay una sesión activa. Las solicitudes simultáneas de una misma pestaña comparten una petición de renovación para evitar rotaciones duplicadas.
4. Las pantallas protegidas se habilitan según `usuario.rol`. El React original utiliza las etiquetas `Cliente`, `Admin` y `Vendedor`. La integración adapta esas comparaciones a los roles de la API.
5. El backend de Tienda utiliza Spring Security como servidor de recursos y verifica firma RS256, emisor `delivery-usuarios` (o el valor acordado de `JWT_ISSUER`), audiencia `deliveryapp` y expiración. La URI JWKS apunta a `/api/auth/jwks` de Usuarios. El claim `sub` es el UUID del usuario y `rol` contiene `CLIENTE`, `ADMIN` o `VENDEDOR`. Vendedor no tiene los permisos de administrador. Tienda debe asociar el pedido al `sub` verificado.

El JWT dura 10 minutos. La sesión de renovación tiene una vigencia máxima de 7 días desde el login. PostgreSQL almacena únicamente el hash de esa credencial. Al cerrar sesión se revoca la renovación. Un JWT ya emitido puede seguir siendo válido en Tienda hasta vencer. Esta API comprueba también el rol actual en base de datos al administrar usuarios.

## AWS

El servicio escucha en `PORT` (por defecto `8081`). La conexión a PostgreSQL utiliza `DB_URL`, `DB_USER` y `DB_PASSWORD`. Las claves se montan en las rutas indicadas por `JWT_PRIVATE_KEY` y `JWT_PUBLIC_KEY` (por ejemplo `file:/run/keys/private.pem`). El entorno HTTPS utiliza `COOKIE_SECURE=true`. `FRONTEND_ORIGIN` contiene el origen exacto de React, sin barra final. Para que funcione la cookie `SameSite=Lax`, React y Usuarios se sirven bajo el mismo sitio, preferentemente con un proxy HTTPS que enrute `/api/auth`, `/api/users` y `/api/admin/users` a este servicio. Ambas aplicaciones Spring Boot pueden seguir desplegadas por separado.

## Pruebas

```powershell
mvn clean verify
```

La suite de integración levanta Spring, aplica la migración sobre H2 en modo PostgreSQL por defecto y comprueba registro, perfil, permisos, rotación y cierre de sesión, CRUD de administración, documentación OpenAPI y JWKS. La verificación del despliegue se realiza por HTTPS con el frontend conectado y la base PostgreSQL del entorno.


Para ejecutar la misma suite sobre PostgreSQL, define `TEST_DB_URL`, `TEST_DB_USER` y `TEST_DB_PASSWORD` antes de ejecutar Maven. La suite requiere una base de pruebas vacía y desechable. Las pruebas crean y modifican usuarios de prueba.
