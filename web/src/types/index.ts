// Mirror Edge Function response types

export interface TodayStats {
  date: string;
  total_stars: number;
  gold_stars: number;
  silver_stars: number;
  bronze_stars: number;
  total_points: number;
  goal_met: boolean;
  daily_target: number;
  sentences_read: number;
  reading_time_minutes: number;
  longest_streak: number;
  races_earned: number;
  races_used: number;
}

export interface WeeklyStats {
  week_start: string;
  week_end: string;
  total_stars: number;
  gold_stars: number;
  silver_stars: number;
  bronze_stars: number;
  total_points: number;
  days_with_activity: number;
  goals_met: number;
  total_reading_minutes: number;
  sentences_read: number;
  best_streak: number;
  total_races: number;
}

export interface LifetimeStats {
  total_stars: number;
  gold_stars: number;
  silver_stars: number;
  bronze_stars: number;
  total_points: number;
  total_days: number;
  days_with_activity: number;
  goals_met: number;
  total_reading_hours: number;
  sentences_read: number;
  pages_completed: number;
  books_completed: number;
  best_streak: number;
  total_races: number;
  first_activity: string;
  last_activity: string;
}

export interface Book {
  title: string;
  total_pages: number;
  pages_read: number;
  current_page: number;
  completed_at: string | null;
  total_stars_earned: number;
}

export interface DifficultWord {
  word: string;
  total_attempts: number;
  successful_attempts: number;
  mastery_level: number; // 0-5
}

export interface ErrorLog {
  error_type: string;
  severity: string;
  message: string;
  context: Record<string, unknown> | null;
  current_screen: string | null;
  app_version: string | null;
  occurred_at: string;
}

export interface DailyHistoryItem {
  date: string;
  gold_stars: number;
  silver_stars: number;
  bronze_stars: number;
  total_points: number;
  goal_met: boolean;
  sentences_read: number;
  active_reading_time_ms: number;
  longest_streak: number;
  races_earned: number;
}
