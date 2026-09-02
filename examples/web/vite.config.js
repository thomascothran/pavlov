import { defineConfig } from "vite";
import squint from "squint-cljs/vite";

export default defineConfig({
  base: "/js-squint/",
  plugins: [squint()],
  build: {
    outDir: "resources/public/js-squint",
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: "main.js",
        chunkFileNames: "chunks/[name]-[hash].js",
        assetFileNames: "assets/[name]-[hash][extname]",
      },
    },
  },
});
