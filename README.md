# Twogether

Twogether is a private, invite-only web space for one couple. It combines a privacy-controlled cycle tracker with synchronous two-player games and lightweight couple messaging.

The web experience is responsive and mobile-first. There is no native client in the MVP.

## Repository map

```text
frontend/                       Next.js App Router web client
src/Twogether.Shared/          Result, Error, Guard
src/Twogether.Core/            Domain entities and enums
src/Twogether.Application/     Use cases and application contracts
src/Twogether.Infrastructure/  EF Core, PostgreSQL, external adapters
src/Twogether.Api/             Controllers, SignalR hubs, composition
tests/                         Unit, integration, and API tests
```

The C# layout and conventions follow the existing NoteFusion project: dependencies point inward, controllers stay thin, and expected failures use `Result`/`Error` rather than exceptions.

## Current foundation

- .NET 10 clean-architecture solution
- PostgreSQL EF Core context and initial entity configurations
- Initial User, Couple, PartnerInvite, Cycle, and GameSession domain types
- Shared API error mapping
- Couple and game SignalR hub shells
- Health endpoint at `/health`
- Mobile-first Next.js dashboard shell inspired by the supplied design reference
- Desktop sidebar and mobile bottom navigation

Authentication, invite-based couple linking, cycle tracking and prediction, persisted messages with SignalR fan-out, and server-owned game sessions are implemented. PostgreSQL is selected from `DATABASE_URL` (Railway) or the `PG*` variables. Production requires an explicit `Jwt__SigningKey`.

## Local setup

Start PostgreSQL:

```powershell
docker compose up -d db
```

Run the API:

```powershell
dotnet run --project src/Twogether.Api --urls http://localhost:5080
```

Run the frontend:

```powershell
cd frontend
npm.cmd install
npm.cmd run dev
```

Open `http://localhost:3000`. The API health endpoint is `http://localhost:5080/health`.

Environment keys use double underscores in deployment, for example `Database__ConnectionString` and `Frontend__Origin`.

For the deployed frontend set `NEXT_PUBLIC_API_BASE_URL` and `NEXT_PUBLIC_SIGNALR_BASE_URL` to the public API origin. Set the API's `Frontend__Origin` to the Vercel origin (comma-separated if more than one origin is needed).

# 2gether
