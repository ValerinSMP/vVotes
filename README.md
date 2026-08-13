<div align="center">

# vVotes

### Votos, recompensas y metas para la comunidad de ValerinSMP

[![Paper](https://img.shields.io/badge/Paper-1.21.11%2B-222222?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-E76F00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/version-1.0.0-7B5CFA?style=for-the-badge)](https://github.com/ValerinSMP/vVotes)

[Características](#-características) • [Comandos](#-comandos) • [Configuración](#️-configuración) • [Operación segura](#-operación-segura) • [Desarrollo](#️-compilación)

</div>

**vVotes** conecta VotifierPlus con el sistema de progresión de ValerinSMP. Registra
votos, planifica recompensas durables y presenta metas globales diarias y metas
mensuales por jugador.

## ⭐ Características

- **⭐ Recompensas automáticas** — Ejecuta comandos configurables por cada voto válido.
- **⭐ Entrega offline** — Congela período y premios hasta la siguiente conexión exacta.
- **⭐ Metas comunitarias** — Progreso global diario y objetivos mensuales personales.
- **⭐ Sorteos y rankings** — Ganadores elegibles, historial y top del periodo actual.
- **⭐ Persistencia durable** — SQLite con WAL, deduplicación y journal de comandos.
- **⭐ Integración visual** — PlaceholderAPI para scoreboards, menús y estadísticas.

## 🎮 Comandos

| Comando | Descripción | Permiso |
| --- | --- | --- |
| `/vote` | Alias de ayuda, estadísticas e información. | `vvotes.use` |
| `/votestats` | Muestra estadísticas personales. | `vvotes.use` |
| `/vvotes help\|about\|stats\|toggle` | Comando público canónico. | `vvotes.use` |
| `/vvotesadmin help [página]` | Administra votos, metas, sorteos y grants ambiguos. | `vvotes.admin` |

`/voteadmin` se conserva como alias compatible.

## 🧩 Placeholders

- `%vvotes_total%`
- `%vvotes_daily%`
- `%vvotes_monthly%`
- `%vvotes_streak_monthly%`
- `%vvotes_global_daily%`
- `%vvotes_next_global_goal%`
- `%vvotes_next_monthly_goal%`
- `%vvotes_double_site_today_icon%`

## ⚙️ Configuración

- `config.yml`: recompensas, metas y zona horaria.
- `messages.yml`: textos MiniMessage y prefijo `<dark_gray>[</dark_gray><#34D399>ᴠᴏᴛᴇs</#34D399><dark_gray>]</dark_gray>`.
- `sound.yml`: sonidos de votos y anuncios.

Los cambios de ruta SQLite, timeout o zona horaria requieren reinicio. El resto se
valida como un candidato completo con `/vvotesadmin reload`; si alguna parte es
inválida, continúa activa la configuración anterior.

### Comandos obligatorios por servicio

`services.force-player-command` compara el `serviceName` exacto de VotifierPlus sin
distinguir mayúsculas. Sus comandos se ejecutan como el jugador, en orden:

```yml
services:
  force-player-command:
    40smc:
      - mivoto40
```

## 🔒 Operación segura

- `provider.process-test-votes` es `false` por defecto. `votifierplus test <jugador> <servicio>` verifica recepción y deduplicación sin generar economía.
- El fingerprint SHA-256 usa los cinco campos oficiales de VotifierPlus. El proveedor no entrega un ID estable: dos payloads legítimos idénticos son indistinguibles y un retry con payload alterado puede no deduplicarse.
- vVotes no promete entrega externa exactamente una vez. Un comando interrumpido después de invocarse queda `AMBIGUOUS`, visible con `/vvotesadmin ambiguous`, y nunca se reintenta automáticamente.
- Los votos offline conservan período, configuración y comandos de la recepción; no adoptan premios de un reload posterior.

La máquina de estados, recuperación, backups y procedimiento de smoke están en
[`docs/OPERATIONS.md`](docs/OPERATIONS.md).

## 🧰 Requisitos

| Paper | Java requerida | Folia |
| :---: | :---: | :---: |
| 1.21.11 | 21 | ❌ |
| 26.1 en adelante | 25 | ❌ |

Requerido:

- VotifierPlus

Opcional:

- PlaceholderAPI

## 📦 Instalación

1. Instala VotifierPlus y, opcionalmente, PlaceholderAPI.
2. Copia `vVotes-1.0.0.jar` dentro de `plugins/`.
3. Reinicia Paper y configura recompensas, metas y enlaces de voto.
4. Usa `/vvotesadmin reload` para aplicar cambios compatibles.

## 🛠️ Compilación

```bash
./gradlew clean test build
```

El artefacto Paper se genera en `build/libs/vVotes-1.0.0.jar`.

## 🔗 Enlaces

- [Repositorio](https://github.com/ValerinSMP/vVotes)
- [Organización ValerinSMP](https://github.com/ValerinSMP)
