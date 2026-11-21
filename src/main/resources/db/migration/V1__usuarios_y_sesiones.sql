create table usuarios (
    id uuid primary key,
    nombre varchar(150) not null,
    correo varchar(100) not null unique,
    password_hash varchar(60) not null,
    rol varchar(10) not null check (rol in ('CLIENTE', 'ADMIN', 'VENDEDOR')),
    telefono varchar(30),
    direccion varchar(200),
    region varchar(100),
    comuna varchar(100)
);

create table sesiones (
    token_hash varchar(64) primary key,
    usuario_id uuid not null references usuarios(id) on delete cascade,
    vence timestamp with time zone not null
);
create index sesiones_usuario_idx on sesiones(usuario_id);
create index sesiones_vence_idx on sesiones(vence);

