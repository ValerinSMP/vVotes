# AGENTS — guía rápida para agentes AI

Breve guía para que un agente AI sea productivo rápidamente en este repositorio (vVotes).

**Propósito del proyecto**: complemento de Minecraft (Purpur/Paper) que gestiona votos, recompensas y sorteos mensuales.

**Comandos de build / ejecución**
- Compilar: `mvn -DskipTests package`
- El JAR resultante: `target/vVotes-1.0.0.jar`
- El `pom.xml` tiene un `maven-antrun-plugin` que copia el jar al directorio de plugins configurado (`server.plugins.dir`) durante `package`.

**Entorno de ejecución**
- Java 21
- Servidor Purpur/Paper compatible con API 1.21.11
- Dependencias runtime esperadas en el servidor: `VotifierPlus`, `PlaceholderAPI` (opcional).

**Arquitectura y límites del código**
- Entrada principal: [src/main/java/com/valerinsmp/vvotes/VVotesPlugin.java](src/main/java/com/valerinsmp/vvotes/VVotesPlugin.java#L1)
- Comandos: carpeta `command/` (ej.: `VoteCommand`, `VoteAdminCommand`).
- Servicios core: `service/` (gestiona lógica de votos, sorteos, mensajería, sonidos).
- Acceso a DB: `db/DatabaseManager.java` (SQLite integrado).
- Configuración por defecto en `src/main/resources` (`config.yml`, `messages.yml`, `sound.yml`).

**Convenciones y consideraciones**
- Cambios en `config.yml`, `messages.yml` o `sound.yml` deben respetar la estructura de defaults; use `ensureYamlDefaults()` al recargar.
- Evitar cambios que requieran un servidor en caliente sin pruebas manuales — preferir pruebas locales en un servidor Paper/Purpur.
- El proyecto no incluye tests automáticos; los cambios que modifiquen comportamiento crítico deben validarse manualmente en servidor.

**Tareas recomendadas para un agente**
- Ejecutar `mvn -DskipTests package` y reportar errores de compilación.
- Buscar usos de API removidas al actualizar versión de Paper/Java.
- Proponer cambios mínimos y documentados en `config.yml` y `messages.yml` en caso necesario.
- No cambiar el `plugin.yml` sin verificar nombres de comandos y permisos.

**Referencias rápidas**
- README: [README.md](README.md#L1)
- POM (build/deploy): [pom.xml](pom.xml#L1)

---
Si quieres, puedo además crear `.github/copilot-instructions.md` con reglas extra (por ejemplo: rama de trabajo, nombre del artefacto, cómo probar localmente). ¿Lo genero ahora?
