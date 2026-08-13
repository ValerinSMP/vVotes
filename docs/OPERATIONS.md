# Operación de vVotes

## Identidad y deduplicación

VotifierPlus 1.4.3 expone `serviceName`, `username`, `address`, `timeStamp` y
`sourceAddress`, pero no un ID estable. vVotes construye un SHA-256 versionado con
longitudes prefijadas. Solo recorta espacios y normaliza usuario/servicio con
`Locale.ROOT`; no usa coincidencias parciales ni heurísticas.

Address y sourceAddress participan en el hash, pero no se persisten ni se registran
en logs. Un payload idéntico se considera el mismo evento incluso después de un
restart. Esto no equivale a exactly-once: payloads legítimos idénticos colisionan y
un retry modificado por el proveedor puede producir otro hash.

`TestVote` queda en `QUARANTINED` con la configuración predeterminada. Smoke seguro:

```text
votifierplus test Steve 40smc
```

## Estados durables

```text
UNRESOLVED ──join exacto──> PROCESSING ──transacción──> PLANNED
     │                                               └─ grants PENDING
     └─ payload inválido/TestVote seguro ─────────────> QUARANTINED

PENDING ──claim durable──> CLAIMED ──dispatch confirmado──> DONE
                             ├─ no se invocó el comando ───> PENDING
                             └─ resultado incierto ─────────> AMBIGUOUS
```

`PROCESSING` no se confirma por separado: stats, snapshots, claims, comandos
materializados y `PLANNED` comparten transacción. Al arrancar, todo `CLAIMED`
interrumpido pasa a `AMBIGUOUS`. Un comando ambiguo bloquea las secuencias posteriores
de su lote. Consulta read-only:

```text
/vvotesadmin ambiguous
```

## SQLite y recuperación

- Un solo executor serializa escrituras; PlaceholderAPI lee snapshots en memoria.
- WAL, `busy_timeout`, `schema_version` y `PRAGMA user_version` son obligatorios.
- Antes de migrar una base pre-v2: checkpoint WAL, cierre, copia no sobrescrita e
  `integrity_check` sobre el backup.
- Una versión futura o discrepancia entre ambas versiones falla cerrada antes de
  habilitar listeners.
- `pending_votes` legacy se migra por su PK/origen y no se borra automáticamente.

## Reload y apagado

`/vvotesadmin reload` valida `config.yml`, `messages.yml` y `sound.yml` antes de
aplicar. No reinicia DB, PlaceholderAPI ni listeners. Ruta SQLite, timeout y zona
horaria requieren restart.

Durante disable se bloquea ingest/claims, se cancelan tareas y listeners, se cierra
el executor y finalmente el ledger. Un grant ya reclamado que no alcance `DONE`
queda recuperable como `AMBIGUOUS` en el próximo inicio.

## Límites de verificación

El smoke sin cliente demuestra build, enable, esquema, restart, reload y recepción
oficial de `TestVote`. Recompensas online, ejecución externa de comandos y resolución
por join requieren un cliente/proveedor real y no deben declararse verificadas desde
un servidor vacío.
