import { defineConfig } from 'vite';

export default defineConfig({
  test: {
    include: ["out/squint-js/**/**test.mjs"],
  }
});
