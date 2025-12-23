import { useState, useEffect, useCallback } from 'react';
import * as api from '../api/queryStats';
import type {
  TodayStats,
  WeeklyStats,
  LifetimeStats,
  Book,
  ErrorLog,
  DifficultWord,
  DailyHistoryItem,
} from '../types';

interface ArloStats {
  today: TodayStats | null;
  weekly: WeeklyStats | null;
  lifetime: LifetimeStats | null;
  books: Book[];
  errors: ErrorLog[];
  words: DifficultWord[];
  history: DailyHistoryItem[];
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export function useArloStats(): ArloStats {
  const [today, setToday] = useState<TodayStats | null>(null);
  const [weekly, setWeekly] = useState<WeeklyStats | null>(null);
  const [lifetime, setLifetime] = useState<LifetimeStats | null>(null);
  const [books, setBooks] = useState<Book[]>([]);
  const [errors, setErrors] = useState<ErrorLog[]>([]);
  const [words, setWords] = useState<DifficultWord[]>([]);
  const [history, setHistory] = useState<DailyHistoryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchAll = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      const [
        todayData,
        weeklyData,
        lifetimeData,
        booksData,
        errorsData,
        wordsData,
        historyData,
      ] = await Promise.all([
        api.getTodayStats(),
        api.getWeeklyStats(),
        api.getLifetimeStats(),
        api.getBooks(),
        api.getErrors(),
        api.getWords(),
        api.getHistory(14), // Last 2 weeks
      ]);

      setToday(todayData);
      setWeekly(weeklyData);
      setLifetime(lifetimeData);
      setBooks(booksData || []);
      setErrors(errorsData || []);
      setWords(wordsData || []);
      setHistory(historyData || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load stats');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  return {
    today,
    weekly,
    lifetime,
    books,
    errors,
    words,
    history,
    loading,
    error,
    refresh: fetchAll,
  };
}
