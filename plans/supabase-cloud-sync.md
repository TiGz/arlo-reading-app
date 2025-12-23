# Supabase Cloud Sync - Implementation Plan

## Overview

Add cloud persistence to the Arlo reading app using Supabase, enabling:
1. **Remote stat tracking** - View Arlo's reading progress from anywhere
2. **Error logging** - Remote debugging without physical tablet access
3. **Offline-first** - Graceful handling of connectivity issues
4. **Claude Code skill** - Query stats directly from your development environment

## Requirements Summary

| # | Feature | Description | Priority |
|---|---------|-------------|----------|
| 1 | Daily Stats Sync | All gamification stats (stars, points, streaks, races) | High |
| 2 | Book Progress | Book titles, pages read, completion status | High |
| 3 | Error Logging | Crash reports, API errors, unexpected states | High |
| 4 | Session Activity | When and how long Arlo reads | Medium |
| 5 | Word Mastery | Difficult words and progress | Medium |
| 6 | Achievements | Unlocked achievements and progress | Low |
| 7 | Claude Code Skill | Query stats via `/arlo-stats` command | High |

---

## Architecture

### High-Level Data Flow

```
┌─────────────────┐         ┌──────────────────┐         ┌─────────────────┐
│   Arlo App      │         │  Supabase Edge   │         │  Supabase DB    │
│   (Android)     │ ──────▶ │   Functions      │ ──────▶ │  (Postgres)     │
│                 │         │   (TypeScript)   │         │                 │
└─────────────────┘         └──────────────────┘         └─────────────────┘
        │                                                        │
        │  Room DB (local)                                       │
        └───────────────────────────────────────────────────────┘
                        ▲                              │
                        │     Claude Code Skill        │
                        │     (query via Supabase)     │
                        └──────────────────────────────┘
```

### Sync Strategy: Batch Periodic Sync (Not Real-time)

To avoid chattiness and handle connectivity gracefully:

1. **Local-first**: All data written to Room DB immediately
2. **Periodic sync**: Background job syncs every 15 minutes when app is active
3. **Event-driven sync**: Sync on app pause/resume transitions
4. **Batch uploads**: Aggregate changes since last sync, send in single request
5. **Idempotent writes**: Server handles duplicate/out-of-order data gracefully

### Why Not Real-time Sync?

- Reading sessions are typically 15-30 minutes
- Tablet may have intermittent WiFi connectivity
- Battery efficiency matters for kids' tablets
- Parent queries are infrequent (daily check-ins)
- Simplicity > complexity for a single-user app

---

## Supabase Project Setup

### 1. Create Supabase Project

```bash
# Install Supabase CLI
brew install supabase/tap/supabase

# Login and create project
supabase login
supabase projects create arlo-reading-stats --region us-east-1
```

### 2. Database Schema

