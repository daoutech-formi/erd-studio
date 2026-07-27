import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 로컬 개발 시 백엔드(3000)로 /api, /ws 프록시. 운영은 nginx가 담당한다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:3000",
      "/ws": { target: "ws://localhost:3000", ws: true },
    },
  },
});
