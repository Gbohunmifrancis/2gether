import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Twogether — your private space",
  description: "Connect, play and grow together.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