```sql
-- Enable UUID extension
create extension if not exists "uuid-ossp";

-- ============================================
-- CORE TABLES
-- ============================================

-- Device registration (single device for now, extensible later)
create table devices (
    id uuid primary key default uuid_generate_v4(),
    device_name text not null default 'Arlo''s Tablet',
    device_id text unique not null,  -- Android device ID
    app_version text,
    last_seen_at timestamptz default now(),
    created_at timestamptz default now()
);

-- Daily reading statistics (mirrors Room DailyStats)
create table daily_stats (
    id uuid primary key default uuid_generate_v4(),
    device_id uuid references devices(id) on delete cascade,
    date date not null,

    -- Star breakdown
    gold_stars int default 0,
    silver_stars int default 0,
    bronze_stars int default 0,
    total_points int default 0,

    -- Daily goal
    daily_points_target int default 100,
    goal_met boolean default false,

    -- Reading activity
    sentences_read int default 0,
    pages_completed int default 0,
    books_completed int default 0,

    -- Collaborative mode
    perfect_words int default 0,
    total_collaborative_attempts int default 0,
    successful_collaborative_attempts int default 0,
    longest_streak int default 0,

    -- Time tracking
    active_reading_time_ms bigint default 0,
    total_app_time_ms bigint default 0,
    session_count int default 0,

    -- Game rewards
    races_earned int default 0,
    races_used int default 0,
    game_reward_claimed boolean default false,

    -- Metadata
    synced_at timestamptz default now(),
    app_updated_at timestamptz,

    unique(device_id, date)
);

-- Book progress tracking
create table books (
    id uuid primary key default uuid_generate_v4(),
    device_id uuid references devices(id) on delete cascade,
    local_book_id bigint not null,  -- Room DB book ID
    title text not null,

    -- Progress
    total_pages int default 0,
    pages_read int default 0,
    current_page int default 1,
    current_sentence int default 0,

    -- Completion
    started_at timestamptz default now(),
    completed_at timestamptz,

    -- Stats
    total_stars_earned int default 0,
    total_reading_time_ms bigint default 0,

    synced_at timestamptz default now(),

    unique(device_id, local_book_id)
);

-- Reading sessions (for activity pattern analysis)
create table reading_sessions (
    id uuid primary key default uuid_generate_v4(),
    device_id uuid references devices(id) on delete cascade,
    local_session_id bigint,

    date date not null,
    started_at timestamptz not null,
    ended_at timestamptz,
    duration_ms bigint default 0,

    -- What was read
    book_id uuid references books(id),
    pages_read int default 0,
    sentences_read int default 0,

    -- Stars earned
    gold_stars int default 0,
    silver_stars int default 0,
    bronze_stars int default 0,
    points_earned int default 0,

    synced_at timestamptz default now()
);

-- Error logging for remote debugging
create table error_logs (
    id uuid primary key default uuid_generate_v4(),
    device_id uuid references devices(id) on delete cascade,

    -- Error classification
    error_type text not null,  -- 'crash', 'api_error', 'tts_error', 'ocr_error', 'unexpected'
    severity text default 'error',  -- 'debug', 'info', 'warning', 'error', 'fatal'

    -- Error details
    message text not null,
    stack_trace text,
    context jsonb,  -- Additional context (screen, action, etc.)

    -- App state when error occurred
    app_version text,
    current_screen text,

    occurred_at timestamptz not null,
    synced_at timestamptz default now()
);

-- Difficult words (word mastery tracking)
create table difficult_words (
    id uuid primary key default uuid_generate_v4(),
    device_id uuid references devices(id) on delete cascade,

    word text not null,
    normalized_word text not null,

    total_attempts int default 0,
    successful_attempts int default 0,
    consecutive_successes int default 0,
    mastery_level int default 0,  -- 0-5

    first_seen_at timestamptz default now(),
    last_attempt_at timestamptz,

    synced_at timestamptz default now(),

    unique(device_id, normalized_word)
);

-- Sync metadata (track what's been synced)
create table sync_state (
    id uuid primary key default uuid_generate_v4(),
    device_id uuid references devices(id) on delete cascade unique,

    last_daily_stats_sync timestamptz,
    last_books_sync timestamptz,
    last_sessions_sync timestamptz,
    last_words_sync timestamptz,
    last_full_sync timestamptz,

    updated_at timestamptz default now()
);

-- ============================================
-- INDEXES
-- ============================================

create index idx_daily_stats_device_date on daily_stats(device_id, date desc);
create index idx_books_device on books(device_id);
create index idx_sessions_device_date on reading_sessions(device_id, date desc);
create index idx_errors_device_time on error_logs(device_id, occurred_at desc);
create index idx_errors_type on error_logs(error_type, occurred_at desc);
create index idx_words_device on difficult_words(device_id);

-- ============================================
-- ROW LEVEL SECURITY
-- ============================================

-- Enable RLS on all tables
alter table devices enable row level security;
alter table daily_stats enable row level security;
alter table books enable row level security;
alter table reading_sessions enable row level security;
alter table error_logs enable row level security;
alter table difficult_words enable row level security;
alter table sync_state enable row level security;

-- Policies: Allow service role (Edge Functions) full access
-- The app will use anon key + custom auth via device_id
create policy "Service role has full access to devices"
    on devices for all to service_role using (true);

create policy "Service role has full access to daily_stats"
    on daily_stats for all to service_role using (true);

create policy "Service role has full access to books"
    on books for all to service_role using (true);

create policy "Service role has full access to reading_sessions"
    on reading_sessions for all to service_role using (true);

create policy "Service role has full access to error_logs"
    on error_logs for all to service_role using (true);

create policy "Service role has full access to difficult_words"
    on difficult_words for all to service_role using (true);

create policy "Service role has full access to sync_state"
    on sync_state for all to service_role using (true);

-- ============================================
-- HELPER FUNCTIONS
-- ============================================

-- Get today's stats summary
create or replace function get_today_summary(p_device_id uuid)
returns json
language plpgsql
security definer
as $$
declare
    result json;
begin
    select json_build_object(
        'date', date,
        'total_stars', gold_stars + silver_stars + bronze_stars,
        'gold_stars', gold_stars,
        'silver_stars', silver_stars,
        'bronze_stars', bronze_stars,
        'total_points', total_points,
        'goal_met', goal_met,
        'daily_target', daily_points_target,
        'sentences_read', sentences_read,
        'reading_time_minutes', round(active_reading_time_ms / 60000.0),
        'longest_streak', longest_streak,
        'races_earned', races_earned,
        'races_used', races_used
    ) into result
    from daily_stats
    where device_id = p_device_id
      and date = current_date;

    return coalesce(result, '{}');
end;
$$;

-- Get weekly summary
create or replace function get_weekly_summary(p_device_id uuid, p_week_start date default null)
returns json
language plpgsql
security definer
as $$
declare
    week_start date;
    result json;
begin
    week_start := coalesce(p_week_start, date_trunc('week', current_date)::date);

    select json_build_object(
        'week_start', week_start,
        'week_end', week_start + interval '6 days',
        'total_stars', sum(gold_stars + silver_stars + bronze_stars),
        'gold_stars', sum(gold_stars),
        'silver_stars', sum(silver_stars),
        'bronze_stars', sum(bronze_stars),
        'total_points', sum(total_points),
        'days_with_activity', count(case when total_points > 0 then 1 end),
        'goals_met', count(case when goal_met then 1 end),
        'total_reading_minutes', round(sum(active_reading_time_ms) / 60000.0),
        'sentences_read', sum(sentences_read),
        'best_streak', max(longest_streak),
        'total_races', sum(races_earned)
    ) into result
    from daily_stats
    where device_id = p_device_id
      and date between week_start and week_start + interval '6 days';

    return coalesce(result, '{}');
end;
$$;

-- Get lifetime summary
create or replace function get_lifetime_summary(p_device_id uuid)
returns json
language plpgsql
security definer
as $$
declare
    result json;
begin
    select json_build_object(
        'total_stars', sum(gold_stars + silver_stars + bronze_stars),
        'gold_stars', sum(gold_stars),
        'silver_stars', sum(silver_stars),
        'bronze_stars', sum(bronze_stars),
        'total_points', sum(total_points),
        'total_days', count(*),
        'days_with_activity', count(case when total_points > 0 then 1 end),
        'goals_met', count(case when goal_met then 1 end),
        'total_reading_hours', round(sum(active_reading_time_ms) / 3600000.0, 1),
        'sentences_read', sum(sentences_read),
        'pages_completed', sum(pages_completed),
        'books_completed', sum(books_completed),
        'best_streak', max(longest_streak),
        'total_races', sum(races_earned),
        'first_activity', min(date),
        'last_activity', max(date)
    ) into result
    from daily_stats
    where device_id = p_device_id;

    return coalesce(result, '{}');
end;
$$;

-- Get recent errors
create or replace function get_recent_errors(p_device_id uuid, p_limit int default 20)
returns json
language plpgsql
security definer
as $$
begin
    return (
        select coalesce(json_agg(row_to_json(e)), '[]')
        from (
            select
                error_type,
                severity,
                message,
                context,
                current_screen,
                app_version,
                occurred_at
            from error_logs
            where device_id = p_device_id
            order by occurred_at desc
            limit p_limit
        ) e
    );
end;
$$;
```

