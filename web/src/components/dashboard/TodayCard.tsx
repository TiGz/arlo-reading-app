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

  const progress = Math.min(
    100,
    (stats.total_points / stats.daily_target) * 100
  );

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
