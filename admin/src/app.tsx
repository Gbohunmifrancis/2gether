import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider, type RouterHistory } from "@tanstack/react-router";
import { ApiError } from "./api/client";
import { onSessionChange } from "./auth/session";
import { ToastProvider } from "./components/feedback";
import { makeRouter } from "./router";

/** The whole dashboard: query cache, router and the sign-out wiring. Tests pass a memory history. */
export function createApp(history?: RouterHistory) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        // Don't retry what the server refused on purpose; retry blips once.
        retry: (failures, error) => failures < 1 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
      },
    },
  });
  const router = makeRouter(queryClient, history);

  // Signed out (button, expiry or a 401): forget everything fetched and let the route guard redirect.
  const unsubscribe = onSessionChange((session) => {
    if (!session) {
      queryClient.clear();
      void router.invalidate();
    }
  });

  const element = (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>
  );
  return { queryClient, router, element, dispose: unsubscribe };
}