### 3. Edge Functions

#### `sync-stats` - Main sync endpoint

```typescript
// supabase/functions/sync-stats/index.ts
import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type, x-device-id',
}

interface SyncPayload {
  device_id: string
  device_name?: string
  app_version?: string
  daily_stats?: DailyStatsPayload[]
  books?: BookPayload[]
  sessions?: SessionPayload[]
  difficult_words?: WordPayload[]
  errors?: ErrorPayload[]
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response(null, { headers: corsHeaders })
  }

  try {
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    const payload: SyncPayload = await req.json()

    if (!payload.device_id) {
      return new Response(
        JSON.stringify({ error: 'device_id is required' }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Upsert device
    const { data: device, error: deviceError } = await supabase
      .from('devices')
      .upsert({
        device_id: payload.device_id,
        device_name: payload.device_name || "Arlo's Tablet",
        app_version: payload.app_version,
        last_seen_at: new Date().toISOString()
      }, { onConflict: 'device_id' })
      .select()
      .single()

    if (deviceError) {
      console.error('Device upsert error:', deviceError)
      throw deviceError
    }

    const deviceUuid = device.id
    const results = {
      device: 'synced',
      daily_stats: 0,
      books: 0,
      sessions: 0,
      words: 0,
      errors: 0
    }

    // Sync daily stats
    if (payload.daily_stats?.length) {
      const statsToUpsert = payload.daily_stats.map(s => ({
        device_id: deviceUuid,
        date: s.date,
        gold_stars: s.gold_stars,
        silver_stars: s.silver_stars,
        bronze_stars: s.bronze_stars,
        total_points: s.total_points,
        daily_points_target: s.daily_points_target,
        goal_met: s.goal_met,
        sentences_read: s.sentences_read,
        pages_completed: s.pages_completed,
        books_completed: s.books_completed,
        perfect_words: s.perfect_words,
        total_collaborative_attempts: s.total_collaborative_attempts,
        successful_collaborative_attempts: s.successful_collaborative_attempts,
        longest_streak: s.longest_streak,
        active_reading_time_ms: s.active_reading_time_ms,
        total_app_time_ms: s.total_app_time_ms,
        session_count: s.session_count,
        races_earned: s.races_earned,
        races_used: s.races_used,
        game_reward_claimed: s.game_reward_claimed,
        app_updated_at: s.updated_at
      }))

      const { error: statsError } = await supabase
        .from('daily_stats')
        .upsert(statsToUpsert, { onConflict: 'device_id,date' })

      if (statsError) console.error('Stats sync error:', statsError)
      else results.daily_stats = statsToUpsert.length
    }

    // Sync books
    if (payload.books?.length) {
      const booksToUpsert = payload.books.map(b => ({
        device_id: deviceUuid,
        local_book_id: b.local_id,
        title: b.title,
        total_pages: b.total_pages,
        pages_read: b.pages_read,
        current_page: b.current_page,
        current_sentence: b.current_sentence,
        completed_at: b.completed_at,
        total_stars_earned: b.total_stars_earned,
        total_reading_time_ms: b.total_reading_time_ms
      }))

      const { error: booksError } = await supabase
        .from('books')
        .upsert(booksToUpsert, { onConflict: 'device_id,local_book_id' })

      if (booksError) console.error('Books sync error:', booksError)
      else results.books = booksToUpsert.length
    }

    // Sync sessions
    if (payload.sessions?.length) {
      const sessionsToInsert = payload.sessions.map(s => ({
        device_id: deviceUuid,
        local_session_id: s.local_id,
        date: s.date,
        started_at: s.started_at,
        ended_at: s.ended_at,
        duration_ms: s.duration_ms,
        pages_read: s.pages_read,
        sentences_read: s.sentences_read,
        gold_stars: s.gold_stars,
        silver_stars: s.silver_stars,
        bronze_stars: s.bronze_stars,
        points_earned: s.points_earned
      }))

      // Use insert with ON CONFLICT DO NOTHING (sessions are append-only)
      const { error: sessionsError } = await supabase
        .from('reading_sessions')
        .insert(sessionsToInsert)

      if (sessionsError && !sessionsError.message.includes('duplicate')) {
        console.error('Sessions sync error:', sessionsError)
      } else {
        results.sessions = sessionsToInsert.length
      }
    }

    // Sync difficult words
    if (payload.difficult_words?.length) {
      const wordsToUpsert = payload.difficult_words.map(w => ({
        device_id: deviceUuid,
        word: w.word,
        normalized_word: w.normalized_word,
        total_attempts: w.total_attempts,
        successful_attempts: w.successful_attempts,
        consecutive_successes: w.consecutive_successes,
        mastery_level: w.mastery_level,
        last_attempt_at: w.last_attempt_at
      }))

      const { error: wordsError } = await supabase
        .from('difficult_words')
        .upsert(wordsToUpsert, { onConflict: 'device_id,normalized_word' })

      if (wordsError) console.error('Words sync error:', wordsError)
      else results.words = wordsToUpsert.length
    }

    // Log errors
    if (payload.errors?.length) {
      const errorsToInsert = payload.errors.map(e => ({
        device_id: deviceUuid,
        error_type: e.type,
        severity: e.severity,
        message: e.message,
        stack_trace: e.stack_trace,
        context: e.context,
        app_version: payload.app_version,
        current_screen: e.screen,
        occurred_at: e.occurred_at
      }))

      const { error: errorsError } = await supabase
        .from('error_logs')
        .insert(errorsToInsert)

      if (errorsError) console.error('Errors sync error:', errorsError)
      else results.errors = errorsToInsert.length
    }

    // Update sync state
    await supabase
      .from('sync_state')
      .upsert({
        device_id: deviceUuid,
        last_full_sync: new Date().toISOString(),
        ...(payload.daily_stats?.length && { last_daily_stats_sync: new Date().toISOString() }),
        ...(payload.books?.length && { last_books_sync: new Date().toISOString() }),
        ...(payload.sessions?.length && { last_sessions_sync: new Date().toISOString() }),
        ...(payload.difficult_words?.length && { last_words_sync: new Date().toISOString() })
      }, { onConflict: 'device_id' })

    console.log('Sync completed:', results)

    return new Response(
      JSON.stringify({ success: true, synced: results }),
      { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (error) {
    console.error('Sync error:', error)
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})
```

