import { resolve } from 'node:path';
import fs from 'node:fs';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import vueJsx from '@vitejs/plugin-vue-jsx';
import tailwindcss from '@tailwindcss/vite';

const version = fs.readFileSync(new URL('../../Version', import.meta.url), 'utf-8').trim();

export default defineConfig({
    root: resolve(import.meta.dirname, '../..'),
    publicDir: resolve(import.meta.dirname, '../public'),
    base: './',
    plugins: [
        vue(),
        vueJsx({
            tsTransform: 'built-in'
        }),
        tailwindcss()
    ],
    resolve: { alias: { '@': resolve(import.meta.dirname, '..') } },
    define: {
        VERSION: JSON.stringify(version),
        NIGHTLY: 'true',
        WINDOWS: 'false',
        LINUX: 'true',
        ANDROID: 'true'
    },
    build: {
        target: 'chrome110',
        outDir: 'build/android',
        emptyOutDir: true,
        copyPublicDir: true,
        reportCompressedSize: false,
        chunkSizeWarningLimit: 5000,
        rollupOptions: { input: resolve(import.meta.dirname, 'index.html') }
    }
});
