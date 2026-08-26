"use client";

import { useEffect } from "react";

// Without this boundary any render error escapes to Next's built-in global
// error page, which replaces the whole document and only offers a reload.
export default function AppError({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <main className="state-screen">
      <div>
        <h1>Something interrupted your space</h1>
        <p>{error.message || "An unexpected error stopped the last update."}</p>
        <button className="primary-button" onClick={() => retry()}>
          Try again
        </button>
      </div>
    </main>
  );
}
