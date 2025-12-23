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

  const response = await fetch(`${FUNCTION_URL}?${params}`);

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
