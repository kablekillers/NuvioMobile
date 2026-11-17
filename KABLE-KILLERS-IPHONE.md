# Installing Kable Killers Mobile on iPhone or iPad

Kable Killers Mobile isn't on the App Store. You install it with **SideStore** (or **AltStore**),
free apps that install other apps using your own Apple ID. You don't need a paid Apple
Developer account.

**Source URL** (copy this, you'll need it in step 3):

```
https://raw.githubusercontent.com/kablekillers/NuvioMobile/altstore-source/source.json
```

## 1. Install SideStore (one time, needs a computer)

Follow the official guide at **https://sidestore.io**. In short:

1. On a Mac or Windows/Linux PC, install **iloader** and plug in your iPhone.
2. Sign in with your Apple ID in iloader and install SideStore.
3. On the iPhone: **Settings → Privacy & Security → Developer Mode → On**, and restart.
4. **Settings → General → VPN & Device Management**, then trust your Apple ID.
5. Open SideStore, sign in with the same Apple ID, and refresh SideStore once.

After this you don't need the computer again.

## 2. Turn on SideStore's refresh helper

SideStore uses a small on-device VPN app (shown in its setup steps) to sign apps. Install it
and switch it on when SideStore asks. This is what lets apps refresh without a computer.

## 3. Add the Kable Killers source

1. In SideStore, open **Sources → +**.
2. Paste the source URL above and tap **Add**.
3. Open **Kable Killers**, then tap **Free / Install** next to *Kable Killers Mobile*.

When a new version comes out, SideStore shows an update in **My Apps**.

## Things to know

- **The app expires after 7 days unless it's refreshed.** That's Apple's rule for free Apple
  IDs. SideStore refreshes it in the background while its VPN helper is on. Open SideStore
  now and then, or tap the **7 DAYS** counter in *My Apps* to refresh by hand.
- **Free Apple IDs can have 3 sideloaded apps at a time**, and SideStore counts as one.
- **Your Apple ID is only used to sign apps on your own device.** Some people make a
  separate free Apple ID just for sideloading.
- **AltStore works too**: add the same source URL in AltStore → Sources.

## Installing the IPA directly

Every release on https://github.com/kablekillers/NuvioMobile/releases has a
`Kable-Killers-Mobile-vX.Y.Z.ipa` file. You can open it with SideStore, AltStore or
Sideloadly instead of using the source.