#### `query-stats` - Read endpoint for Claude Code skill

```typescript
// supabase/functions/query-stats/index.ts
import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { headers: corsHeaders })
  }

  try {
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    const url = new URL(req.url)
    const query = url.searchParams.get('q') || 'today'
    const deviceName = url.searchParams.get('device') || "Arlo's Tablet"

    // Get device ID
    const { data: device } = await supabase
      .from('devices')
      .select('id')
      .eq('device_name', deviceName)
      .single()

    if (!device) {
      return new Response(
        JSON.stringify({ error: 'Device not found', device_name: deviceName }),
        { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    let result: any

    switch (query) {
      case 'today':
        result = await supabase.rpc('get_today_summary', { p_device_id: device.id })
        break

      case 'week':
        result = await supabase.rpc('get_weekly_summary', { p_device_id: device.id })
        break

      case 'lifetime':
        result = await supabase.rpc('get_lifetime_summary', { p_device_id: device.id })
        break

      case 'errors':
        result = await supabase.rpc('get_recent_errors', {
          p_device_id: device.id,
          p_limit: 20
        })
        break

      case 'books':
        const { data: books } = await supabase
          .from('books')
          .select('title, total_pages, pages_read, current_page, completed_at, total_stars_earned')
          .eq('device_id', device.id)
          .order('synced_at', { ascending: false })
        result = { data: books }
        break

      case 'words':
        const { data: words } = await supabase
          .from('difficult_words')
          .select('word, total_attempts, successful_attempts, mastery_level')
          .eq('device_id', device.id)
          .order('mastery_level', { ascending: true })
          .limit(20)
        result = { data: words }
        break

      case 'history':
        const days = parseInt(url.searchParams.get('days') || '7')
        const { data: history } = await supabase
          .from('daily_stats')
          .select('date, gold_stars, silver_stars, bronze_stars, total_points, goal_met, sentences_read, active_reading_time_ms, longest_streak, races_earned')
          .eq('device_id', device.id)
          .order('date', { ascending: false })
          .limit(days)
        result = { data: history }
        break

      default:
        return new Response(
          JSON.stringify({
            error: 'Unknown query',
            valid_queries: ['today', 'week', 'lifetime', 'errors', 'books', 'words', 'history']
          }),
          { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
    }

    return new Response(
      JSON.stringify(result.data || result),
      { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (error) {
    console.error('Query error:', error)
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})
```

