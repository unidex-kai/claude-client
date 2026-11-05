# Lyrics World (Fabric, Minecraft Java 26.2)

Shows your Spotify lyrics as floating words around you, with a now-playing bar at the top.
Press **K** in game to open the settings.

## Get the .jar (no installs needed): build it on GitHub

1. Make a free account at github.com and create a **new repository** (any name, Public or Private).
2. Click **uploading an existing file** and drag in *everything inside this folder*
   (including the hidden `.github` folder). Commit.
3. Open the **Actions** tab. The "Build mod" job starts by itself (about 3-5 minutes).
4. When it has a green tick, open it and download **lyricsworld-jar** at the bottom. Unzip it.
5. Put `lyricsworld-1.1.0.jar` in your `.minecraft/mods` folder, next to **Fabric API 0.161.0+26.2**.
6. If the job goes red, open it, copy the red error lines and send them to me. I'll fix the code.

## Or build it on your own PC
Install JDK 25, open a terminal in this folder and run `gradlew build` (Windows)
or `./gradlew build` (Mac/Linux). The jar appears in `build/libs/`.

## Using it
* **K** opens settings. "Demo song" (Music tab) plays a built-in fake song so you can test the look right away.
* **Tabs:** General (on/off, menu animations, fonts), Look (intensity, placement, size and timing),
  Colours (two colours, with a live preview) and Music (source, Spotify login, sync offset).
  Click a row to change it. On rows with `<` `>` arrows, click the left half to go back and the right half to go forward.
* **Fonts:** pick a menu font and a lyrics font (Minecraft, Poppins, Pixel, Orbitron). The fonts are in
  the `.ttf` files and set up by the `font-*.json` files. They are under the SIL Open Font License
  (see `FONT-LICENSES.txt`). To add your own: add a `.ttf`, copy a `font-*.json` and edit it, then add a
  name in `Config.java` and `Fonts.java`.
* **Menu animations** can be switched off in General if you prefer a plain menu.
* **Spotify login:** click the "Spotify" row in the Music tab and follow the on-screen steps
  (create a free app at developer.spotify.com/dashboard, add the Redirect URI shown,
  tick "Web API", paste the Client ID). Your browser opens to approve it. No password
  or secret is stored, only a login token in `config/lyricsworld.json`.
* Lyrics come from the free LRCLIB service (Spotify has no public lyrics API),
  so a few songs may have no synced lyrics. Use "Sync offset" if the words feel early or late.
