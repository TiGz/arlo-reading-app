# Phase 3: React Dashboard SPA Implementation Plan

## Overview

A lightweight React SPA that displays Arlo's reading statistics, deployed to GitHub Pages for easy parent access from any device.

**Goal:** Provide a parent-friendly dashboard to monitor reading progress, celebrate achievements, and debug errors remotely.

---

## Architecture

### Why This Approach?

1. **Leverage existing infrastructure** - The `query-stats` Edge Function already provides all needed data
2. **No backend needed** - Static SPA calls Supabase Edge Functions directly
3. **Free hosting** - GitHub Pages requires zero infrastructure cost
4. **Simple deployment** - Push to main → GitHub Actions → Live

### Tech Stack

| Tool | Purpose | Why |
|------|---------|-----|
| React 18 | UI framework | Standard, well-supported |
| TypeScript | Type safety | Match existing Edge Function types |
| Vite | Build tool | Fast builds, tree-shaking |
| Tailwind CSS | Styling | Rapid development, responsive |
| Recharts | Charts | Simple, React-native charting |
| GitHub Actions | CI/CD | Automatic deployment to GH Pages |

---

## Directory Structure

```
web/
├── .github/
│   └── workflows/
│       └── deploy.yml          # GitHub Actions deployment
├── public/
│   ├── 404.html                # SPA routing fallback
│   └── favicon.ico
├── src/
│   ├── components/
│   │   ├── layout/
│   │   │   ├── Header.tsx
│   │   │   ├── TabNav.tsx
│   │   │   └── Container.tsx
│   │   ├── dashboard/
│   │   │   ├── TodayCard.tsx
│   │   │   ├── GoalProgress.tsx
│   │   │   ├── StreakBadge.tsx
│   │   │   └── QuickStats.tsx
│   │   ├── charts/
│   │   │   ├── WeeklyActivityChart.tsx
│   │   │   ├── StarDistributionPie.tsx
│   │   │   └── ReadingTimeBar.tsx
│   │   ├── books/
│   │   │   ├── BookCard.tsx
│   │   │   └── BookList.tsx
│   │   ├── errors/
│   │   │   ├── ErrorLogTable.tsx
│   │   │   ├── ErrorRow.tsx
│   │   │   └── ErrorDetail.tsx
│   │   └── words/
│   │       ├── WordMasteryGrid.tsx
│   │       └── WordCard.tsx
│   ├── hooks/
│   │   ├── useArloStats.ts     # Combined data fetching
│   │   ├── useTodayStats.ts
│   │   ├── useWeeklyStats.ts
│   │   ├── useBooks.ts
│   │   ├── useErrors.ts
│   │   └── useWords.ts
│   ├── api/
│   │   └── queryStats.ts       # API client wrapper
│   ├── types/
│   │   └── index.ts            # TypeScript interfaces
│   ├── pages/
│   │   ├── DashboardPage.tsx
│   │   ├── BooksPage.tsx
│   │   ├── ErrorsPage.tsx
│   │   └── WordsPage.tsx
│   ├── App.tsx
│   ├── main.tsx
│   └── index.css               # Tailwind imports
├── package.json
├── vite.config.ts
├── tailwind.config.js
├── tsconfig.json
└── README.md
```

---

## Implementation Tasks

### Task 1: Project Scaffolding

Create the web directory and initialize Vite + React:

```bash
cd /Users/adam/dev/personal/arlo-reading-app
npm create vite@latest web -- --template react-ts
cd web
npm install
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p
npm install recharts lucide-react
```

### Task 2: Configure Vite for GitHub Pages

**File:** `web/vite.config.ts`

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/arlo-reading-app/',  // GitHub repo name
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
})
```

### Task 3: Configure Tailwind

**File:** `web/tailwind.config.js`

```javascript
/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // Match Android app theme
        primary: '#00897B',    // Teal
        accent: '#7E57C2',     // Purple
        coral: '#FF7043',      // Coral
        cream: '#FFF8E1',      // Background
        success: '#4CAF50',    // Green
        error: '#F44336',      // Red
        gold: '#FFD700',       // Gold star
        silver: '#C0C0C0',     // Silver star
        bronze: '#CD7F32',     // Bronze star
      },
    },
  },
  plugins: [],
}
```

**File:** `web/src/index.css`

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

body {
  @apply bg-cream min-h-screen;
}
```

### Task 4: TypeScript Types

