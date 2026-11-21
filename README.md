# DeliveryApp · Usuarios y Autenticación

API independiente para la Evaluación 3. Spring Boot, PostgreSQL y Swagger. Gestiona las cuentas, el perfil que usa el checkout, el CRUD de usuarios y la autenticación que comparte con Tienda.

## Iniciar en desarrollo

Necesitas Java 21 o superior, Maven, PostgreSQL y OpenSSL para generar el par RSA. Ejecuta los comandos desde la raíz del proyecto. También puedes usar el Dockerfile para compilar y ejecutar la aplicación en un contenedor.

Crea una base PostgreSQL y un usuario de aplicación con acceso a ella. Si usas Docker para la base local, por ejemplo:

```powershell
docker run --name delivery-postgres -e POSTGRES_USER=delivery_users -e POSTGRES_PASSWORD=ClaveLocalSoloDemo -e POSTGRES_DB=delivery_users -p 127.0.0.1:5432:5432 -v delivery-usuarios-db:/var/lib/postgresql/data -d postgres:16
```

Genera las claves una vez y conserva la clave privada fuera del repositorio:

```powershell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
```

Puedes ejecutar los mismos comandos de OpenSSL en Git Bash si tu instalación de Git lo incluye. Luego, en PowerShell:

```powershell
$env:DB_PASSWORD = 'ClaveLocalSoloDemo'
$env:COOKIE_SECURE = 'false'
$env:ADMIN_EMAIL = 'admin@duoc.cl'
$env:ADMIN_PASSWORD = 'CambiaEstaClaveLocal'
mvn spring-boot:run
```

Abre [Swagger UI](http://localhost:8081/swagger-ui/index.html). Flyway crea las tablas `usuarios` y `sesiones`. Las variables `ADMIN_EMAIL` y `ADMIN_PASSWORD` crean el administrador inicial una vez. Cambiar la variable después de crearlo no cambia su contraseña en PostgreSQL.

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

El registro conserva los dominios de DeliveryApp (`duoc.cl`, `profesor.duoc.cl` y `gmail.com`). El mínimo del backend es de 8 caracteres y BCrypt admite hasta 72 bytes. Ajusta el formulario React, que originalmente validaba 4 a 10 caracteres. Elimina la cuenta local de demostración cuando integres la API. Las cuentas y pedidos antiguos del navegador no se importan automáticamente.

Para alta desde administración, envía `nombre`, `correo`, `password` y `rol` (`ADMIN`, `CLIENTE` o `VENDEDOR`). Para editar puedes omitir `password` y conservar la actual. El administrador conectado no puede eliminarse ni quitarse su propio rol. Las respuestas nunca incluyen contraseñas o hashes. El listado devuelve `usuarios`, `pagina` y `haySiguiente`, con parámetros `pagina` (base cero) y `cantidad` (máximo 100).

## Integración con React y Tienda

1. En login, refresh y logout envía la cabecera `X-Delivery-Session: 1` y la opción `credentials: 'include'` de `fetch`. Para login envía además `Content-Type: application/json`. En Swagger escribe `1` en la cabecera solicitada.
2. Login y refresh devuelven `accessToken`, `tokenType`, `expiresIn` y `usuario`. Guarda el token de acceso en memoria y usa `Authorization: Bearer <token>` en las rutas protegidas. En Swagger pega el token en **Authorize**.
3. Al cargar React, llama una vez a refresh. Si devuelve 401, muestra la sesión cerrada. Comparte una petición de renovación entre solicitudes simultáneas en una misma pestaña para no intentar rotar dos veces la misma cookie.
4. Usa `usuario.rol` para mostrar pantallas protegidas. El React original utiliza las etiquetas `Cliente`, `Admin` y `Vendedor`. Actualiza las comparaciones o mapea los roles al adaptar el frontend.
5. Tienda configura Spring Security como servidor de recursos y verifica firma RS256, emisor `delivery-usuarios` (o el valor acordado de `JWT_ISSUER`), audiencia `deliveryapp` y expiración. Usa `/api/auth/jwks` de Usuarios como URI JWKS. El claim `sub` es el UUID del usuario y `rol` contiene `CLIENTE`, `ADMIN` o `VENDEDOR`. Vendedor no tiene los permisos de administrador. Tienda debe asociar el pedido al `sub` verificado.

El JWT dura 10 minutos. La sesión de renovación tiene una vigencia máxima de 7 días desde el login. PostgreSQL almacena únicamente el hash de esa credencial. Al cerrar sesión se revoca la renovación. Un JWT ya emitido puede seguir siendo válido en Tienda hasta vencer. Esta API comprueba también el rol actual en base de datos al administrar usuarios.

## AWS

El servicio escucha en `PORT` (por defecto `8081`). Configura `DB_URL`, `DB_USER` y `DB_PASSWORD` para tu PostgreSQL y monta las claves en rutas configuradas por `JWT_PRIVATE_KEY` y `JWT_PUBLIC_KEY` (por ejemplo `file:/run/keys/private.pem`). Mantén `COOKIE_SECURE=true` con HTTPS y declara el origen exacto del frontend en `FRONTEND_ORIGIN`, sin barra final. Para que funcione la cookie `SameSite=Lax`, sirve React y Usuarios bajo el mismo sitio, preferentemente con un proxy HTTPS que enrute `/api/auth`, `/api/users` y `/api/admin/users` a este servicio. Ambas aplicaciones Spring Boot pueden seguir desplegadas por separado.

## Pruebas

```powershell
mvn clean verify
```

La suite de integración levanta Spring, aplica la migración sobre H2 en modo PostgreSQL por defecto y comprueba registro, perfil, permisos, rotación y cierre de sesión, CRUD de administración, documentación OpenAPI y JWKS. Para la evidencia en AWS repite el flujo por HTTPS con la base PostgreSQL desplegada y el frontend conectado.


Para ejecutar la misma suite sobre PostgreSQL, define `TEST_DB_URL`, `TEST_DB_USER` y `TEST_DB_PASSWORD` antes de ejecutar Maven. Usa una base desechable y vacía. Las pruebas crean y modifican usuarios de prueba.
