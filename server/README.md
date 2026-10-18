# server

One Spring Boot 3.5 app (Java 17, SQLite, Flyway). Packages and owners are listed in `AGENTS.md`.
Kiro builds the core (`fuelprint.core`, `fuelprint.api`).

```
./mvnw test                         # all server tests (or ../tools/test-all.sh)
./mvnw spring-boot:run              # data in <repo>/data/runtime (fp.data-dir)
```

Core pieces every package uses:

- `CommandBus` / `CommandHandler`: every state change is a command; register a handler bean per `command_type`.
- `AutomationBus.submit(...)`: what event listeners call to make an automatic step (actor `automation`).
- `RecordStore` (read through `StoreView`), `EventLog`, `BlobStore`.
- `fuelprint.api`: the interfaces of AGREEMENT section 9 and their record types.
