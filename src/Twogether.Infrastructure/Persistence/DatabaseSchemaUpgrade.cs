using Microsoft.EntityFrameworkCore;

namespace Twogether.Infrastructure.Persistence;

public static class DatabaseSchemaUpgrade
{
    public static Task ApplyAsync(ApplicationDbContext database, CancellationToken cancellationToken = default)
        => database.Database.ExecuteSqlRawAsync(Sql, Array.Empty<object>(), cancellationToken);

    private const string Sql = """
        ALTER TABLE game_sessions ADD COLUMN IF NOT EXISTS "InvitedByUserId" uuid NULL;
        ALTER TABLE game_sessions ADD COLUMN IF NOT EXISTS "InviteAcceptedAtUtc" timestamp with time zone NULL;
        ALTER TABLE game_sessions ADD COLUMN IF NOT EXISTS "CountdownEndsAtUtc" timestamp with time zone NULL;
        ALTER TABLE game_sessions ADD COLUMN IF NOT EXISTS "EndedByUserId" uuid NULL;
        ALTER TABLE game_sessions ADD COLUMN IF NOT EXISTS "SetupJson" jsonb NOT NULL DEFAULT '{{}}'::jsonb;
        ALTER TABLE game_sessions ADD COLUMN IF NOT EXISTS "EndRequestedByUserId" uuid NULL;
        ALTER TABLE game_sessions ADD COLUMN IF NOT EXISTS "EndRequestedAtUtc" timestamp with time zone NULL;
        ALTER TABLE messages ADD COLUMN IF NOT EXISTS "IsPrivate" boolean NOT NULL DEFAULT false;
        ALTER TABLE users ADD COLUMN IF NOT EXISTS "MapColor" character varying(20) NOT NULL DEFAULT '#f45c91';
        ALTER TABLE users ADD COLUMN IF NOT EXISTS "CycleOwner" boolean NOT NULL DEFAULT false;
        ALTER TABLE users ADD COLUMN IF NOT EXISTS "Gender" character varying(32) NOT NULL DEFAULT 'PreferNotToSay';

        -- "On game" is now "I call on". GameType is stored as a string, so rows written
        -- before the rename carry the old name and would fail to map on read.
        UPDATE game_sessions SET "GameType" = 'ICallOn' WHERE "GameType" = 'OnGame';

        CREATE TABLE IF NOT EXISTS notifications (
            "Id" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "Type" character varying(40) NOT NULL,
            "Title" character varying(180) NOT NULL,
            "Body" character varying(1000) NOT NULL,
            "DataJson" jsonb NULL,
            "ReadAtUtc" timestamp with time zone NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_notifications" PRIMARY KEY ("Id")
        );

        CREATE TABLE IF NOT EXISTS couple_locations (
            "Id" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "Latitude" double precision NOT NULL,
            "Longitude" double precision NOT NULL,
            "AccuracyMeters" double precision NOT NULL,
            "SharingEnabled" boolean NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_couple_locations" PRIMARY KEY ("Id")
        );

        CREATE TABLE IF NOT EXISTS game_results (
            "Id" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "GameSessionId" uuid NOT NULL,
            "GameType" character varying(40) NOT NULL,
            "Outcome" character varying(40) NOT NULL,
            "StartedAtUtc" timestamp with time zone NULL,
            "EndedAtUtc" timestamp with time zone NOT NULL,
            "WinnerUserId" uuid NULL,
            "ScoresJson" jsonb NOT NULL DEFAULT '{{}}'::jsonb,
            "RoundsPlayed" integer NOT NULL DEFAULT 0,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_game_results" PRIMARY KEY ("Id")
        );

        CREATE INDEX IF NOT EXISTS "IX_game_sessions_InvitedByUserId" ON game_sessions ("InvitedByUserId");
        CREATE INDEX IF NOT EXISTS "IX_notifications_UserId_CreatedAtUtc" ON notifications ("UserId", "CreatedAtUtc");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_couple_locations_CoupleId_UserId" ON couple_locations ("CoupleId", "UserId");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_game_results_GameSessionId" ON game_results ("GameSessionId");
        CREATE INDEX IF NOT EXISTS "IX_game_results_CoupleId_EndedAtUtc" ON game_results ("CoupleId", "EndedAtUtc");
        """;
}
