import type { DifficultWord } from '../../types';

interface Props {
  words: DifficultWord[];
}

export function WordMasteryGrid({ words }: Props) {
  if (words.length === 0) {
    return (
      <div className="bg-white rounded-2xl shadow-lg p-6">
        <h2 className="text-lg font-semibold text-gray-700 mb-4">
          Words to Practice
        </h2>
        <p className="text-center text-gray-400 py-4">
          No difficult words tracked yet
        </p>
      </div>
    );
  }

  const masteryColors = [
    'bg-red-100 text-red-800',
    'bg-orange-100 text-orange-800',
    'bg-yellow-100 text-yellow-800',
    'bg-lime-100 text-lime-800',
    'bg-green-100 text-green-800',
    'bg-emerald-100 text-emerald-800',
  ];

  return (
    <div className="bg-white rounded-2xl shadow-lg p-6">
      <h2 className="text-lg font-semibold text-gray-700 mb-4">
        Words to Practice ({words.length})
      </h2>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
        {words.map((word, index) => {
          const accuracy =
            word.total_attempts > 0
              ? Math.round(
                  (word.successful_attempts / word.total_attempts) * 100
                )
              : 0;
          return (
            <div
              key={index}
              className={`p-3 rounded-lg ${masteryColors[word.mastery_level]}`}
            >
              <div className="font-medium">{word.word}</div>
              <div className="text-xs opacity-75">
                {accuracy}% &bull; Level {word.mastery_level}/5
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
