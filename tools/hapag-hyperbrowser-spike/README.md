# Hapag-Lloyd / Hyperbrowser manual spike

This isolated diagnostic checks whether Playwright/CDP can observe the same page state shown in Hyperbrowser Live View after a human operator completes any portal verification. It does **not** extract historical events, persist page data, or integrate with ShipArrival.

## Safety

- Never enable CAPTCHA solving, stealth, proxy/evasion, or automated challenge interaction.
- The operator alone handles any verification in Live View.
- Treat `liveUrl` and `wsEndpoint` as bearer secrets. This runner intentionally never prints either. Do not screenshot/share them.
- Run against a B/L you are authorized to inspect. Hyperbrowser may bill for session time.
- Session timeout defaults to 10 minutes and cleanup is attempted even on error. Check the provider dashboard if cleanup cannot be confirmed.

## Setup (PowerShell)

From this directory:

```powershell
npm install
$env:HYPERBROWSER_API_KEY = Read-Host 'Set the API key privately' -AsSecureString | ConvertFrom-SecureString
```

Use your local secrets manager or set the variable directly in a private terminal session without saving it to shell history. Do not paste the key into chat or commit it.

Then set the B/L and run:

```powershell
$env:HAPAG_BL = 'YOUR_AUTHORIZED_BL'
npm test
npm run spike
```

The runner creates a short session, prints the `liveUrl` to this local interactive terminal for you to open, attaches CDP, and navigates to the tracking page. The Live View URL is a bearer secret: do not share it, record it, or paste it into chat. Complete any verification manually in Live View, then press Enter in the runner to record page title, path, visible-text length, and table count. Compare that diagnostic with what you see in Live View. No page text or events are saved.

If CDP cannot attach to or observe the operator's live page, stop: the integration plan's gate has failed. If it can, capture and anonymize a separate fixture only after agreeing on an approved collection method; this runner deliberately does not dump DOM HTML.
