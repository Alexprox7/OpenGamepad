<p align="center">
  <img src="docs/images/icon.png" width="96" alt="Icona di OpenGamepad">
</p>

<h1 align="center">OpenGamepad</h1>

<p align="center">
  Trasforma il telefono Android in un controller per PC a bassa latenza.<br>
  Wi‑Fi · USB · Bluetooth · controller fisico · macro e ruote radiali · risparmio energetico OLED
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="#download">Download</a> ·
  <a href="docs/PROTOCOL.md">Protocollo</a> ·
  <a href="CONTRIBUTING.md">Contribuire</a>
</p>

<p align="center">
  <img src="docs/images/controller.png" width="720" alt="Schermata del controller">
</p>

## Funzioni

- **Tre modi di collegarsi**
  | Modalità | Cosa serve sul PC | Cosa vede il PC |
  |---|---|---|
  | Wi‑Fi (codice QR o `IP:porta`) | OpenGamepad Companion | controller Xbox 360 (XInput) |
  | Cavo USB | OpenGamepad Companion | controller Xbox 360 (XInput) |
  | Bluetooth | **niente** | gamepad HID generico + tastiera |
- **Controller fisico** – collega un controller al telefono (es. GameSir G8 Galileo): i suoi input
  vengono uniti ai comandi touch e inviati al PC.
- **Vibrazione e numero giocatore** inoltrati dal gioco al telefono.
- **Interfaccia nero assoluto** – i comandi sono contorni sottili su nero puro, così i pixel OLED restano spenti.
- **Risparmio schermo mentre giochi**
  - *Nero OLED*: livello nero + luminosità minima, i comandi touch restano attivi.
  - *Spegni pannello* (anche su LCD): spegne il pannello tramite [Shizuku](https://shizuku.rikka.app/)
    mentre il controller fisico continua a funzionare.
  - Sicurezza: Volume su, Back+Start tenuti sul controller o l'uscita dall'app riaccendono lo schermo;
    se l'app smette di rispondere lo fa da solo un watchdog entro 2,5 s.
- **Macro e ruote radiali** in stile reWASD, con editor visuale:
  - combinazioni registrate direttamente dal controller (es. **L3 + R3**);
  - sequenze di pulsanti del controller, **tasti PC** (Ctrl, Alt, Win, F1–F24…), movimenti
    degli stick e attese, con turbo opzionale;
  - ruote radiali con **icone e spicchi colorati**, scelta con uno stick;
  - una finestra di combinazione evita che il primo pulsante di una combinazione arrivi al gioco.

<p align="center">
  <img src="docs/images/wheel.png" width="420" alt="Ruota radiale">
  <img src="docs/images/wheel-editor.png" width="420" alt="Editor della ruota">
</p>

## Download

Le versioni pronte sono nella pagina [Releases](../../releases):

- `OpenGamepad-<versione>.apk` – l'app Android (Android 9 o successivo).
- `OpenGamepadCompanion-<versione>.exe` – il companion per Windows 10/11.

## Per iniziare

### PC (Wi‑Fi e USB)

1. Installa il driver [ViGEmBus](https://github.com/nefarius/ViGEmBus/releases) e il
   [.NET 8 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/8.0).
2. Avvia `OpenGamepadCompanion.exe` e consenti l'accesso alle reti private quando lo chiede il firewall.
3. **Wi‑Fi:** nell'app tocca *Scansiona QR* e inquadra il codice del companion (oppure scrivi `IP:47800`).
   **USB:** attiva il debug USB sul telefono, collegalo e tocca *Connetti via USB*.
   Il companion usa `adb` ([Android platform-tools](https://developer.android.com/tools/releases/platform-tools))
   dal `PATH` o da `C:\platform-tools`.

### Bluetooth (nessun programma sul PC)

1. Nell'app tocca *Attiva Bluetooth*, poi *Rendi visibile*.
2. Sul PC/TV aggiungi un dispositivo Bluetooth e scegli il telefono.
3. Le volte successive il telefono si ricollega da solo all'avvio dell'app.

Con la modalità Bluetooth attiva l'app mostra la notifica "Gamepad Bluetooth attivo": Android
annulla i gamepad Bluetooth delle app che vanno in secondo piano. Se il telefono era già abbinato
con un'altra app gamepad, rimuovilo dal PC e abbinalo di nuovo: il PC memorizza il descrittore HID
al momento dell'abbinamento.

### Spegnere il pannello sui telefoni LCD

Installa e avvia [Shizuku](https://shizuku.rikka.app/) (debug wireless o ADB), poi tocca *Concedi*
nella scheda *Risparmio schermo*.

### Emergenza: riaccendere lo schermo

Dal più comodo all'ultima risorsa:

1. **Premi Volume su.** A pannello spento il servizio privilegiato legge il tasto direttamente dal
   dispositivo di input del kernel: funziona anche se l'app è bloccata o Android non consegna il tasto.
2. **Tieni premuti Back + Start per 1,5 s** sul controller fisico.
3. **Esci dall'app** (gesto Home o app recenti), oppure premi **Power** due volte: standby e
   risveglio ripristinano lo schermo normalmente.
4. Se l'app va in crash o viene chiusa, il watchdog riaccende il pannello entro **2,5 s**.
5. Da un PC con ADB: `adb shell input keyevent KEYCODE_VOLUME_UP`, oppure
   `adb shell am force-stop org.opengamepad` (al resto pensa il watchdog).
6. Tenendo premuto Power per ~10 s il telefono si riavvia, e lo schermo torna sempre acceso.

## Compatibilità con Remote Gamepad

Via Wi‑Fi l'app funziona anche con l'app PC di *Remote Gamepad* di SmartFusionLabs: basta scansionarne
il codice QR. I tasti PC delle macro arrivano solo con OpenGamepad Companion o via Bluetooth.
OpenGamepad è un progetto indipendente, non affiliato a SmartFusionLabs; la compatibilità deriva da
un'analisi indipendente del protocollo di rete, documentato in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Compilare dai sorgenti

| Parte | Requisiti | Comando |
|---|---|---|
| App Android | JDK 17+, Android SDK (platform 36) | `cd android && ./gradlew assembleRelease` |
| Test | idem | `cd android && ./gradlew testDebugUnitTest` |
| Companion Windows | .NET 8 SDK | `cd pc-companion && dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o publish` |

L'APK di release viene firmato con la chiave indicata in `android/keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); senza, si usa la chiave di debug.
Dettagli in [CONTRIBUTING.md](CONTRIBUTING.md).

## Struttura

```
android/        App Android (Kotlin, Jetpack Compose)
pc-companion/   Companion per Windows (C#, .NET 8, WinForms)
docs/           Specifica del protocollo e immagini
tools/          Script di supporto
```

## Crediti

- [ViGEmBus / ViGEm.Client](https://github.com/nefarius/ViGEmBus) (MIT/BSD‑3) – controller Xbox virtuale.
- [Shizuku](https://github.com/RikkaApps/Shizuku-API) (Apache‑2.0) e
  [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache‑2.0) – spegnimento pannello.
- La tecnica di spegnimento del pannello segue [scrcpy](https://github.com/Genymobile/scrcpy) (Apache‑2.0).
- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache‑2.0) – lettore QR.
- [QRCoder](https://github.com/codebude/QRCoder) (MIT) – codice QR del companion.

## Licenza

[MIT](LICENSE)
