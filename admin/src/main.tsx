import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createApp } from "./app";
import "./styles.css";
import "./console.css";

async function start() {
  // Demo mode answers every /api/admin call in the browser. The check is a build-time constant, so a normal build
  // leaves the demo out entirely.
  if (import.meta.env.VITE_DEMO === "1") {
    const { installDemo } = await import("./demo/server");
    installDemo();
  }
  const { element } = createApp();
  createRoot(document.getElementById("root")!).render(<StrictMode>{element}</StrictMode>);
}

void start();
