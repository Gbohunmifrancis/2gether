# Twogether monorepo

Twogether is a mobile-first responsive web application for one private couple. The frontend is Next.js; the backend is a .NET 10 modular monolith with PostgreSQL and SignalR.

## Architecture

Clients talk only to `Twogether.Api`. Dependency direction is:

`Shared <- Core <- Application <- Infrastructure <- Api`

- `frontend/`: Next.js App Router, strict TypeScript, REST and SignalR clients.
- `src/Twogether.Shared`: result/error primitives and guards.
- `src/Twogether.Core`: domain entities, enums, invariants, and pure algorithms.
- `src/Twogether.Application`: feature folders, commands, queries, DTOs, validators, and ports.
- `src/Twogether.Infrastructure`: EF Core/PostgreSQL and external adapters.
- `src/Twogether.Api`: controllers, hubs, middleware, auth, and composition only.

## C# conventions

- Use feature folders in Application.
- Use `Result`/`Error` for expected failures; throw for programmer errors only.
- Keep controllers and hubs thin.
- Put EF mapping in `Persistence/Configurations`.
- Derive `CoupleId` from authenticated server state. Never trust it from request payloads.
- The server owns timers, turns, scores, consent state, and reconnect snapshots.
- Wire contracts are camelCase; enums are serialized as camelCase strings.

## Commands

```powershell
dotnet build Twogether.slnx
dotnet test Twogether.slnx

cd frontend
npm.cmd install
npm.cmd run lint
npm.cmd run dev
```
