using Microsoft.EntityFrameworkCore;

namespace Twogether.Infrastructure.Persistence;

public static class DatabaseSchemaUpgrade
{
    public static Task ApplyAsync(ApplicationDbContext database, CancellationToken cancellationToken = default)
        => database.Database.ExecuteSqlRawAsync(Sql, Array.Empty<object>(), cancellationToken);

    private const string Sql = """
        ALTER TABLE couples ADD COLUMN IF NOT EXISTS "DissolvedAtUtc" timestamp with time zone NULL;
        -- Nothing updates a couple after it ends, so its last update is when it ended.
        UPDATE couples SET "DissolvedAtUtc" = "UpdatedAtUtc" WHERE "Status" = 'Dissolved' AND "DissolvedAtUtc" IS NULL;
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
        ALTER TABLE IF EXISTS couple_locations ADD COLUMN IF NOT EXISTS "PausedUntilUtc" timestamp with time zone NULL;
        -- Push outbox columns. Rows that existed before push delivery are marked skipped so a
        -- deploy never sends a backlog of old notifications to phones.
        ALTER TABLE IF EXISTS notifications ADD COLUMN IF NOT EXISTS "PushState" character varying(20) NOT NULL DEFAULT 'Skipped';
        ALTER TABLE IF EXISTS notifications ADD COLUMN IF NOT EXISTS "PushAttempts" integer NOT NULL DEFAULT 0;
        ALTER TABLE IF EXISTS notifications ADD COLUMN IF NOT EXISTS "PushNextAttemptAtUtc" timestamp with time zone NULL;
        ALTER TABLE IF EXISTS notifications ADD COLUMN IF NOT EXISTS "PushLockedUntilUtc" timestamp with time zone NULL;
        ALTER TABLE IF EXISTS notifications ADD COLUMN IF NOT EXISTS "PushedAtUtc" timestamp with time zone NULL;

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
            "PushState" character varying(20) NOT NULL DEFAULT 'Skipped',
            "PushAttempts" integer NOT NULL DEFAULT 0,
            "PushNextAttemptAtUtc" timestamp with time zone NULL,
            "PushLockedUntilUtc" timestamp with time zone NULL,
            "PushedAtUtc" timestamp with time zone NULL,
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
            "PausedUntilUtc" timestamp with time zone NULL,
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

        CREATE TABLE IF NOT EXISTS exercise_goals (
            "Id" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "AssignedUserId" uuid NOT NULL,
            "CreatedByUserId" uuid NOT NULL,
            "ExerciseType" character varying(40) NOT NULL,
            "Title" character varying(80) NOT NULL,
            "Target" integer NOT NULL,
            "ScheduledTime" time without time zone NOT NULL,
            "TimeZoneId" character varying(100) NOT NULL,
            "GraceMinutes" integer NOT NULL,
            "IsActive" boolean NOT NULL,
            "NextDueAtUtc" timestamp with time zone NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_exercise_goals" PRIMARY KEY ("Id")
        );

        CREATE TABLE IF NOT EXISTS exercise_occurrences (
            "Id" uuid NOT NULL,
            "GoalId" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "ExerciseType" character varying(40) NOT NULL,
            "Title" character varying(80) NOT NULL,
            "Target" integer NOT NULL,
            "LocalDate" date NOT NULL,
            "ScheduledAtUtc" timestamp with time zone NOT NULL,
            "DeadlineUtc" timestamp with time zone NOT NULL,
            "CompleteByUtc" timestamp with time zone NULL,
            "Status" character varying(40) NOT NULL,
            "AlarmStartedAtUtc" timestamp with time zone NOT NULL,
            "StartedAtUtc" timestamp with time zone NULL,
            "CompletedAtUtc" timestamp with time zone NULL,
            "AchievedCount" integer NOT NULL,
            "Confidence" double precision NOT NULL,
            "StateVersion" bigint NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_exercise_occurrences" PRIMARY KEY ("Id")
        );

        CREATE TABLE IF NOT EXISTS food_entries (
            "Id" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "LocalDate" date NOT NULL,
            "MealType" character varying(20) NOT NULL,
            "Description" character varying(120) NOT NULL,
            "Calories" integer NOT NULL,
            "CaloriesLow" integer NULL,
            "CaloriesHigh" integer NULL,
            "ProteinGrams" double precision NULL,
            "CarbsGrams" double precision NULL,
            "FatGrams" double precision NULL,
            "Source" character varying(20) NOT NULL,
            "Confidence" double precision NULL,
            "ScanId" uuid NULL,
            "LoggedAtUtc" timestamp with time zone NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_food_entries" PRIMARY KEY ("Id"),
            CONSTRAINT "CK_food_entries_Calories" CHECK ("Calories" >= 0),
            CONSTRAINT "CK_food_entries_Range" CHECK ("CaloriesLow" IS NULL OR ("CaloriesHigh" IS NOT NULL AND "CaloriesLow" <= "Calories" AND "Calories" <= "CaloriesHigh"))
        );

        CREATE TABLE IF NOT EXISTS nutrition_targets (
            "Id" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "DailyCalories" integer NOT NULL,
            "ProteinGrams" double precision NULL,
            "CarbsGrams" double precision NULL,
            "FatGrams" double precision NULL,
            "ShareLevel" character varying(20) NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_nutrition_targets" PRIMARY KEY ("Id"),
            CONSTRAINT "CK_nutrition_targets_DailyCalories" CHECK ("DailyCalories" BETWEEN 800 AND 6000)
        );

        CREATE TABLE IF NOT EXISTS food_scans (
            "Id" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "Model" character varying(80) NOT NULL,
            "MediaType" character varying(40) NOT NULL,
            "ImageBytes" integer NOT NULL,
            "Succeeded" boolean NOT NULL,
            "ErrorCode" character varying(60) NULL,
            "InputTokens" integer NOT NULL,
            "OutputTokens" integer NOT NULL,
            "LatencyMs" integer NOT NULL,
            "ResultJson" jsonb NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_food_scans" PRIMARY KEY ("Id")
        );

        CREATE TABLE IF NOT EXISTS pregnancies (
            "Id" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "DueDate" date NOT NULL,
            "Status" character varying(20) NOT NULL,
            "ShareWithPartner" boolean NOT NULL,
            "EndedAtUtc" timestamp with time zone NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_pregnancies" PRIMARY KEY ("Id")
        );

        CREATE TABLE IF NOT EXISTS pregnancy_appointments (
            "Id" uuid NOT NULL,
            "PregnancyId" uuid NOT NULL,
            "CreatedByUserId" uuid NOT NULL,
            "Title" character varying(120) NOT NULL,
            "AtUtc" timestamp with time zone NOT NULL,
            "Location" character varying(200) NULL,
            "Notes" character varying(1000) NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_pregnancy_appointments" PRIMARY KEY ("Id"),
            CONSTRAINT "FK_pregnancy_appointments_pregnancies_PregnancyId" FOREIGN KEY ("PregnancyId") REFERENCES pregnancies ("Id") ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS kick_sessions (
            "Id" uuid NOT NULL,
            "PregnancyId" uuid NOT NULL,
            "StartedAtUtc" timestamp with time zone NOT NULL,
            "EndedAtUtc" timestamp with time zone NULL,
            "Count" integer NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_kick_sessions" PRIMARY KEY ("Id"),
            CONSTRAINT "FK_kick_sessions_pregnancies_PregnancyId" FOREIGN KEY ("PregnancyId") REFERENCES pregnancies ("Id") ON DELETE CASCADE,
            CONSTRAINT "CK_kick_sessions_Count" CHECK ("Count" BETWEEN 0 AND 200)
        );

        CREATE TABLE IF NOT EXISTS daily_questions (
            "Id" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "LocalDate" date NOT NULL,
            "QuestionKey" character varying(40) NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_daily_questions" PRIMARY KEY ("Id")
        );

        CREATE TABLE IF NOT EXISTS daily_answers (
            "Id" uuid NOT NULL,
            "DailyQuestionId" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "Body" character varying(1000) NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_daily_answers" PRIMARY KEY ("Id"),
            CONSTRAINT "FK_daily_answers_daily_questions_DailyQuestionId" FOREIGN KEY ("DailyQuestionId") REFERENCES daily_questions ("Id") ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS memories (
            "Id" uuid NOT NULL,
            "CoupleId" uuid NOT NULL,
            "UploadedByUserId" uuid NOT NULL,
            "ObjectKey" character varying(200) NOT NULL,
            "ContentType" character varying(40) NOT NULL,
            "IsVideo" boolean NOT NULL,
            "SizeBytes" bigint NOT NULL,
            "Caption" character varying(500) NULL,
            "TakenAtUtc" timestamp with time zone NULL,
            "SortAtUtc" timestamp with time zone NOT NULL,
            "Source" character varying(20) NOT NULL,
            "Status" character varying(20) NOT NULL,
            "ReadyAtUtc" timestamp with time zone NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_memories" PRIMARY KEY ("Id"),
            CONSTRAINT "CK_memories_SizeBytes" CHECK ("SizeBytes" > 0)
        );
        ALTER TABLE memories ADD COLUMN IF NOT EXISTS "ThumbnailContentType" character varying(20) NULL;
        ALTER TABLE memories ADD COLUMN IF NOT EXISTS "ThumbnailBytes" bigint NOT NULL DEFAULT 0;
        ALTER TABLE memories ADD COLUMN IF NOT EXISTS "UploadWindowEndsAtUtc" timestamp with time zone NULL;
        CREATE INDEX IF NOT EXISTS "IX_memories_upload_window" ON memories ("UploadWindowEndsAtUtc") WHERE "UploadWindowEndsAtUtc" IS NOT NULL;

        CREATE TABLE IF NOT EXISTS device_registrations (
            "Id" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "Platform" character varying(20) NOT NULL,
            "TokenKind" character varying(30) NOT NULL,
            "Token" character varying(1024) NOT NULL,
            "AppVersion" character varying(40) NULL,
            "LastSeenAtUtc" timestamp with time zone NOT NULL,
            "DisabledAtUtc" timestamp with time zone NULL,
            "DisabledReason" character varying(40) NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_device_registrations" PRIMARY KEY ("Id")
        );

        CREATE TABLE IF NOT EXISTS notification_preferences (
            "Id" uuid NOT NULL,
            "UserId" uuid NOT NULL,
            "MessagesEnabled" boolean NOT NULL,
            "GamesEnabled" boolean NOT NULL,
            "ExerciseEnabled" boolean NOT NULL,
            "CycleEnabled" boolean NOT NULL,
            "PregnancyEnabled" boolean NOT NULL,
            "DailyQuestionsEnabled" boolean NOT NULL,
            "QuietHoursEnabled" boolean NOT NULL,
            "QuietStartLocal" time without time zone NOT NULL,
            "QuietEndLocal" time without time zone NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_notification_preferences" PRIMARY KEY ("Id"),
            CONSTRAINT "CK_notification_preferences_quiet_window" CHECK ("QuietStartLocal" <> "QuietEndLocal")
        );

        CREATE TABLE IF NOT EXISTS store_subscriptions (
            "Id" uuid NOT NULL,
            "Store" character varying(20) NOT NULL,
            "PurchaseToken" character varying(1024) NOT NULL,
            "ProductId" character varying(100) NOT NULL,
            "UserId" uuid NOT NULL,
            "Status" character varying(30) NOT NULL,
            "ExpiresAtUtc" timestamp with time zone NOT NULL,
            "AutoRenewing" boolean NOT NULL,
            "Acknowledged" boolean NOT NULL,
            "LinkedPurchaseToken" character varying(1024) NULL,
            "LastVerifiedAtUtc" timestamp with time zone NOT NULL,
            "CreatedAtUtc" timestamp with time zone NOT NULL,
            "UpdatedAtUtc" timestamp with time zone NOT NULL,
            CONSTRAINT "PK_store_subscriptions" PRIMARY KEY ("Id")
        );

        CREATE INDEX IF NOT EXISTS "IX_game_sessions_InvitedByUserId" ON game_sessions ("InvitedByUserId");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_store_subscriptions_PurchaseToken" ON store_subscriptions ("PurchaseToken");
        CREATE INDEX IF NOT EXISTS "IX_store_subscriptions_UserId_ExpiresAtUtc" ON store_subscriptions ("UserId", "ExpiresAtUtc");
        CREATE INDEX IF NOT EXISTS "IX_notifications_pending_push" ON notifications ("CreatedAtUtc") WHERE "PushState" = 'Pending';
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_device_registrations_Token" ON device_registrations ("Token");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_notification_preferences_UserId" ON notification_preferences ("UserId");
        CREATE INDEX IF NOT EXISTS "IX_device_registrations_UserId_DisabledAtUtc" ON device_registrations ("UserId", "DisabledAtUtc");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_memories_ObjectKey" ON memories ("ObjectKey");
        CREATE INDEX IF NOT EXISTS "IX_memories_CoupleId_Status_SortAtUtc_Id" ON memories ("CoupleId", "Status", "SortAtUtc", "Id");
        CREATE INDEX IF NOT EXISTS "IX_memories_Status_CreatedAtUtc" ON memories ("Status", "CreatedAtUtc");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_daily_questions_CoupleId_LocalDate" ON daily_questions ("CoupleId", "LocalDate");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_daily_answers_DailyQuestionId_UserId" ON daily_answers ("DailyQuestionId", "UserId");
        CREATE INDEX IF NOT EXISTS "IX_daily_answers_CoupleId" ON daily_answers ("CoupleId");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_pregnancies_UserId" ON pregnancies ("UserId") WHERE "Status" = 'Active';
        CREATE INDEX IF NOT EXISTS "IX_pregnancies_CoupleId_Status" ON pregnancies ("CoupleId", "Status");
        CREATE INDEX IF NOT EXISTS "IX_pregnancy_appointments_PregnancyId_AtUtc" ON pregnancy_appointments ("PregnancyId", "AtUtc");
        CREATE INDEX IF NOT EXISTS "IX_kick_sessions_PregnancyId_StartedAtUtc" ON kick_sessions ("PregnancyId", "StartedAtUtc");
        CREATE INDEX IF NOT EXISTS "IX_food_entries_UserId_LocalDate" ON food_entries ("UserId", "LocalDate");
        CREATE INDEX IF NOT EXISTS "IX_food_entries_CoupleId_LocalDate" ON food_entries ("CoupleId", "LocalDate");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_nutrition_targets_UserId" ON nutrition_targets ("UserId");
        CREATE INDEX IF NOT EXISTS "IX_food_scans_UserId_CreatedAtUtc" ON food_scans ("UserId", "CreatedAtUtc");
        CREATE INDEX IF NOT EXISTS "IX_exercise_goals_CoupleId_IsActive" ON exercise_goals ("CoupleId", "IsActive");
        CREATE INDEX IF NOT EXISTS "IX_exercise_goals_IsActive_NextDueAtUtc" ON exercise_goals ("IsActive", "NextDueAtUtc");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_exercise_occurrences_GoalId_LocalDate" ON exercise_occurrences ("GoalId", "LocalDate");
        CREATE INDEX IF NOT EXISTS "IX_exercise_occurrences_CoupleId_LocalDate" ON exercise_occurrences ("CoupleId", "LocalDate");
        CREATE INDEX IF NOT EXISTS "IX_exercise_occurrences_Status_DeadlineUtc" ON exercise_occurrences ("Status", "DeadlineUtc");
        CREATE INDEX IF NOT EXISTS "IX_notifications_UserId_CreatedAtUtc" ON notifications ("UserId", "CreatedAtUtc");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_couple_locations_CoupleId_UserId" ON couple_locations ("CoupleId", "UserId");
        CREATE UNIQUE INDEX IF NOT EXISTS "IX_game_results_GameSessionId" ON game_results ("GameSessionId");
        CREATE INDEX IF NOT EXISTS "IX_game_results_CoupleId_EndedAtUtc" ON game_results ("CoupleId", "EndedAtUtc");
        """;
}