---

## Android Implementation

### 1. Dependencies

Add to `app/build.gradle.kts`:

```kotlin
// Supabase
implementation(platform("io.github.jan-tennert.supabase:bom:3.0.0"))
implementation("io.github.jan-tennert.supabase:functions-kt")
implementation("io.ktor:ktor-client-android:2.3.7")

// WorkManager for background sync
implementation("androidx.work:work-runtime-ktx:2.9.0")
```

### 2. New Files to Create

#### `app/src/main/java/com/example/arlo/sync/SupabaseConfig.kt`

```kotlin
package com.example.arlo.sync

import android.content.Context
import android.provider.Settings
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions

object SupabaseConfig {
    // These will be loaded from BuildConfig (set in local.properties)
    private var supabaseUrl: String = ""
    private var supabaseAnonKey: String = ""

    fun initialize(url: String, key: String) {
        supabaseUrl = url
        supabaseAnonKey = key
    }

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseAnonKey
        ) {
            install(Functions)
        }
    }

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }
}
```

#### `app/src/main/java/com/example/arlo/sync/SyncPayloads.kt`

```kotlin
package com.example.arlo.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncPayload(
    val device_id: String,
    val device_name: String = "Arlo's Tablet",
    val app_version: String? = null,
    val daily_stats: List<DailyStatsPayload>? = null,
    val books: List<BookPayload>? = null,
    val sessions: List<SessionPayload>? = null,
    val difficult_words: List<WordPayload>? = null,
    val errors: List<ErrorPayload>? = null
)

@Serializable
data class DailyStatsPayload(
    val date: String,
    val gold_stars: Int,
    val silver_stars: Int,
    val bronze_stars: Int,
    val total_points: Int,
    val daily_points_target: Int,
    val goal_met: Boolean,
    val sentences_read: Int,
    val pages_completed: Int,
    val books_completed: Int,
    val perfect_words: Int,
    val total_collaborative_attempts: Int,
    val successful_collaborative_attempts: Int,
    val longest_streak: Int,
    val active_reading_time_ms: Long,
    val total_app_time_ms: Long,
    val session_count: Int,
    val races_earned: Int,
    val races_used: Int,
    val game_reward_claimed: Boolean,
    val updated_at: String
)

@Serializable
data class BookPayload(
    val local_id: Long,
    val title: String,
    val total_pages: Int,
    val pages_read: Int,
    val current_page: Int,
    val current_sentence: Int,
    val completed_at: String? = null,
    val total_stars_earned: Int,
    val total_reading_time_ms: Long
)

@Serializable
data class SessionPayload(
    val local_id: Long,
    val date: String,
    val started_at: String,
    val ended_at: String?,
    val duration_ms: Long,
    val pages_read: Int,
    val sentences_read: Int,
    val gold_stars: Int,
    val silver_stars: Int,
    val bronze_stars: Int,
    val points_earned: Int
)

@Serializable
data class WordPayload(
    val word: String,
    val normalized_word: String,
    val total_attempts: Int,
    val successful_attempts: Int,
    val consecutive_successes: Int,
    val mastery_level: Int,
    val last_attempt_at: String
)

@Serializable
data class ErrorPayload(
    val type: String,
    val severity: String,
    val message: String,
    val stack_trace: String? = null,
    val context: Map<String, String>? = null,
    val screen: String? = null,
    val occurred_at: String
)

@Serializable
data class SyncResponse(
    val success: Boolean,
    val synced: SyncedCounts? = null,
    val error: String? = null
)

@Serializable
data class SyncedCounts(
    val device: String,
    val daily_stats: Int,
    val books: Int,
    val sessions: Int,
    val words: Int,
    val errors: Int
)
```

