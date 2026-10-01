# Emmaforo Android

App Kotlin per la lampada Emmaforo. Quattro schermate: Semaforo, Colori, Configurazione, Info. Il Bluetooth è il collegamento normale. Il Wi‑Fi, sulla rete già salvata, entra solo quando la scheda è fuori portata.

Serve JDK 21. Dalla cartella del progetto:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./gradlew assembleDebug
```

L’APK di debug è `app/build/outputs/apk/debug/app-debug.apk`.

Copyright Michele Bigi 2026. Apache License 2.0.
