# Jarvis (Vivo Y19s / Android 8+)

## A) GitHub orqali, kompyutersiz
1. github.com da bepul akkaunt oching va yangi bo'sh repo yarating (masalan: jarvis).
2. Telefonda Termux oching:
   pkg install git unzip
   termux-setup-storage
   unzip ~/storage/downloads/jarvis-android.zip -d ~
   cd ~/jarvis
   git init && git add -A && git commit -m "jarvis"
   git branch -M main
   git remote add origin https://github.com/SIZNING_NOM/jarvis.git
   git push -u origin main
   (parol o'rnida GitHub "Personal access token" kiriting: Settings > Developer settings > Tokens, "repo" ruxsati)
3. Repo > Actions bo'limida "Build APK" tugagach, "jarvis-apk" artefaktini yuklab oling, ichidagi app-debug.apk ni o'rnating.

## B) Android Studio bilan (boshqa kompyuterda)
Jarvis papkasini oching, telefonni USB bilan ulang, Run bosing. Yoki Build > Build APK.

## Kalitlar
- Anthropic API kalit: console.anthropic.com
- Picovoice AccessKey (bepul): console.picovoice.ai ("Jarvis" tayyor uyg'onish so'zi)

## Vivo sozlamalari
Sozlamalar > Ilovalar > Jarvis: Avto-ishga tushirish YOQ, Batareya: cheklovsiz, "Fonda oyna ochish" YOQ.
Android 13+ da: Ilova ma'lumoti > yuqoridagi uch nuqta > "Cheklangan sozlamalarga ruxsat", keyin Accessibility'ni yoqing.
