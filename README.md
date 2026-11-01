# DATENZENTRALE – Version 0.1

Android-Server für einen USB-Stick, Zugriff über Browser im selben WLAN/Hotspot.

## Wichtig
- Dies ist ein erster lauffähiger Prototyp, kein fertiges Produkt.
- Android-Gerät (Motorola) muss den USB-Stick über USB-OTG erkennen.
- Motorola und Browser-Geräte müssen im selben lokalen Netzwerk sein.
- iPhone/Windows öffnen die angezeigte lokale Adresse im Browser.
- Version 0.1 kann Ordner anzeigen, mehrere Dateien hochladen und Dateien herunterladen.
- Noch nicht enthalten: Fortsetzen abgebrochener Übertragungen, Löschen/Verschieben, automatische Wiederverbindung.
- Der Server ist nur im lokalen Netz gedacht. Nicht ins Internet freigeben.

## Build
Das Projekt ist ein Android/Kotlin-Projekt. GitHub Actions baut eine Debug-APK.
Workflow: `.github/workflows/android.yml`.

## Bedienung
1. APK auf dem Motorola installieren.
2. App öffnen und „USB-Ordner auswählen“ drücken.
3. Im Android-Dateiauswahldialog den USB-Stick-Ordner auswählen und Zugriff erlauben.
4. „Server starten“ drücken.
5. Die angezeigte Adresse z. B. `http://192.168.x.x:8765` am iPhone/HP im Browser öffnen.

Falls das Motorola den Hotspot nicht als WLAN-Netz mit lokaler Kommunikation behandelt, funktioniert der Zugriff darüber nicht; dann später anderes gemeinsames WLAN oder USB-/Alternativlösung nutzen.
