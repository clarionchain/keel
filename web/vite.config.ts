import { brotliCompressSync, constants as zlibConstants, gzipSync } from "node:zlib";
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, type Plugin } from "vite";
import { VitePWA } from "vite-plugin-pwa";

const webRoot = dirname(fileURLToPath(import.meta.url));

const COMPRESS_EXT = new Set([".wasm", ".js", ".css", ".html", ".svg", ".webmanifest"]);

function walkFiles(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walkFiles(p, out);
    else out.push(p);
  }
  return out;
}

/** Precompress text/wasm so Caddy can serve br/gzip without encoding on the fly. */
function compressStatic(): Plugin {
  return {
    name: "compress-static",
    apply: "build",
    closeBundle() {
      const root = join(webRoot, "dist");
      for (const file of walkFiles(root)) {
        if (file.endsWith(".gz") || file.endsWith(".br")) continue;
        if (![...COMPRESS_EXT].some((ext) => file.endsWith(ext))) continue;
        const buf = readFileSync(file);
        writeFileSync(file + ".gz", gzipSync(buf, { level: 9 }));
        writeFileSync(
          file + ".br",
          brotliCompressSync(buf, {
            params: { [zlibConstants.BROTLI_PARAM_QUALITY]: 6 },
          }),
        );
      }
    },
  };
}

/** Start the 7.7MB wasm fetch during HTML parse, before JS evaluates. */
function preloadWasm(): Plugin {
  return {
    name: "preload-wasm",
    apply: "build",
    transformIndexHtml: {
      order: "post",
      handler(html, ctx) {
        const wasm = Object.keys(ctx.bundle ?? {}).find((k) => k.endsWith(".wasm"));
        if (!wasm) return html;
        const href = `/keel/${wasm}`;
        const tag = `<link rel="preload" href="${href}" as="fetch" type="application/wasm" crossorigin>`;
        // Before the module script so the 7.7MB fetch starts during HTML parse.
        if (html.includes(tag)) return html;
        return html.replace(
          '<script type="module"',
          `${tag}\n  <script type="module"`,
        );
      },
    },
  };
}

export default defineConfig({
  base: "/keel/",
  build: {
    outDir: "dist",
    target: "es2022",
  },
  plugins: [
    preloadWasm(),
    VitePWA({
      registerType: "autoUpdate",
      manifest: {
        name: "Keel — Ark Wallet",
        short_name: "Keel",
        description: "Self-custodial Ark wallet. Test network only (Signet).",
        theme_color: "#000000",
        background_color: "#000000",
        display: "standalone",
        start_url: "/keel/",
        icons: [
          { src: "icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
          { src: "icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
          { src: "icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        // The wasm binary is several MB; precache everything so the wallet works offline.
        maximumFileSizeToCacheInBytes: 32 * 1024 * 1024,
        globPatterns: ["**/*.{js,css,html,wasm,png,svg}"],
        // Never serve the app shell for downloads — let them hit the network.
        navigateFallbackDenylist: [/\.apk$/, /\.sha256$/, /INSTALL\.md$/],
      },
    }),
    compressStatic(),
  ],
});
