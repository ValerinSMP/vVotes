# vVotes — memoria de desarrollo

Lee también `AGENTS.md` antes de modificar el repositorio.

## Rol

Plugin pequeño adecuado para validar el stack común antes de abordar ValerinUtils.

## Base actual

- Java 21, Gradle Kotlin DSL 9.1.0 y un único artefacto Paper.
- API mínima Paper 1.21.11.
- SQLite sombreado, VotifierPlus y PlaceholderAPI.
- JUnit 5 cubre configuración de servicios de voto y formato de contadores.
- Los módulos Maven y el adapter Arclight heredados fueron retirados.
- Compilación y smoke test Paper 1.21.11 completados.
- La línea moderna comienza en `1.0.0`. Gradle es la fuente canónica y
  `plugin.yml` recibe la versión durante `processResources`.

## Trabajo prioritario

1. Capturar metas, rachas, votos offline y sorteos en tests.
2. Asegurar idempotencia ante eventos de voto repetidos.
3. Inyectar reloj y zona horaria para probar cambios de periodo.
4. Conservar bytecode Java 21 y probar también la última versión estable de Paper.

Los comandos de recompensa son una frontera de alto riesgo: un retry no puede
entregar el premio dos veces.