**File:** `web/src/types/index.ts`

```typescript
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
  mastery_level: number;  // 0-5
}

export interface ErrorLog {
  error_type: string;
  severity: string;
  message: string;
  context: Record<string, any> | null;
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
```

### Task 5: API Client

**File:** `web/src/api/queryStats.ts`

```typescript
const SUPABASE_URL = 'https://qqhkximogdndkfiunewm.supabase.co';
const FUNCTION_URL = `${SUPABASE_URL}/functions/v1/query-stats`;

export type QueryType = 'today' | 'week' | 'lifetime' | 'errors' | 'books' | 'words' | 'history';

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
export const getHistory = (days = 7) => queryStats<DailyHistoryItem[]>('history', { days });
```

### Task 6: Data Fetching Hooks

**File:** `web/src/hooks/useArloStats.ts`

```typescript
import { useState, useEffect } from 'react';
import * as api from '../api/queryStats';
import type { TodayStats, WeeklyStats, LifetimeStats, Book, ErrorLog, DifficultWord, DailyHistoryItem } from '../types';

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

  const fetchAll = async () => {
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
        api.getHistory(14),  // Last 2 weeks
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
  };

  useEffect(() => {
    fetchAll();
  }, []);

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
```

### Task 7: Main Dashboard Components

**File:** `web/src/components/dashboard/TodayCard.tsx`

```typescript
import { Star, Target, Flame, Trophy } from 'lucide-react';
import type { TodayStats } from '../../types';

interface Props {
  stats: TodayStats | null;
}

export function TodayCard({ stats }: Props) {
  if (!stats || Object.keys(stats).length === 0) {
    return (
      <div className="bg-white rounded-2xl shadow-lg p-6">
        <h2 className="text-lg font-semibold text-gray-500">Today</h2>
        <p className="text-gray-400 mt-4">No reading activity yet today</p>
      </div>
    );
  }

  const progress = Math.min(100, (stats.total_points / stats.daily_target) * 100);

  return (
    <div className="bg-white rounded-2xl shadow-lg p-6">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-700">Today's Progress</h2>
        {stats.goal_met && (
          <span className="flex items-center gap-1 text-success text-sm font-medium">
            <Trophy className="w-4 h-4" /> Goal Met!
          </span>
        )}
      </div>

      {/* Progress bar */}
      <div className="mb-6">
        <div className="flex justify-between text-sm text-gray-500 mb-1">
          <span>{stats.total_points} points</span>
          <span>{stats.daily_target} goal</span>
        </div>
        <div className="h-3 bg-gray-200 rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-all ${
              stats.goal_met ? 'bg-success' : 'bg-primary'
            }`}
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>

      {/* Stars */}
      <div className="grid grid-cols-3 gap-4 mb-6">
        <div className="text-center">
          <div className="flex items-center justify-center gap-1">
            <Star className="w-5 h-5 text-gold fill-gold" />
            <span className="text-2xl font-bold">{stats.gold_stars}</span>
          </div>
          <span className="text-xs text-gray-500">Gold</span>
        </div>
        <div className="text-center">
          <div className="flex items-center justify-center gap-1">
            <Star className="w-5 h-5 text-silver fill-silver" />
            <span className="text-2xl font-bold">{stats.silver_stars}</span>
          </div>
          <span className="text-xs text-gray-500">Silver</span>
        </div>
        <div className="text-center">
          <div className="flex items-center justify-center gap-1">
            <Star className="w-5 h-5 text-bronze fill-bronze" />
            <span className="text-2xl font-bold">{stats.bronze_stars}</span>
          </div>
          <span className="text-xs text-gray-500">Bronze</span>
        </div>
      </div>

      {/* Quick stats */}
      <div className="grid grid-cols-2 gap-4 text-sm">
        <div className="flex items-center gap-2">
          <Target className="w-4 h-4 text-primary" />
          <span>{stats.sentences_read} sentences</span>
        </div>
        <div className="flex items-center gap-2">
          <Flame className="w-4 h-4 text-coral" />
          <span>{stats.longest_streak} streak</span>
        </div>
      </div>
    </div>
  );
}
```

**File:** `web/src/components/charts/WeeklyActivityChart.tsx`

```typescript
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import type { DailyHistoryItem } from '../../types';

interface Props {
  history: DailyHistoryItem[];
}

