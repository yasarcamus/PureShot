# PureShot - RAW Camera App

Xiaomi'nin agresif post-processing filtrelerini bypass eden minimalist kamera uygulaması.

## Özellikler

- **Zero Processing**: Noise reduction, edge enhancement, color correction KAPALI
- **Manuel Kontrol**: ISO, Shutter Speed, White Balance
- **Instant Shutter**: Gecikme yok, anında çekim
- **Minimalist UI**: Temiz, karanlık tema arayüz

## Build

```bash
# Debug APK
./gradlew assembleDebug

# APK lokasyonu
app/build/outputs/apk/debug/app-debug.apk
```

## Kurulum

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Teknik Detaylar

Camera2 API üzerinden devre dışı bırakılan filtreler:
- `NOISE_REDUCTION_MODE_OFF`
- `EDGE_MODE_OFF`
- `COLOR_CORRECTION_MODE_FAST`
- `HOT_PIXEL_MODE_OFF`
- `SHADING_MODE_OFF`
- `TONEMAP_MODE_FAST`
- `STATISTICS_FACE_DETECT_MODE_OFF`
- `COLOR_CORRECTION_ABERRATION_MODE_OFF`

## Lisans

Kişisel kullanım için, Play Store dağıtımı yok.
