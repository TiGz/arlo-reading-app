import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from 'recharts';
import type { DailyHistoryItem } from '../../types';

interface Props {
  history: DailyHistoryItem[];
}

export function WeeklyActivityChart({ history }: Props) {
  if (history.length === 0) {
    return (
      <div className="bg-white rounded-2xl shadow-lg p-6">
        <h2 className="text-lg font-semibold text-gray-700 mb-4">This Week</h2>
        <div className="h-48 flex items-center justify-center text-gray-400">
          No activity data yet
        </div>
      </div>
    );
  }

  const data = history
    .slice(0, 7)
    .reverse()
    .map((item) => ({
      date: new Date(item.date).toLocaleDateString('en-US', {
        weekday: 'short',
      }),
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
