/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development the API is proxied, so the browser sees one origin and needs no CORS.
// A deployed build calls VITE_API_URL directly; add the dashboard's origin to the API's Frontend__Origin.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": { target: process.env.HEARTPACT_API_URL ?? "http://localhost:5135", changeOrigin: true } },
  },
  build: { sourcemap: false },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    restoreMocks: true,
  },
});
