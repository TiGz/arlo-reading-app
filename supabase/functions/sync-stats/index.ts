// supabase/functions/sync-stats/index.ts
// Main sync endpoint for Arlo reading app

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type, x-device-id',
}

interface DailyStatsPayload {
  date: string
  gold_stars: number
  silver_stars: number
  bronze_stars: number
  total_points: number
  daily_points_target: number
  goal_met: boolean
  sentences_read: number
  pages_completed: number
  books_completed: number
  perfect_words: number
  total_collaborative_attempts: number
  successful_collaborative_attempts: number
  longest_streak: number
  active_reading_time_ms: number
  total_app_time_ms: number
  session_count: number
  races_earned: number
  races_used: number
  game_reward_claimed: boolean
  updated_at: string
}

interface BookPayload {
  local_id: number
  title: string
  total_pages: number
  pages_read: number
  current_page: number
  current_sentence: number
  completed_at?: string
  total_stars_earned: number
  total_reading_time_ms: number
}

interface SessionPayload {
  local_id: number
  date: string
  started_at: string
  ended_at?: string
  duration_ms: number
  pages_read: number
  sentences_read: number
  gold_stars: number
  silver_stars: number
  bronze_stars: number
  points_earned: number
}

interface WordPayload {
  word: string
  normalized_word: string
  total_attempts: number
  successful_attempts: number
  consecutive_successes: number
  mastery_level: number
  last_attempt_at: string
}

interface ErrorPayload {
  type: string
  severity: string
  message: string
  stack_trace?: string
  context?: Record<string, string>
  screen?: string
  occurred_at: string
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

      // Use insert - duplicates will fail silently (sessions are append-only)
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
