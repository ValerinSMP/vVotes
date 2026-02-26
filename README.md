# vVotes
Sistema de votos para Purpur 1.21.11 con metas globales diarias y metas mensuales por jugador.

## Requisitos
- Java 21
- Purpur/Paper API 1.21.11
- VotifierPlus instalado en el servidor
- PlaceholderAPI (opcional, para placeholders)

## Compilar
```bash
mvn -DskipTests package
```

El jar final queda en `target/vVotes-1.0.0.jar`.

## Comandos
- `/vote` muestra tus estadisticas y metas por chat.
- `/voteadmin reload`
- `/voteadmin add <jugador> <cantidad>`
- `/voteadmin resetdaily`
- `/voteadmin resetmonthly <jugador>`

## Permisos
- `vvotes.use`
- `vvotes.admin`

## Placeholders (PlaceholderAPI)
- `%vvotes_total%`
- `%vvotes_daily%`
- `%vvotes_monthly%`
- `%vvotes_streak_monthly%`
- `%vvotes_global_daily%`
- `%vvotes_next_global_goal%`
- `%vvotes_next_monthly_goal%`
- `%vvotes_double_site_today_icon%` (muestra `☁` cuando ya voto en 2 sitios hoy, o vacio si no)

## Configuracion
- `config.yml`: rewards, metas y timezone.
- `messages.yml`: mensajes del plugin.
- `sound.yml`: sonidos por evento (voto y anuncio global).

## Notas funcionales
- Recompensa automatica al votar.
- Si el jugador esta offline, el voto se omite (no se guarda para entregar despues).
- SQLite optimizado con WAL y retencion indefinida de historial (`vote_logs`).
