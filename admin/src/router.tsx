import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, createRoute, createRouter, Outlet, redirect, type RouterHistory } from "@tanstack/react-router";
import { getSession } from "./auth/session";
import { Shell } from "./components/Shell";
import { AuditPage } from "./pages/Audit";
import { EngagementPage } from "./pages/Engagement";
import { NotFound } from "./pages/NotFound";
import { OverviewPage } from "./pages/Overview";
import { PaymentsPage } from "./pages/Payments";
import { RevenuePage } from "./pages/Revenue";
import { SignInPage } from "./pages/SignIn";
import { SystemPage } from "./pages/System";
import { UserPage } from "./pages/User";
import { UsersPage } from "./pages/Users";
import { auditSearch, paymentsSearch, rangeSearch, signInSearch, usersSearch } from "./lib/search";

export interface RouterContext {
  queryClient: QueryClient;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
  notFoundComponent: NotFound,
});

const signInRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/sign-in",
  validateSearch: signInSearch,
  beforeLoad: () => {
    if (getSession()) throw redirect({ to: "/" });
  },
  component: SignInPage,
});

/** Everything else needs a live admin session; without one, go sign in and come back. */
const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "_app",
  beforeLoad: ({ location }) => {
    if (!getSession()) {
      throw redirect({ to: "/sign-in", search: { redirect: location.href } });
    }
  },
  component: Shell,
});

const overviewRoute = createRoute({ getParentRoute: () => appRoute, path: "/", validateSearch: rangeSearch, component: OverviewPage });
const engagementRoute = createRoute({ getParentRoute: () => appRoute, path: "/engagement", validateSearch: rangeSearch, component: EngagementPage });
const revenueRoute = createRoute({ getParentRoute: () => appRoute, path: "/revenue", validateSearch: rangeSearch, component: RevenuePage });
const usersRoute = createRoute({ getParentRoute: () => appRoute, path: "/users", validateSearch: usersSearch, component: UsersPage });
const userRoute = createRoute({ getParentRoute: () => appRoute, path: "/users/$userId", component: UserPage });
const paymentsRoute = createRoute({ getParentRoute: () => appRoute, path: "/payments", validateSearch: paymentsSearch, component: PaymentsPage });
const auditRoute = createRoute({ getParentRoute: () => appRoute, path: "/audit", validateSearch: auditSearch, component: AuditPage });
const systemRoute = createRoute({ getParentRoute: () => appRoute, path: "/system", component: SystemPage });

const routeTree = rootRoute.addChildren([
  signInRoute,
  appRoute.addChildren([overviewRoute, engagementRoute, revenueRoute, usersRoute, userRoute, paymentsRoute, auditRoute, systemRoute]),
]);

export function makeRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({
    routeTree,
    history,
    context: { queryClient },
    defaultPreload: "intent",
    // Queries own freshness; the router just renders.
    defaultPreloadStaleTime: 0,
    scrollRestoration: true,
  });
}

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof makeRouter>;
  }
}
