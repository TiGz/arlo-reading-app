import { useState } from 'react';
import { useArloStats } from './hooks/useArloStats';
import { TodayCard } from './components/dashboard/TodayCard';
import { LifetimeStats } from './components/dashboard/LifetimeStats';
import { WeeklyActivityChart } from './components/charts/WeeklyActivityChart';
import { BookList } from './components/books/BookList';
import { ErrorLogTable } from './components/errors/ErrorLogTable';
import { WordMasteryGrid } from './components/words/WordMasteryGrid';
import {
  RefreshCw,
  BookOpen,
  AlertTriangle,
  Star,
  Book,
} from 'lucide-react';

type Tab = 'dashboard' | 'books' | 'errors' | 'words';

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('dashboard');
  const {
    today,
    weekly,
    lifetime,
    books,
    errors,
    words,
    history,
    loading,
    error,
    refresh,
  } = useArloStats();

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
          {tabs.map((tab) => (
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
          <div className="bg-red-50 text-red-800 p-4 rounded-lg">{error}</div>
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
                <LifetimeStats stats={lifetime} />

                {/* Weekly summary */}
                {weekly && Object.keys(weekly).length > 0 && (
                  <div className="bg-white rounded-2xl shadow-lg p-6">
                    <h2 className="text-lg font-semibold text-gray-700 mb-4">
                      This Week Summary
                    </h2>
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-center">
                      <div>
                        <div className="text-2xl font-bold text-primary">
                          {weekly.days_with_activity}/7
                        </div>
                        <div className="text-xs text-gray-500">Active Days</div>
                      </div>
                      <div>
                        <div className="text-2xl font-bold text-primary">
                          {weekly.goals_met}
                        </div>
                        <div className="text-xs text-gray-500">Goals Met</div>
                      </div>
                      <div>
                        <div className="text-2xl font-bold text-primary">
                          {weekly.total_stars}
                        </div>
                        <div className="text-xs text-gray-500">Stars Earned</div>
                      </div>
                      <div>
                        <div className="text-2xl font-bold text-primary">
                          {weekly.total_reading_minutes}m
                        </div>
                        <div className="text-xs text-gray-500">Reading</div>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            )}

            {activeTab === 'books' && <BookList books={books} />}

            {activeTab === 'errors' && <ErrorLogTable errors={errors} />}

            {activeTab === 'words' && <WordMasteryGrid words={words} />}
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
