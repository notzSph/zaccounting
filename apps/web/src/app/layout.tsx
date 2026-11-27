import type { Metadata } from "next";

import "@styles/globals.scss";
import "@styles/framework.scss";

export const metadata: Metadata = {
  title: "zAccounting",
  description: "Self-hosted accounting software.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
