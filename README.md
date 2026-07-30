<div align="center">

# vVotes

### Votos, recompensas y metas para la comunidad de ValerinSMP

[![Paper](https://img.shields.io/badge/Paper-1.21.11%2B-222222?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-E76F00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/version-1.0.0-7B5CFA?style=for-the-badge)](https://github.com/ValerinSMP/vVotes)

</div>

**vVotes** conecta VotifierPlus con el sistema de progresión de ValerinSMP. Registra
votos, entrega recompensas y presenta metas globales diarias y metas mensuales por
jugador.

## ⭐ Características

- **Recompensas automáticas:** ejecuta comandos configurables por cada voto.
- **Jugadores offline:** conserva recompensas pendientes hasta la próxima conexión.
- **Metas globales:** progreso diario compartido por toda la comunidad.
- **Metas personales:** objetivos mensuales por jugador.
- **Rachas:** seguimiento de participación durante el mes.
- **Sorteos mensuales:** selección y registro de ganadores elegibles.
- **Top mensual:** ranking de votos del periodo actual.
- **Historial durable:** SQLite con WAL y registro de eventos.
- **PlaceholderAPI:** estadísticas listas para scoreboards y menús.

## 🎮 Comandos

| Comando | Descripción | Permiso |
| --- | --- | --- |
| `/vote` | Muestra ayuda y enlaces de voto. | `vvotes.use` |
| `/votestats` | Muestra estadísticas personales. | `vvotes.use` |
| `/vvotes toggle` | Alterna anuncios personales. | `vvotes.use` |
| `/vvotesadmin` | Administra votos, metas y sorteos. | `vvotes.admin` |

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
- `messages.yml`: textos del plugin.
- `sound.yml`: sonidos de votos y anuncios.

## 🧰 Requisitos

| Paper | Java requerida | Folia |
| :---: | :---: | :---: |
| 1.21.11 | 21 | ❌ |
| 26.1 en adelante | 25 | ❌ |

Requerido:

- VotifierPlus

Opcional:

- PlaceholderAPI

## 🛠️ Compilación

```bash
./gradlew clean test build
```

El artefacto Paper se genera en `build/libs/vVotes-1.0.0.jar`.
