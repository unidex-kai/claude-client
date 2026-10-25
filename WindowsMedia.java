package com.lyricsworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads "what is playing" from Windows' own media controls (the popup you see when you press a
 * volume key). Works with Spotify, YouTube Music, browsers and most players. No login needed.
 * A small PowerShell script runs in the background and prints one line of JSON about every 0.7s.
 */
public final class WindowsMedia {
	private static final Logger LOG = LoggerFactory.getLogger("lyricsworld");

	/** Latest track from Windows, or null when nothing is playing. */
	public static volatile Track track = null;
	public static volatile String status = "";

	private static Process proc;

	private static final String SCRIPT = """
			$ErrorActionPreference = 'Stop'
			[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
			Add-Type -AssemblyName System.Runtime.WindowsRuntime
			$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
			function Await($op, $type) {
			  $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
			  $t.Wait(-1) | Out-Null
			  $t.Result
			}
			$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
			$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
			$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
			while ($true) {
			  try {
			    $s = $mgr.GetCurrentSession()
			    if ($null -eq $s) {
			      Write-Output '{"none":true}'
			    } else {
			      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			      $tl = $s.GetTimelineProperties()
			      $pb = $s.GetPlaybackInfo()
			      $o = [ordered]@{
			        title = [string]$p.Title
			        artist = [string]$p.Artist
			        album = [string]$p.AlbumTitle
			        pos = [long]$tl.Position.TotalMilliseconds
			        dur = [long]($tl.EndTime - $tl.StartTime).TotalMilliseconds
			        upd = [long]$tl.LastUpdatedTime.ToUnixTimeMilliseconds()
			        playing = ([string]$pb.PlaybackStatus -eq 'Playing')
			      }
			      Write-Output ($o | ConvertTo-Json -Compress)
			    }
			  } catch {
			    Write-Output ('{"error":"' + ($_.Exception.Message -replace '[^a-zA-Z0-9 ]', '?') + '"}')
			  }
			  [Console]::Out.Flush()
			  Start-Sleep -Milliseconds 700
			}
			""";

	private WindowsMedia() {
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static boolean scheduled = false;

	/**
	 * Call once at start-up, and again whenever the settings change. Starts the background
	 * reader when it is wanted and stops it when it is not.
	 */
	public static synchronized void apply() {
		if (!scheduled) {
			scheduled = true;
			Runtime.getRuntime().addShutdownHook(new Thread(WindowsMedia::stop));
			Spotify.POOL.scheduleWithFixedDelay(WindowsMedia::supervise, 1, 3, TimeUnit.SECONDS);
		}
		Spotify.POOL.execute(WindowsMedia::supervise);
	}

	/** Starts or stops the background script depending on the settings. */
	private static synchronized void supervise() {
		try {
			Config c = Config.get();
			boolean want = c.enabled && c.source == 0 && !LyricsWorldClient.demo;
			boolean alive = proc != null && proc.isAlive();
			if (want && !alive) {
				if (!isWindows()) {
					status = "Windows media only works on Windows. Switch Source to Spotify.";
					return;
				}
				launch();
			} else if (!want && alive) {
				stop();
				track = null;
			}
		} catch (Exception e) {
			status = "Windows media: " + e.getMessage();
			LOG.warn("could not start the Windows media reader", e);
		}
	}

	private static void launch() throws Exception {
		Path script = Files.createTempFile("lyricsworld-media", ".ps1");
		Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
		script.toFile().deleteOnExit();
		ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
				"-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString());
		pb.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process p = pb.start();
		proc = p;
		status = "Starting Windows media reader...";
		Thread t = new Thread(() -> readLoop(p), "LyricsWorld-winmedia");
		t.setDaemon(true);
		t.start();
	}

	public static synchronized void stop() {
		if (proc != null) {
			proc.destroyForcibly();
			proc = null;
		}
	}

	private static void readLoop(Process p) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				handle(line.trim());
			}
		} catch (Exception e) {
			LOG.debug("media reader ended", e);
		}
	}

	private static void handle(String line) {
		if (!line.startsWith("{")) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(line).getAsJsonObject();
			if (o.has("none")) {
				track = null;
				status = "Nothing is playing in Windows media";
				return;
			}
			if (o.has("error")) {
				status = "Windows media: " + o.get("error").getAsString();
				return;
			}
			String title = o.get("title").getAsString().trim();
			if (title.isEmpty()) {
				track = null;
				status = "Nothing is playing in Windows media";
				return;
			}
			String artist = o.get("artist").getAsString().trim();
			String album = o.get("album").getAsString().trim();
			long pos = o.get("pos").getAsLong();
			long dur = o.get("dur").getAsLong();
			long upd = o.get("upd").getAsLong();
			boolean playing = o.get("playing").getAsBoolean();
			long now = System.currentTimeMillis();
			if (upd < 1_000_000_000_000L || upd > now + 5000) {
				upd = now; // Windows did not give a usable timestamp
			}
			if (dur < 0) {
				dur = 0;
			}
			Track t = new Track(title + "|" + artist, title, artist, firstArtist(artist), album, dur, pos, playing, upd);
			track = t;
			status = "Reading from Windows media";
			Lyrics.request(t);
		} catch (Exception e) {
			LOG.debug("bad line from media reader: {}", line);
		}
	}

	static String firstArtist(String artist) {
		String[] parts = artist.split("\\s*[,;]\\s*");
		return parts.length == 0 ? artist : parts[0];
	}
}
