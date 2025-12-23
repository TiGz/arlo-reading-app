// supabase/functions/query-stats/index.ts
// Read endpoint for Claude Code skill to query Arlo's reading stats

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