export function WeeklyActivityChart({ history }: Props) {
  const data = history
    .slice(0, 7)
    .reverse()
    .map(item => ({
      date: new Date(item.date).toLocaleDateString('en-US', { weekday: 'short' }),
      points: item.total_points,
      goalMet: item.goal_met,
    }));

  return (
    <div className="bg-white rounded-2xl shadow-lg p-6">
      <h2 className="text-lg font-semibold text-gray-700 mb-4">This Week</h2>
      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data}>
            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
            <YAxis tick={{ fontSize: 12 }} />
            <Tooltip />
            <Bar dataKey="points" radius={[4, 4, 0, 0]}>
              {data.map((entry, index) => (
                <Cell
                  key={index}
                  fill={entry.goalMet ? '#4CAF50' : '#00897B'}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
```

**File:** `web/src/components/errors/ErrorLogTable.tsx`

```typescript
import { useState } from 'react';
import { AlertCircle, ChevronDown, ChevronUp } from 'lucide-react';
import type { ErrorLog } from '../../types';

interface Props {
  errors: ErrorLog[];
}

export function ErrorLogTable({ errors }: Props) {
  const [expandedId, setExpandedId] = useState<number | null>(null);

  if (errors.length === 0) {
    return (
      <div className="bg-white rounded-2xl shadow-lg p-6">
        <h2 className="text-lg font-semibold text-gray-700 mb-4">Error Log</h2>
        <div className="text-center py-8 text-gray-400">
          <AlertCircle className="w-12 h-12 mx-auto mb-2 opacity-50" />
          <p>No errors recorded</p>
        </div>
      </div>
    );
  }

  const severityColors: Record<string, string> = {
    fatal: 'bg-red-100 text-red-800',
    error: 'bg-orange-100 text-orange-800',
    warning: 'bg-yellow-100 text-yellow-800',
    info: 'bg-blue-100 text-blue-800',
  };

  return (
    <div className="bg-white rounded-2xl shadow-lg p-6">
      <h2 className="text-lg font-semibold text-gray-700 mb-4">
        Error Log ({errors.length})
      </h2>
      <div className="space-y-2">
        {errors.map((error, index) => (
          <div key={index} className="border rounded-lg overflow-hidden">
            <div
              className="flex items-center gap-3 p-3 cursor-pointer hover:bg-gray-50"
              onClick={() => setExpandedId(expandedId === index ? null : index)}
            >
              <span className={`px-2 py-0.5 text-xs rounded ${
                severityColors[error.severity] || severityColors.error
              }`}>
                {error.severity}
              </span>
              <span className="text-sm font-mono text-gray-600">
                {error.error_type}
              </span>
              <span className="flex-1 text-sm text-gray-500 truncate">
                {error.message}
              </span>
              <span className="text-xs text-gray-400">
                {new Date(error.occurred_at).toLocaleString()}
              </span>
              {expandedId === index ? (
                <ChevronUp className="w-4 h-4 text-gray-400" />
              ) : (
                <ChevronDown className="w-4 h-4 text-gray-400" />
              )}
            </div>

            {expandedId === index && (
              <div className="p-3 bg-gray-50 border-t">
                <div className="text-sm space-y-2">
                  <div>
                    <span className="font-medium">Message:</span>
                    <p className="text-gray-600">{error.message}</p>
                  </div>
                  {error.current_screen && (
                    <div>
                      <span className="font-medium">Screen:</span>
                      <span className="ml-2 text-gray-600">{error.current_screen}</span>
                    </div>
                  )}
                  {error.context && (
                    <div>
                      <span className="font-medium">Context:</span>
                      <pre className="mt-1 p-2 bg-gray-100 rounded text-xs overflow-x-auto">
                        {JSON.stringify(error.context, null, 2)}
                      </pre>
                    </div>
                  )}
                  {error.app_version && (
                    <div>
                      <span className="font-medium">App Version:</span>
                      <span className="ml-2 text-gray-600">{error.app_version}</span>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
```

### Task 8: Main App Component

**File:** `web/src/App.tsx`

```typescript
import { useState } from 'react';
import { useArloStats } from './hooks/useArloStats';
import { TodayCard } from './components/dashboard/TodayCard';
import { WeeklyActivityChart } from './components/charts/WeeklyActivityChart';
import { ErrorLogTable } from './components/errors/ErrorLogTable';
import { RefreshCw, BookOpen, AlertTriangle, Star, Book } from 'lucide-react';

type Tab = 'dashboard' | 'books' | 'errors' | 'words';

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('dashboard');
  const { today, weekly, lifetime, books, errors, words, history, loading, error, refresh } = useArloStats();

  const tabs: { id: Tab; label: string; icon: typeof Star }[] = [
    { id: 'dashboard', label: 'Dashboard', icon: Star },
    { id: 'books', label: 'Books', icon: Book },
    { id: 'errors', label: 'Errors', icon: AlertTriangle },
    { id: 'words', label: 'Words', icon: BookOpen },
  ];

  return (
    <div className="min-h-screen bg-cream">
      {/* Header */}
      <header className="bg-primary text-white p-4 shadow-md">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold">Arlo's Reading Dashboard</h1>
            <p className="text-sm text-white/80">Reading progress tracker</p>
          </div>
          <button
            onClick={refresh}
            disabled={loading}
            className="p-2 rounded-full hover:bg-white/10 transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </header>

      {/* Tab Navigation */}
      <nav className="bg-white shadow-sm sticky top-0 z-10">
        <div className="max-w-4xl mx-auto flex">
          {tabs.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex-1 flex items-center justify-center gap-2 py-3 border-b-2 transition-colors ${
                activeTab === tab.id
                  ? 'border-primary text-primary'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              <tab.icon className="w-4 h-4" />
              <span className="text-sm font-medium">{tab.label}</span>
              {tab.id === 'errors' && errors.length > 0 && (
                <span className="bg-error text-white text-xs px-1.5 py-0.5 rounded-full">
                  {errors.length}
                </span>
              )}
            </button>
          ))}
        </div>
      </nav>

      {/* Content */}
      <main className="max-w-4xl mx-auto p-4 space-y-4">
        {error && (
          <div className="bg-red-50 text-red-800 p-4 rounded-lg">
            {error}
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <RefreshCw className="w-8 h-8 animate-spin text-primary" />
          </div>
        ) : (
          <>
            {activeTab === 'dashboard' && (
              <div className="space-y-4">
                <TodayCard stats={today} />
                <WeeklyActivityChart history={history} />

                {/* Lifetime stats */}
                {lifetime && (
                  <div className="bg-white rounded-2xl shadow-lg p-6">
                    <h2 className="text-lg font-semibold text-gray-700 mb-4">All Time</h2>
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-center">
                      <div>
                        <div className="text-2xl font-bold text-primary">{lifetime.total_stars}</div>
                        <div className="text-xs text-gray-500">Total Stars</div>
                      </div>
                      <div>
                        <div className="text-2xl font-bold text-primary">{lifetime.books_completed}</div>
                        <div className="text-xs text-gray-500">Books Finished</div>
                      </div>
                      <div>
                        <div className="text-2xl font-bold text-primary">{lifetime.best_streak}</div>
                        <div className="text-xs text-gray-500">Best Streak</div>
                      </div>
                      <div>
                        <div className="text-2xl font-bold text-primary">{lifetime.total_reading_hours}h</div>
                        <div className="text-xs text-gray-500">Reading Time</div>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            )}

            {activeTab === 'books' && (
              <div className="space-y-4">
                {books.length === 0 ? (
                  <div className="bg-white rounded-2xl shadow-lg p-6 text-center text-gray-400">
                    No books yet
                  </div>
                ) : (
                  books.map((book, index) => (
                    <div key={index} className="bg-white rounded-2xl shadow-lg p-6">
                      <div className="flex items-start justify-between mb-3">
                        <h3 className="font-semibold text-gray-800">{book.title}</h3>
                        {book.completed_at && (
                          <span className="text-xs bg-success/10 text-success px-2 py-1 rounded">
                            Completed
                          </span>
                        )}
                      </div>
                      <div className="mb-2">
                        <div className="flex justify-between text-sm text-gray-500 mb-1">
                          <span>Page {book.current_page} of {book.total_pages}</span>
                          <span>{Math.round((book.pages_read / book.total_pages) * 100)}%</span>
                        </div>
                        <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
                          <div
                            className="h-full bg-primary rounded-full"
                            style={{ width: `${(book.pages_read / book.total_pages) * 100}%` }}
                          />
                        </div>
                      </div>
                      <div className="flex items-center gap-1 text-sm text-gray-500">
                        <Star className="w-4 h-4 text-gold fill-gold" />
                        <span>{book.total_stars_earned} stars earned</span>
                      </div>
                    </div>
                  ))
                )}
              </div>
            )}

            {activeTab === 'errors' && <ErrorLogTable errors={errors} />}

            {activeTab === 'words' && (
              <div className="bg-white rounded-2xl shadow-lg p-6">
                <h2 className="text-lg font-semibold text-gray-700 mb-4">
                  Words to Practice ({words.length})
                </h2>
                {words.length === 0 ? (
                  <p className="text-center text-gray-400 py-4">No difficult words tracked yet</p>
                ) : (
                  <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
                    {words.map((word, index) => {
                      const accuracy = word.total_attempts > 0
                        ? Math.round((word.successful_attempts / word.total_attempts) * 100)
                        : 0;
                      const masteryColors = [
                        'bg-red-100 text-red-800',
                        'bg-orange-100 text-orange-800',
                        'bg-yellow-100 text-yellow-800',
                        'bg-lime-100 text-lime-800',
                        'bg-green-100 text-green-800',
                        'bg-emerald-100 text-emerald-800',
                      ];
                      return (
                        <div
                          key={index}
                          className={`p-3 rounded-lg ${masteryColors[word.mastery_level]}`}
                        >
                          <div className="font-medium">{word.word}</div>
                          <div className="text-xs opacity-75">
                            {accuracy}% • Level {word.mastery_level}/5
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </main>

      {/* Footer */}
      <footer className="text-center text-xs text-gray-400 py-4">
        Last synced from Arlo's Tablet
      </footer>
    </div>
  );
}
```

### Task 9: GitHub Actions Deployment

**File:** `web/.github/workflows/deploy.yml`

```yaml
name: Deploy to GitHub Pages

on:
  push:
    branches:
      - main
    paths:
      - 'web/**'
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: "pages"
  cancel-in-progress: false

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: web
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: web/package-lock.json

      - name: Install dependencies
        run: npm ci

      - name: Build
        run: npm run build

      - name: Setup Pages
        uses: actions/configure-pages@v4

      - name: Upload artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: web/dist

  deploy:
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    needs: build
    steps:
      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v4
```

### Task 10: SPA Routing Fallback

**File:** `web/public/404.html`

```html
<!DOCTYPE html>
<html>
  <head>
    <meta charset="utf-8">
    <title>Arlo's Reading Dashboard</title>
    <script>
      // Single-page app redirect
      // Redirect 404s back to index.html for client-side routing
      const path = window.location.pathname;
      const repo = '/arlo-reading-app';
      if (path.startsWith(repo)) {
        const route = path.slice(repo.length) || '/';
        sessionStorage.setItem('redirect', route);
        window.location.replace(repo + '/');
      }
    </script>
  </head>
  <body>
    Redirecting...
  </body>
</html>
```

---

## Deployment Steps

1. **Create the web directory and initialize:**
   ```bash
   cd /Users/adam/dev/personal/arlo-reading-app
   npm create vite@latest web -- --template react-ts
   cd web
   npm install tailwindcss postcss autoprefixer recharts lucide-react
   npx tailwindcss init -p
   ```

2. **Copy component files from this plan**

3. **Test locally:**
   ```bash
   npm run dev
   ```

4. **Enable GitHub Pages in repository settings:**
   - Go to Settings → Pages
   - Source: GitHub Actions

5. **Push changes:**
   ```bash
   git add web/
   git commit -m "feat: Add React dashboard SPA"
   git push origin main
   ```

6. **Access dashboard:**
   `https://tigz.github.io/arlo-reading-app/`

---

## Future Enhancements

1. **Real-time updates** - Add polling or WebSocket for live sync
2. **Date range picker** - Filter history by custom date ranges
3. **Export to PDF** - Generate progress reports for teachers
4. **Push notifications** - Alert when reading goal is met
5. **Comparison view** - Week-over-week progress comparison
6. **Achievement gallery** - Show unlocked achievements with animations

---

## Success Criteria

1. Dashboard loads and displays reading stats from Supabase
2. Error log shows expandable details with stack traces
3. Word mastery grid shows progression by color
4. Charts render correctly on mobile and desktop
5. GitHub Pages deployment works automatically on push
6. Page loads in < 2 seconds on first visit

---

## Estimated Effort

- **Project setup:** 30 minutes
- **Core components:** 2 hours
- **Charts and visualizations:** 1 hour
- **Error log drill-down:** 30 minutes
- **GitHub Actions setup:** 30 minutes
- **Testing and polish:** 1 hour

**Total: ~5-6 hours**
