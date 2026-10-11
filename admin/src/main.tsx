import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createApp } from "./app";
import "./styles.css";

const { element } = createApp();

createRoot(document.getElementById("root")!).render(<StrictMode>{element}</StrictMode>);
