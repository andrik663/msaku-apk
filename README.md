# msaku-apk

Repo khusus pembangunan APK aplikasi **msaku** (Capacitor WebView shell menuju `https://www.msaku.id`).

Setiap kali kode di-push ke branch `main`, GitHub Actions otomatis membangun APK di cloud dan mengunggahnya sebagai **Artifact** — bisa diunduh langsung dari halaman run tanpa perlu build lokal.

## Cara unduh APK

### 1. Lewat Artifact (setiap push)
1. Buka tab **Actions** di repo ini → pilih run "Build APK" terbaru (harus hijau).
2. Di bagian bawah halaman run, unduh artifact **msaku-apk-debug**.
3. Ekstrak zip → dapatkan `msaku-v<versi>-debug.apk`.

### 2. Lewat Release (stabil, permanen)
Setiap kali maintainer membuat **Release**, APK otomatis ditempel ke release dan tersedia di URL tetap publik:

```
https://github.com/andrik663/msaku-apk/releases/latest/download/msaku-debug.apk
```

URL inilah yang dipakai tombol "Download APK" di beranda dashboard web msaku (halaman `/download` sudah dihapus).

## Instal di Android
1. Unduh APK di atas.
2. Buka file → izinkan "install dari sumber tidak dikenal".
3. Install. App memuat konten live dari msaku.id.

## Struktur
```
android/                  # project Capacitor Android (applicationId com.sholatbre.app)
.github/workflows/        # workflow build otomatis
icon-download-apk/        # ikon "Download APK" (dipakai di web msaku)
```

## Build versi baru
1. Naikkan `versionCode` + `versionName` di `android/app/build.gradle`.
2. Push → Actions membangun artifact baru.
3. Buat Release (tag `v1.x`) → APK menempel di release.