#### `app/src/main/java/com/example/arlo/sync/CloudSyncManager.kt`

```kotlin
package com.example.arlo.sync

import android.content.Context
import android.util.Log
import com.example.arlo.BuildConfig
import com.example.arlo.data.*
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

class CloudSyncManager(
    private val context: Context,
    private val statsRepository: ReadingStatsRepository,
    private val bookRepository: BookRepository
) {
    private val tag = "CloudSync"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    private var pendingErrors = mutableListOf<ErrorPayload>()

    /**
     * Perform a full sync of all changed data.
     * Call this periodically (every 15 min) and on app lifecycle events.
     */
    suspend fun syncAll(): SyncResponse = withContext(Dispatchers.IO) {
        try {
            val deviceId = SupabaseConfig.getDeviceId(context)
            val lastSync = prefs.getLong("last_sync", 0L)

            Log.d(tag, "Starting sync. Last sync: ${Date(lastSync)}")

            // Gather data to sync
            val dailyStats = gatherDailyStats(lastSync)
            val books = gatherBooks()
            val sessions = gatherSessions(lastSync)
            val words = gatherDifficultWords()
            val errors = pendingErrors.toList()

            // Build payload
            val payload = SyncPayload(
                device_id = deviceId,
                device_name = "Arlo's Tablet",
                app_version = BuildConfig.VERSION_NAME,
                daily_stats = dailyStats.takeIf { it.isNotEmpty() },
                books = books.takeIf { it.isNotEmpty() },
                sessions = sessions.takeIf { it.isNotEmpty() },
                difficult_words = words.takeIf { it.isNotEmpty() },
                errors = errors.takeIf { it.isNotEmpty() }
            )

            Log.d(tag, "Syncing: ${dailyStats.size} stats, ${books.size} books, ${sessions.size} sessions, ${words.size} words, ${errors.size} errors")

            // Call Edge Function
            val response = SupabaseConfig.client.functions(
                function = "sync-stats",
                body = payload
            )

            val result = response.body<SyncResponse>()

            if (result.success) {
                // Clear synced errors
                pendingErrors.clear()
                // Update last sync time
                prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
                Log.d(tag, "Sync successful: ${result.synced}")
            } else {
                Log.e(tag, "Sync failed: ${result.error}")
            }

            result

        } catch (e: Exception) {
            Log.e(tag, "Sync error", e)
            SyncResponse(success = false, error = e.message)
        }
    }

    /**
     * Log an error for cloud sync.
     * Errors are batched and sent on next sync.
     */
    fun logError(
        type: String,
        message: String,
        severity: String = "error",
        stackTrace: String? = null,
        context: Map<String, String>? = null,
        screen: String? = null
    ) {
        val error = ErrorPayload(
            type = type,
            severity = severity,
            message = message,
            stack_trace = stackTrace,
            context = context,
            screen = screen,
            occurred_at = isoFormat.format(Date())
        )
        pendingErrors.add(error)

        // Cap pending errors at 100
        if (pendingErrors.size > 100) {
            pendingErrors = pendingErrors.takeLast(100).toMutableList()
        }
    }

    private suspend fun gatherDailyStats(since: Long): List<DailyStatsPayload> {
        // Get recent stats (last 7 days to ensure nothing missed)
        val stats = statsRepository.getRecentDailyStats(7)
        return stats.map { s ->
            DailyStatsPayload(
                date = s.date,
                gold_stars = s.goldStars,
                silver_stars = s.silverStars,
                bronze_stars = s.bronzeStars,
                total_points = s.totalPoints,
                daily_points_target = s.dailyPointsTarget,
                goal_met = s.goalMet,
                sentences_read = s.sentencesRead,
                pages_completed = s.pagesCompleted,
                books_completed = s.booksCompleted,
                perfect_words = s.perfectWords,
                total_collaborative_attempts = s.totalCollaborativeAttempts,
                successful_collaborative_attempts = s.successfulCollaborativeAttempts,
                longest_streak = s.longestStreak,
                active_reading_time_ms = s.activeReadingTimeMs,
                total_app_time_ms = s.totalAppTimeMs,
                session_count = s.sessionCount,
                races_earned = s.racesEarned,
                races_used = s.racesUsed,
                game_reward_claimed = s.gameRewardClaimed,
                updated_at = isoFormat.format(Date(s.updatedAt))
            )
        }
    }

    private suspend fun gatherBooks(): List<BookPayload> {
        val books = bookRepository.getAllBooksWithInfo()
        return books.map { b ->
            val stats = statsRepository.getBookStats(b.book.id)
            BookPayload(
                local_id = b.book.id,
                title = b.book.title,
                total_pages = b.pageCount,
                pages_read = b.book.lastReadPageNumber,
                current_page = b.book.lastReadPageNumber,
                current_sentence = b.book.lastReadSentenceIndex,
                completed_at = stats?.completedAt?.let { isoFormat.format(Date(it)) },
                total_stars_earned = stats?.totalStarsEarned ?: 0,
                total_reading_time_ms = stats?.totalReadingTimeMs ?: 0
            )
        }
    }

    private suspend fun gatherSessions(since: Long): List<SessionPayload> {
        val today = dateFormat.format(Date())
        val weekAgo = dateFormat.format(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000))
        val sessions = statsRepository.getSessionsForRange(weekAgo, today)

        return sessions.filter { !it.isActive }.map { s ->
            SessionPayload(
                local_id = s.id,
                date = s.date,
                started_at = isoFormat.format(Date(s.startTimestamp)),
                ended_at = s.endTimestamp?.let { isoFormat.format(Date(it)) },
                duration_ms = s.durationMs,
                pages_read = s.pagesRead,
                sentences_read = s.sentencesRead,
                gold_stars = s.goldStars,
                silver_stars = s.silverStars,
                bronze_stars = s.bronzeStars,
                points_earned = s.pointsEarned
            )
        }
    }

    private suspend fun gatherDifficultWords(): List<WordPayload> {
        val words = statsRepository.getMostDifficultWords(50)
        return words.map { w ->
            WordPayload(
                word = w.word,
                normalized_word = w.normalizedWord,
                total_attempts = w.totalAttempts,
                successful_attempts = w.successfulAttempts,
                consecutive_successes = w.consecutiveSuccesses,
                mastery_level = w.masteryLevel,
                last_attempt_at = isoFormat.format(Date(w.lastAttemptDate))
            )
        }
    }
}
```

