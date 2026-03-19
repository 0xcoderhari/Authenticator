import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8081",
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes, req, res) => {
            delete proxyRes.headers["www-authenticate"];
            delete proxyRes.headers["WWW-Authenticate"];
            res.removeHeader("www-authenticate");
            res.removeHeader("WWW-Authenticate");

            if (req.url?.includes("/api/auth/login") && proxyRes.statusCode === 401) {
              proxyRes.statusCode = 400;
              res.statusCode = 400;
            }
          });
        }
      }
    }
  }
});
