import { Star, BookOpen, TrendingUp } from 'lucide-react';
import type { Book } from '../../types';

interface Props {
  books: Book[];
}

export function BookList({ books }: Props) {
  if (books.length === 0) {
    return (
      <div className="bg-white rounded-2xl shadow-lg p-6 text-center text-gray-400">
        No books yet
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {books.map((book, index) => {
        // Calculate reading completion % based on stars per sentence
        // Stars are earned for reading sentences, so stars/sentences is a proxy for completion
        const completionPct =
          book.total_sentences > 0
            ? Math.min(100, Math.round((book.total_stars_earned / book.total_sentences) * 100))
            : 0;

        return (
          <div key={index} className="bg-white rounded-2xl shadow-lg p-6">
            <div className="flex items-start justify-between mb-3">
              <h3 className="font-semibold text-gray-800">{book.title}</h3>
              {book.completed_at && (
                <span className="text-xs bg-success/10 text-success px-2 py-1 rounded">
                  Completed
                </span>
              )}
            </div>

            {/* Page progress */}
            <div className="mb-3">
              <div className="flex justify-between text-sm text-gray-500 mb-1">
                <span>
                  Page {book.current_page} of {book.total_pages}
                </span>
                <span>
                  {book.total_pages > 0
                    ? Math.round((book.pages_read / book.total_pages) * 100)
                    : 0}
                  %
                </span>
              </div>
              <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
                <div
                  className="h-full bg-primary rounded-full"
                  style={{
                    width: `${
                      book.total_pages > 0
                        ? (book.pages_read / book.total_pages) * 100
                        : 0
                    }%`,
                  }}
                />
              </div>
            </div>

            {/* Stats row */}
            <div className="flex flex-wrap items-center gap-4 text-sm text-gray-500">
              <div className="flex items-center gap-1">
                <Star className="w-4 h-4 text-gold fill-gold" />
                <span>{book.total_stars_earned} stars</span>
              </div>

              <div className="flex items-center gap-1">
                <BookOpen className="w-4 h-4 text-primary" />
                <span>{book.total_sentences} sentences</span>
              </div>

              {book.total_sentences > 0 && (
                <div className="flex items-center gap-1">
                  <TrendingUp className="w-4 h-4 text-success" />
                  <span>{completionPct}% read</span>
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
