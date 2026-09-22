import { resolve } from 'node:path';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
    root: resolve(import.meta.dirname, '../..'),
    base: './',
    plugins: [vue(), tailwindcss()],
    resolve: { alias: { '@': resolve(import.meta.dirname, '..') } },
    build: {
        target: 'chrome110',
        outDir: 'build/android',
        emptyOutDir: true,
        rollupOptions: { input: resolve(import.meta.dirname, 'index.html') }
    }
});
