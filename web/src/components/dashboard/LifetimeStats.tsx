import type { LifetimeStats as LifetimeStatsType } from '../../types';

interface Props {
  stats: LifetimeStatsType | null;
}

export function LifetimeStats({ stats }: Props) {
  if (!stats || Object.keys(stats).length === 0) {
    return null;
  }

  return (
    <div className="bg-white rounded-2xl shadow-lg p-6">
      <h2 className="text-lg font-semibold text-gray-700 mb-4">All Time</h2>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-center">
        <div>
          <div className="text-2xl font-bold text-primary">
            {stats.total_stars}
          </div>
          <div className="text-xs text-gray-500">Total Stars</div>
        </div>
        <div>
          <div className="text-2xl font-bold text-primary">
            {stats.books_completed}
          </div>
          <div className="text-xs text-gray-500">Books Finished</div>
        </div>
        <div>
          <div className="text-2xl font-bold text-primary">
            {stats.best_streak}
          </div>
          <div className="text-xs text-gray-500">Best Streak</div>
        </div>
        <div>
          <div className="text-2xl font-bold text-primary">
            {stats.total_reading_hours}h
          </div>
          <div className="text-xs text-gray-500">Reading Time</div>
        </div>
      </div>
    </div>
  );
}
