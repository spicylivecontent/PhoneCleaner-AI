# PhoneCleaner AI

Android-app som skannar telefonen den körs på, låter Claude (med webbsökning) bedöma vad som är säkert att ta bort, och rensar bara det du själv kryssar i och bekräftar. Allt går att ångra.

*Android app that scans the phone it runs on, asks Claude (with web search) what is safe to remove, and removes only what you tick and confirm. Everything can be undone. The app is in Swedish and English and follows the phone's language.*

## Så fungerar den

1. **Skanna** – läser paket, behörigheter, overlay, enhetsadmin, tillgänglighetstjänster, ikoner, batteri/data/lagring och inställningar. Ändrar ingenting.
2. **AI-research** – skickar olåsta paket till Claude API i omgångar om 15. Svaren cachas i en Room-databas så inget paket kostar två gånger.
3. **Granska listan** – grupperad lista med kryssrutor, sök och detaljer med källor. Kritiska paket är låsta.
4. **Rensa / Optimera** – backup-fil först, sedan bekräftelse med exakta kommandon, sedan körning. Logg över allt.
5. **Återställ** – ångra en ändring i taget eller allt.

Appen använder [Shizuku](https://shizuku.rikka.app/) (shell-rättigheter, ingen root). API-nyckeln skrivs in i appen, krypteras med Android Keystore och finns aldrig i repot.

## Bygge och nedladdning

Varje push till `main` bygger en signerad APK med GitHub Actions och publicerar den som en **GitHub Release**. Öppna repots sida i mobilens webbläsare → **Releases** → ladda ner `PhoneCleanerAI.apk`.

Bygget kräver fyra *repository secrets* (Settings → Secrets and variables → Actions → New repository secret):

| Namn | Värde |
| --- | --- |
| `KEYSTORE_BASE64` | signeringsnyckeln (PKCS12) som base64 |
| `KEYSTORE_PASSWORD` | lösenordet till nyckelfilen |
| `KEY_ALIAS` | `phonecleaner` |
| `KEY_PASSWORD` | samma som `KEYSTORE_PASSWORD` |

Samma nyckel måste användas varje gång, annars går appen inte att uppdatera utan att avinstalleras först. Nyckeln och lösenordet ska aldrig checkas in.

### Lägga in secrets från mobilen

1. Öppna `github.com/<ägare>/<repo>/settings/secrets/actions` i webbläsaren (välj "Datorversion"/"Desktop site" om sidan inte visas).
2. Tryck **New repository secret**, skriv namnet exakt som i tabellen, klistra in värdet, tryck **Add secret**.
3. Upprepa för alla fyra.

## Bygga lokalt

```
export KEYSTORE_FILE=/sökväg/release.p12 KEYSTORE_PASSWORD=... KEY_ALIAS=phonecleaner KEY_PASSWORD=...
./gradlew assembleRelease
```

Utan dessa variabler byggs en osignerad release-APK.