#### `app/src/main/java/com/example/arlo/sync/SyncWorker.kt`

```kotlin
package com.example.arlo.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as ArloApplication
            val syncManager = app.cloudSyncManager

            val result = syncManager.syncAll()

            if (result.success) {
                Log.d("SyncWorker", "Background sync completed successfully")
                Result.success()
            } else {
                Log.w("SyncWorker", "Sync failed: ${result.error}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync worker error", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "cloud_sync"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES  // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        }
    }
}
```

### 3. Integrate with ArloApplication

Update `ArloApplication.kt`:

```kotlin
// Add to ArloApplication.kt

class ArloApplication : Application() {
    // ... existing code ...

    val cloudSyncManager: CloudSyncManager by lazy {
        CloudSyncManager(
            context = this,
            statsRepository = statsRepository,
            bookRepository = bookRepository
        )
    }

    override fun onCreate() {
        super.onCreate()

        // ... existing init ...

        // Initialize Supabase
        SupabaseConfig.initialize(
            url = BuildConfig.SUPABASE_URL,
            key = BuildConfig.SUPABASE_ANON_KEY
        )

        // Schedule background sync
        SyncWorker.schedulePeriodicSync(this)
    }
}
```

### 4. Update local.properties

```properties
# Supabase configuration
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your_anon_key_here
```

### 5. Update build.gradle.kts

```kotlin
android {
    defaultConfig {
        // ... existing config ...

        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL") ?: ""}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
    }
}
```

---

## Claude Code Skill

Create `/Users/adam/.claude/commands/arlo-stats.md`:

```markdown
---
name: arlo-stats
description: Query Arlo's reading progress from Supabase
---

# Arlo Reading Stats

Query Arlo's reading progress from the cloud database.

## Usage

```
/arlo-stats [query]
```

## Available Queries

- `today` - Today's stats (default)
- `week` - This week's summary
- `lifetime` - All-time stats
- `history` - Last 7 days breakdown
- `books` - Book progress
- `words` - Difficult words
- `errors` - Recent errors for debugging

## Implementation

Use curl to query the Supabase Edge Function:

```bash
SUPABASE_URL="https://your-project.supabase.co"
SUPABASE_ANON_KEY="your-anon-key"

