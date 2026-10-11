#!/usr/bin/env node
/** Regenerate with existing sharp; no dependency installs.
 * node scripts/generate-brand-assets.mjs
 * Or SHARP_MODULE=/absolute/path/to/node_modules/sharp node scripts/generate-brand-assets.mjs
 */
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(import.meta.url)
const sharp = require(process.env.SHARP_MODULE || 'sharp')
const source = await fs.readFile(path.join(root, 'assets/brand/msaku-mark.svg'), 'utf8')
const d = source.match(/\bd="([^"]+)"/)[1]
const stroke = source.match(/stroke-width="([^"]+)"/)[1]
if (source.match(/viewBox="([^"]+)"/)[1] !== '0 0 28 28' || d !== 'M5 21V7l9 9 9-9v14' || stroke !== '3.2') throw new Error('Canonical Lapak M changed unexpectedly')
const blue = '#2962FF'
const mark = (color = '#FFFFFF') => `<path d="${d}" fill="none" stroke="${color}" stroke-width="${stroke}" stroke-linecap="round" stroke-linejoin="round"/>`
const svg = (w,h,body) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`
const placed = (w,h,side,color) => `<g transform="translate(${(w-side)/2} ${(h-side)/2}) scale(${side/28})">${mark(color)}</g>`
const tile = (w,h) => svg(w,h,`<rect width="${w}" height="${h}" rx="${Math.min(w,h)*.22}" fill="${blue}"/>${placed(w,h,Math.min(w,h)*.78)}`)
const outputs=[]
async function text(file,content) {const dest=path.join(root,file);await fs.mkdir(path.dirname(dest),{recursive:true});await fs.writeFile(dest,content);outputs.push(file)}
async function png(file,w,h,body) {const dest=path.join(root,file);await fs.mkdir(path.dirname(dest),{recursive:true});await sharp(Buffer.from(body)).png().toFile(dest);outputs.push(file)}
const res='android/app/src/main/res'
for (const [density,scale] of Object.entries({ldpi:.75,mdpi:1,hdpi:1.5,xhdpi:2,xxhdpi:3,xxxhdpi:4})) {
  const base=`${res}/mipmap-${density}`
  const legacy=48*scale, adaptive=108*scale
  await png(`${base}/ic_launcher.png`,legacy,legacy,tile(legacy,legacy))
  await png(`${base}/ic_launcher_round.png`,legacy,legacy,svg(legacy,legacy,`<circle cx="${legacy/2}" cy="${legacy/2}" r="${legacy/2}" fill="${blue}"/>${placed(legacy,legacy,legacy*.78)}`))
  // Canonical geometry uniformly scaled 1.85 on a 108dp canvas.
  // Raster stroke pixels are <25dp from center, within 33dp safe radius.
  await png(`${base}/ic_launcher_foreground.png`,adaptive,adaptive,svg(adaptive,adaptive,placed(adaptive,adaptive,28*1.85*scale)))
  await png(`${base}/ic_launcher_background.png`,legacy,legacy,svg(legacy,legacy,`<rect width="${legacy}" height="${legacy}" fill="${blue}"/>`))
}
for (const dir of await fs.readdir(path.join(root,res))) {
  if(!dir.startsWith('drawable'))continue
  const file=`${res}/${dir}/splash.png`
  try {await fs.access(path.join(root,file))}catch{continue}
  const {width:w,height:h}=await sharp(path.join(root,file)).metadata()
  await png(file,w,h,svg(w,h,`<rect width="${w}" height="${h}" fill="${dir.includes('night')?'#111111':'#FFFFFF'}"/>${placed(w,h,Math.min(w,h)*.34,blue)}`))
}
await text(`${res}/drawable-v24/ic_launcher_foreground.xml`,`<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from assets/brand/msaku-mark.svg, inside the 66dp safe circle. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="28.1" android:translateY="28.1" android:scaleX="1.85" android:scaleY="1.85">
        <path android:pathData="${d}" android:fillColor="@android:color/transparent" android:strokeColor="#FFFFFF" android:strokeWidth="${stroke}" android:strokeLineCap="round" android:strokeLineJoin="round" />
    </group>
</vector>
`)
await text(`${res}/drawable/ic_launcher_background.xml`,`<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="${blue}" android:pathData="M0,0h108v108h-108z" />
</vector>
`)
await text(`${res}/values/ic_launcher_background.xml`,`<?xml version="1.0" encoding="utf-8"?>
<resources><color name="ic_launcher_background">${blue}</color></resources>
`)
for(const size of [192,256,512])await png(`icon-download-apk/icon-download-apk-${size}.png`,size,size,tile(size,size))
const banner=svg(1024,360,`<rect width="1024" height="360" rx="32" fill="${blue}"/><g transform="translate(56 72) scale(${216/28})">${mark()}</g><text x="320" y="173" fill="white" font-family="DejaVu Sans, sans-serif" font-size="70" font-weight="bold">msaku.id</text><text x="324" y="232" fill="white" font-family="DejaVu Sans, sans-serif" font-size="28">Download APK Android</text>`)
await png('icon-download-apk/banner-download-apk-1024.png',1024,360,banner)
await text('assets/brand/banner-download-apk.svg',banner+'\n')
const favicon=tile(28,28)
const fallback=`<!doctype html>
<html lang="id">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="theme-color" content="${blue}" />
  <title>msaku.id</title>
  <link rel="icon" href="favicon.svg" type="image/svg+xml" />
  <style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#fff;color:#172033;font:16px system-ui,sans-serif}main{text-align:center;padding:32px;max-width:420px}.brand{width:96px;height:96px;margin:auto}h1{font-size:28px}p{line-height:1.6;color:#596579}a{display:inline-block;background:${blue};color:white;border-radius:12px;padding:12px 20px;text-decoration:none}@media(prefers-color-scheme:dark){body{background:#111;color:#fff}p{color:#b6becb}}</style>
</head>
<body><main><div class="brand" role="img" aria-label="Logo msaku.id">${tile(96,96)}</div><h1>msaku.id</h1><p>Aplikasi memuat konten dari msaku.id. Jika halaman belum terbuka, periksa koneksi internet Anda lalu coba lagi.</p><a href="https://www.msaku.id">Buka msaku.id</a></main></body>
</html>
`
for(const dir of ['public','www']) {
  await text(`${dir}/favicon.svg`,favicon+'\n')
  await text(`${dir}/index.html`,fallback)
  await text(`${dir}/offline.html`,fallback)
}
console.log(`Generated ${outputs.length} brand assets from exact canonical Lapak M`)
console.log(outputs.join('\n'))
