import type {
  TodayStats,
  WeeklyStats,
  LifetimeStats,
  Book,
  DifficultWord,
  ErrorLog,
  DailyHistoryItem,
} from '../types';

const SUPABASE_URL = 'https://qqhkximogdndkfiunewm.supabase.co';
const SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFxaGt4aW1vZ2RuZGtmaXVuZXdtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjY0OTE3MTEsImV4cCI6MjA4MjA2NzcxMX0.UmxARCF72W6zmsgi1zzayZxS1-0P6-luq8XQZHyolBc';
const FUNCTION_URL = `${SUPABASE_URL}/functions/v1/query-stats`;

export type QueryType =
  | 'today'
  | 'week'
  | 'lifetime'
  | 'errors'
  | 'books'
  | 'words'
  | 'history';

interface QueryOptions {
  device?: string;
  days?: number;
}

export async function queryStats<T>(
  query: QueryType,
  options: QueryOptions = {}
): Promise<T> {
  const params = new URLSearchParams({ q: query });

  if (options.device) {
    params.set('device', options.device);
  }
  if (options.days && query === 'history') {
    params.set('days', String(options.days));
  }

  const response = await fetch(`${FUNCTION_URL}?${params}`, {
    headers: {
      'Authorization': `Bearer ${SUPABASE_ANON_KEY}`,
      'apikey': SUPABASE_ANON_KEY,
    },
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to fetch stats');
  }

  return response.json();
}

// Convenience functions
export const getTodayStats = () => queryStats<TodayStats>('today');
export const getWeeklyStats = () => queryStats<WeeklyStats>('week');
export const getLifetimeStats = () => queryStats<LifetimeStats>('lifetime');
export const getBooks = () => queryStats<Book[]>('books');
export const getErrors = () => queryStats<ErrorLog[]>('errors');
export const getWords = () => queryStats<DifficultWord[]>('words');
export const getHistory = (days = 7) =>
  queryStats<DailyHistoryItem[]>('history', { days });