curl -s "${SUPABASE_URL}/functions/v1/query-stats?q=${1:-today}" \
  -H "Authorization: Bearer ${SUPABASE_ANON_KEY}" \
  -H "Content-Type: application/json" | jq .
```

## Example Output

### Today's Stats
```json
{
  "date": "2025-12-23",
  "total_stars": 45,
  "gold_stars": 12,
  "silver_stars": 18,
  "bronze_stars": 15,
  "total_points": 180,
  "goal_met": true,
  "reading_time_minutes": 28,
  "longest_streak": 8,
  "races_earned": 3
}
```

### Weekly Summary
```json
{
  "week_start": "2025-12-16",
  "total_stars": 287,
  "days_with_activity": 6,
  "goals_met": 5,
  "total_reading_minutes": 145,
  "best_streak": 12
}
```
```

---

## Implementation Phases

### Phase 1: Supabase Setup (Day 1)
1. Create Supabase project
2. Run database migration SQL
3. Deploy Edge Functions
4. Test functions with curl

### Phase 2: Android SDK (Day 2)
1. Add Supabase dependencies
2. Create sync data classes
3. Implement CloudSyncManager
4. Add error logging hooks

### Phase 3: Background Sync (Day 3)
5. Create SyncWorker
6. Integrate with ArloApplication
7. Add sync triggers (app pause/resume)
8. Test offline resilience

### Phase 4: Claude Code Integration (Day 4)
9. Create arlo-stats skill
10. Test queries
11. Add to development workflow

### Phase 5: Monitoring & Polish (Day 5)
12. Add sync status indicator (optional)
13. Monitor error logs
14. Fine-tune sync frequency
15. Document usage

---

## Key Design Decisions

1. **Batch Periodic Sync**: Sync every 15 minutes + on app lifecycle events. Avoids real-time complexity and handles offline gracefully.

2. **Idempotent Writes**: Using UPSERT with unique constraints prevents duplicate data even if sync runs multiple times.

3. **Edge Functions**: Centralizes auth/logic on server side. App only needs anon key, not service role key.

4. **Error Batching**: Errors queue locally and sync with regular data. Won't flood server on crash loops.

5. **No Auth Required**: Single-user app with device ID as identifier. Can add proper auth later if needed.

6. **Conservative Data**: Only syncing aggregated stats, not raw attempt data. Keeps payloads small.

7. **7-Day Window**: Always sync last 7 days of data to catch any missed updates. Server handles deduplication.

---

## Monitoring Queries

Useful SQL queries for the Supabase dashboard:

```sql
-- Last sync time
SELECT d.device_name, s.last_full_sync, d.last_seen_at
FROM devices d
JOIN sync_state s ON d.id = s.device_id;

-- This week's progress
SELECT date, total_points, goal_met, gold_stars + silver_stars + bronze_stars as stars
FROM daily_stats
WHERE date >= current_date - interval '7 days'
ORDER BY date DESC;

-- Error summary
SELECT error_type, count(*), max(occurred_at) as latest
FROM error_logs
WHERE occurred_at > now() - interval '24 hours'
GROUP BY error_type
ORDER BY count(*) DESC;

-- Reading time trend
SELECT
    date,
    round(active_reading_time_ms / 60000.0) as minutes,
    total_points
FROM daily_stats
ORDER BY date DESC
LIMIT 14;
```

---

## Security Considerations

1. **Anon Key Only**: App uses public anon key. All writes go through Edge Functions which use service role.

2. **RLS Enabled**: Tables have RLS policies. Only service role (Edge Functions) can write.

3. **Device ID Auth**: Simple but effective for single-user scenario. Attacker would need device ID to impersonate.

4. **No Sensitive Data**: Stats are non-sensitive. No personal info beyond device name.

5. **Rate Limiting**: Edge Functions have built-in rate limiting. Additional limits can be added.

## Future Enhancements

1. **Multi-device Support**: Add proper user auth for multiple tablets
2. **Real-time Dashboard**: Supabase Realtime for live updates
3. **Push Notifications**: Alert parent when goals are met
4. **Data Export**: CSV/PDF reports of reading progress
5. **Parent Mobile App**: Dedicated app for viewing stats

---

## Sources

- [Supabase Kotlin SDK](https://github.com/supabase-community/supabase-kt)
- [Supabase Android Quickstart](https://supabase.com/docs/guides/getting-started/quickstarts/kotlin)
- [PowerSync: Offline-First for Supabase](https://www.powersync.com/blog/bringing-offline-first-to-supabase)
- [Android Offline-First Supabase Library](https://github.com/novumlogic/Android-Offline-First-Supabase-Library)
- [Supabase Edge Functions Docs](https://supabase.com/docs/guides/functions)
