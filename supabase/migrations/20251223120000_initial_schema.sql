-- Arlo Reading App - Cloud Sync Schema
-- Initial migration for Supabase

-- Enable UUID extension in extensions schema and grant usage
create extension if not exists "uuid-ossp" with schema extensions;

-- ============================================
-- CORE TABLES
-- ============================================

-- Device registration (single device for now, extensible later)
create table devices (
    id uuid primary key default gen_random_uuid(),
    device_name text not null default 'Arlo''s Tablet',
    device_id text unique not null,  -- Android device ID
    app_version text,
    last_seen_at timestamptz default now(),
    created_at timestamptz default now()
);

-- Daily reading statistics (mirrors Room DailyStats)
create table daily_stats (
    id uuid primary key default gen_random_uuid(),
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
    id uuid primary key default gen_random_uuid(),
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
    id uuid primary key default gen_random_uuid(),
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
    id uuid primary key default gen_random_uuid(),
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
    id uuid primary key default gen_random_uuid(),
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
    id uuid primary key default gen_random_uuid(),
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
