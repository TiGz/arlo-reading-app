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
              <span
                className={`px-2 py-0.5 text-xs rounded ${
                  severityColors[error.severity] || severityColors.error
                }`}
              >
                {error.severity}
              </span>
              <span className="text-sm font-mono text-gray-600">
                {error.error_type}
              </span>
              <span className="flex-1 text-sm text-gray-500 truncate">
                {error.message}
              </span>
              <span className="text-xs text-gray-400 hidden sm:block">
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
                  <div>
                    <span className="font-medium">Time:</span>
                    <span className="ml-2 text-gray-600">
                      {new Date(error.occurred_at).toLocaleString()}
                    </span>
                  </div>
                  {error.current_screen && (
                    <div>
                      <span className="font-medium">Screen:</span>
                      <span className="ml-2 text-gray-600">
                        {error.current_screen}
                      </span>
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
                      <span className="ml-2 text-gray-600">
                        {error.app_version}
                      </span>
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
