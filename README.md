# FOX Spur Light

Ein kleines Tool für das Light Phone III zum Aufzeichnen und Navigieren beim **Radfahren und Wandern** –
mit Zugriff auf meinen Komoot-Account (geplante Touren laden, Aufzeichnungen hochladen).

> FOX Spur Light ist ein privates Projekt und steht in keiner Verbindung zu Komoot.

Das Repo enthält das komplette [Light SDK](https://github.com/lightphone/light-sdk)
(siehe [README.light-sdk.md](README.light-sdk.md)); der eigene Code liegt nur in `tool/`
(Kotlin-Paket `de.komoonlight` – der Name aus der Anfangszeit).

> Nutzt dieselben **inoffiziellen** Komoot-Endpunkte wie [KomootGPX](https://pypi.org/project/komootgpx/).
> Nur für den privaten Gebrauch mit dem eigenen Account. Komoot kann die Endpunkte jederzeit ändern.

## Funktionen (v0.11)

- **Rad oder Wandern**: ohne geplante Tour sofort aufzeichnen – Kilometer, Zeit, Ø-Tempo
  (geht auch ohne Komoot-Anmeldung). Bei geplanten Touren gilt die in Komoot geplante Sportart.
  Bestimmt Name („Radtour …“ / „Wanderung …“) und Sportart beim Upload.
- **Auto-Pause**: 5 s Stillstand (< 10 m Rad, < 4 m zu Fuß) pausiert die Aufzeichnung, weiterfahren setzt sie fort;
  Fahrzeit und Ø-Tempo zählen nur die Bewegung
- Anmeldung mit Komoot-E-Mail + Passwort – gespeichert wird nur der Token
- Touren getrennt nach **Geplant** und **Gefahren**, mit Datum und Länge
  - geplante Tour: Vorschau der ganzen Route, Navigation erst mit **„Route starten“**,
    „Beenden“ speichert die Fahrt (→ Komoot)
  - gefahrene Tour: nur ansehen – ohne Aufnahme- und Ton-Knopf
- **Karte im Hintergrund** (OpenStreetMap in Graustufen, doppelt groß für das dichte Display),
  darüber Route, eigener Weg gepunktet und Pfeil in Fahrtrichtung; + / − rechts auf der Karte,
  Karte mit dem Finger verschiebbar (Zielkreuz holt sie zurück zur Position bzw. ganzen Route),
  „Karte“ schaltet sie ab. Kacheln entlang der Route werden vorab geladen (< 250, gemäß OSM-Regeln)
  und 60 Tage zwischengespeichert → auch ohne Netz. Lädt die Karte nicht, steht der Grund unten links.
- Nächste Abbiegung groß ab 300 m („→ 120 m rechts, Hauptstraße“), sonst Rest-km
- **Sprachansagen** auf Deutsch: „In zweihundert Metern rechts abbiegen“, „Jetzt rechts abbiegen“,
  „Du hast die Route verlassen“, „Du hast dein Ziel erreicht“ – oben rechts „Ton an / Stumm“
- Zusätzlich Vibration: einmal ~200 m vor der Abbiegung, zweimal „jetzt“, lang bei Abweichung > 50 m
- **Fahrten landen automatisch bei Komoot unter „Gefahren“**: Nach jeder Aufzeichnung wird sie
  hochgeladen („Radtour 21.10.2026, 17:45“, Sportart Fahrrad). Ohne Netz oder Anmeldung wartet sie
  und wird beim nächsten Öffnen des Startscreens nachgeholt – antippen versucht es sofort erneut.
- Zusätzlich als GPX im **LightOS Tool Manager** (Browser) unter „Aufzeichnungen“
- **GPS über „GPS Logger for Android“**, solange LightOS Spur den Standort verweigert (siehe unten)

## GPS über GPS Logger (Übergangslösung)

LightOS gibt den Standort bisher nur an von Light freigegebene Tools („NoPermission: untrusted caller“).
Bis das geht, kann Spur die Punkte von [GPS Logger for Android](https://gpslogger.app/) übernehmen –
die App misst selbst und schickt jeden Punkt an Spur, nur innerhalb des Phones (127.0.0.1).

Einmal in GPS Logger einrichten:

1. **Einstellungen → Logging-Details → Log to custom URL** einschalten, URL:
   `http://127.0.0.1:8378/fix?lat=%LAT&lon=%LON&acc=%ACC&alt=%ALT&ts=%TIMESTAMP`
   (Methode GET, kein Body).
2. **Leistung → Logging-Intervall** auf 1–2 s, Distanzfilter 0 (für Navigation; für Wanderungen reichen 5 s).
3. Andere Ziele (GPX, KML, …) nach Belieben – Spur zeichnet selbst auf und lädt zu Komoot hoch.

Unterwegs: erst in GPS Logger **Start**, dann Spur öffnen. Danach in GPS Logger wieder stoppen.
Kommen Punkte, verschwindet „Warte auf GPS…“. Liefert LightOS später selbst GPS, hat das Vorrang.

Grenze: GPS Logger hält Punkte ohne Datenverbindung (Funkloch) teils zurück und schickt sie später;
Spur verwirft Punkte, die älter als 30 s ankommen, damit die Navigation nicht springt.

## Code

```
tool/
├── lighttool.toml                  ID, Name, Berechtigungen (statt AndroidManifest)
└── src/main/kotlin/de/komoonlight/
    ├── HomeScreen.kt               Login + Tourenliste (Startscreen)
    ├── NavScreen.kt                Navigation / freie Fahrt, Aufnahme, Vibration
    ├── Format.kt                   Datum, Fahrzeit (getestet)
    ├── TextEditorScreen.kt         Light-Tastatur für Eingabefelder
    ├── ToolEntryPoint.kt           Tool-Manager-Ordner „Aufzeichnungen“
    ├── Store.kt                    Token-Speicher (DataStore)
    ├── komoot/KomootClient.kt      Login, Touren, Route + Abbiegehinweise
    ├── location/LightLocation.kt   GPS über LightOS
    ├── location/GpsLoggerBridge.kt GPS von „GPS Logger“ über 127.0.0.1 (getestet)
    ├── nav/Navigator.kt            Navigationslogik (ohne Android, getestet)
    ├── nav/Turn.kt                 Komoot-Abbiegetypen → Text + Pfeil
    ├── nav/AutoPause.kt            Stillstand erkennen (getestet)
    ├── nav/VoicePrompts.kt         Ereignis → Sprachclip
    ├── nav/VoiceAnnouncer.kt       Abspielen über LightAudio
    ├── geo/                        Geometrie, GPX, Kartenprojektion (MapView, getestet)
    ├── map/TileStore.kt            Kartenkacheln laden + zwischenspeichern
    └── ui/RouteView.kt             Karte + Route zeichnen
```

## Upload zu Komoot

`KomootClient.uploadRecorded` nutzt – wie der GPX-Import auf komoot.com – den inoffiziellen Endpunkt
`POST /v007/tours/?data_type=gpx` und setzt danach Name und Sportart (`PATCH /v007/tours/{id}`).
Welche Dateien schon oben sind, merkt sich das Tool (DataStore); die GPX-Dateien bleiben zusätzlich
lokal erhalten. Sportart ändern: Parameter `sport` (z. B. `racebike`, `mtb`, `e_touringbicycle`).

## Karte

Kartendaten und Kacheln © [OpenStreetMap-Mitwirkende](https://www.openstreetmap.org/copyright),
geladen von `tile.openstreetmap.org` gemäß [Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/)
(eigener User-Agent, Zwischenspeicher, kein Massen-Download). CARTO-Kacheln brauchen inzwischen einen API-Schlüssel.
Eine Kartenbibliothek wie osmdroid erlaubt das Light-SDK nicht – die Kacheln werden deshalb selbst
gezeichnet (`TileStore` + `RouteView`), mit höchstens zwei gleichzeitigen Downloads.

## Sprachansagen

Das SDK gibt Tools keinen Zugriff auf Androids Sprachausgabe. Deshalb liegen die Ansagen als
fertige Clips in `tool/src/main/assets/voice/` (53 WAV-Dateien, ~3,4 MB), erzeugt offline mit
[Piper](https://github.com/rhasspy/piper) und der Stimme „Thorsten“
([Thorsten-Voice](https://github.com/thorstenMueller/Thorsten-Voice), CC0).

Texte ändern oder neu erzeugen: siehe `tools/voice/generate.py`. Der Test `VoicePromptsTest`
prüft, dass zu jeder möglichen Ansage ein Clip existiert. Straßennamen werden nicht angesagt.

## Bauen

Jeder Push auf `main` baut das Tool per GitHub Actions („Tool bauen“). Die fertige APK liegt
im jeweiligen Lauf unter **Artifacts → fox-spur-light-debug-apk**.

Lokal (Android Studio oder Kommandozeile, JDK 17 + Android SDK):

```bash
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest   # Tests
./gradlew -DlightSdk.toolOnly=true :tool:assembleDebug        # APK
```

## Aufs Phone

Am bequemsten per WLAN über den Tool Manager im Entwicklermodus
(siehe [docs/sideloading](docs/sideloading/README.md)):

```bash
./gradlew :tool:uploadTool -Pdevice.ip=<IP des Phones> -Pdevice.token=<Schlüssel aus der Tool-Manager-URL>
```

## Geplant

- [ ] **Navigation bei ausgeschaltetem Display** – LightOS erlaubt Tools keinen eigenen
      Hintergrunddienst. Ansatz: die erlaubte Fähigkeit `detached-audio` (läuft als
      Vordergrund-Dienst im Tool-Prozess) hält die Navigation am Leben.
- [x] Sprachansagen (v0.2)
- [ ] **Display 50 m vor Abzweigung einschalten** (`NavEvent.Approaching`) – braucht einen
      Weg, das Display aus dem Tool zu wecken; im SDK noch nicht vorgesehen.
- [ ] GPX-Routen über den Tool Manager hochladen (Fallback, falls Komoot hakt)

## Release / BrightMarket

Neue Version veröffentlichen: `versionCode` und `versionName` in `tool/lighttool.toml` erhöhen
und auf `main` pushen. Der Workflow „Release“ baut die APK und legt einen GitHub-Release mit genau einer `.apk` an –
so findet [BrightMarket](https://github.com/gi-os/BrightMarket) die App (per QR-Code das Repo
hinzufügen). Der `versionCode` muss bei jedem Release steigen, sonst gibt es kein Update.
