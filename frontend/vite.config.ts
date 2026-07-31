import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 로컬 개발 시 백엔드(3000)로 /api, /ws 프록시. 운영은 nginx가 담당한다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:3000",
      "/ws": { target: "ws://localhost:3000", ws: true },
      // MCP (SSE) — 개발 중에도 화면 주소로 MCP 등록이 가능하도록 백엔드로 넘긴다.
      "/sse": "http://localhost:3000",
      "/message": "http://localhost:3000",
    },
  },
});
