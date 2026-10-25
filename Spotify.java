package com.lyricsworld;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Spotify login (PKCE, so no secret is needed) and "what is playing now" polling.
 * Everything here runs on a background thread, never on the game thread.
 */
public final class Spotify {
	private static final Logger LOG = LoggerFactory.getLogger("lyricsworld");
	public static final String REDIRECT = "http://127.0.0.1:19542/callback";
	private static final String SCOPES = "user-read-currently-playing user-read-playback-state";

	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(2, r -> {
		Thread t = new Thread(r, "LyricsWorld-net");
		t.setDaemon(true);
		return t;
	});

	/** Short message for the settings screen. */
	public static volatile String status = "";
	/** Latest track from Spotify, or null when nothing is playing. */
	public static volatile Track track = null;
	/** Login link, shown on screen in case the browser did not open by itself. */
	public static volatile String loginUrl = "";

	private static volatile long backoffUntil = 0;
	private static volatile boolean loginRunning = false;

	private Spotify() {
	}

	public static void start() {
		POOL.scheduleWithFixedDelay(Spotify::poll, 2000, 1500, TimeUnit.MILLISECONDS);
	}

	public static boolean connected() {
		return !Config.get().refreshToken.isEmpty();
	}

	public static void logout() {
		Config c = Config.get();
		c.accessToken = "";
		c.refreshToken = "";
		c.expiresAtMs = 0;
		Config.save();
		track = null;
		status = "Logged out";
	}

	// ------------------------------------------------------------ polling

	private static void poll() {
		try {
			Config c = Config.get();
			if (!c.enabled || LyricsWorldClient.demo || c.refreshToken.isEmpty()) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now < backoffUntil) {
				return;
			}
			if (c.accessToken.isEmpty() || now > c.expiresAtMs - 30_000) {
				if (!refresh()) {
					return;
				}
			}
			HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
					.timeout(Duration.ofSeconds(8))
					.header("Authorization", "Bearer " + c.accessToken)
					.GET().build();
			HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			long fetched = System.currentTimeMillis();
			int code = res.statusCode();
			if (code == 204) {
				track = null;
				status = "Nothing is playing on Spotify";
				return;
			}
			if (code == 401) {
				c.expiresAtMs = 0;
				return;
			}
			if (code == 429) {
				long wait = res.headers().firstValueAsLong("Retry-After").orElse(5);
				backoffUntil = System.currentTimeMillis() + wait * 1000;
				status = "Spotify asked us to slow down";
				return;
			}
			if (code != 200) {
				status = "Spotify error " + code;
				return;
			}
			JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
			JsonElement itemEl = o.get("item");
			if (itemEl == null || itemEl.isJsonNull()) {
				track = null;
				status = "Nothing is playing on Spotify";
				return;
			}
			JsonObject item = itemEl.getAsJsonObject();
			String id = str(item, "id");
			String title = str(item, "name");
			String artists = "";
			String first = "";
			JsonElement arr = item.get("artists");
			if (arr != null && arr.isJsonArray()) {
				JsonArray a = arr.getAsJsonArray();
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < a.size(); i++) {
					String n = str(a.get(i).getAsJsonObject(), "name");
					if (i == 0) {
						first = n;
					}
					if (i > 0) {
						sb.append(", ");
					}
					sb.append(n);
				}
				artists = sb.toString();
			}
			String album = "";
			JsonElement alb = item.get("album");
			if (alb != null && alb.isJsonObject()) {
				album = str(alb.getAsJsonObject(), "name");
			}
			long duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
			long progress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
			boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
			Track t = new Track(id, title, artists, first, album, duration, progress, playing, fetched);
			track = t;
			status = "Connected to Spotify";
			Lyrics.request(t);
		} catch (Exception e) {
			status = "Spotify: " + e.getMessage();
			LOG.debug("poll failed", e);
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	// ------------------------------------------------------------ login

	/** Opens the browser for Spotify login and waits for the redirect on port 19542. */
	public static synchronized void login(String clientId) {
		if (loginRunning || clientId.isEmpty()) {
			return;
		}
		loginRunning = true;
		status = "Waiting for you to approve the login in your browser...";
		POOL.execute(() -> {
			try {
				doLogin(clientId);
			} catch (Exception e) {
				status = "Login failed: " + e.getMessage();
				LOG.warn("login failed", e);
			} finally {
				loginRunning = false;
			}
		});
	}

	private static void doLogin(String clientId) throws Exception {
		SecureRandom rnd = new SecureRandom();
		String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
		StringBuilder v = new StringBuilder();
		for (int i = 0; i < 64; i++) {
			v.append(chars.charAt(rnd.nextInt(chars.length())));
		}
		String verifier = v.toString();
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		byte[] sb = new byte[12];
		rnd.nextBytes(sb);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(sb);

		Map<String, String> q = new LinkedHashMap<>();
		q.put("client_id", clientId);
		q.put("response_type", "code");
		q.put("redirect_uri", REDIRECT);
		q.put("code_challenge_method", "S256");
		q.put("code_challenge", challenge);
		q.put("scope", SCOPES);
		q.put("state", state);
		String url = "https://accounts.spotify.com/authorize?" + form(q);
		loginUrl = url;

		String code;
		try (ServerSocket ss = new ServerSocket()) {
			ss.setReuseAddress(true);
			ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 19542));
			ss.setSoTimeout(180_000);
			openBrowser(url);
			try (Socket s = ss.accept()) {
				s.setSoTimeout(10_000);
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
				String first = in.readLine();
				String line;
				while ((line = in.readLine()) != null && !line.isEmpty()) {
					// skip headers
				}
				String body = "<html><body style='font-family:sans-serif;background:#111;color:#eee;text-align:center;padding-top:15vh'>"
						+ "<h2>Lyrics World is connected.</h2><p>You can close this tab and go back to Minecraft.</p></body></html>";
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
				s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
				s.getOutputStream().write(bytes);
				s.getOutputStream().flush();
				code = parseCallback(first, state);
			}
		}

		Map<String, String> f = new LinkedHashMap<>();
		f.put("grant_type", "authorization_code");
		f.put("code", code);
		f.put("redirect_uri", REDIRECT);
		f.put("client_id", clientId);
		f.put("code_verifier", verifier);
		JsonObject tok = tokenRequest(f);
		applyTokens(tok);
		status = "Connected to Spotify";
		loginUrl = "";
	}

	private static String parseCallback(String requestLine, String expectedState) throws Exception {
		if (requestLine == null || !requestLine.startsWith("GET ")) {
			throw new Exception("unexpected reply from browser");
		}
		String path = requestLine.split(" ")[1];
		int qi = path.indexOf('?');
		if (qi < 0) {
			throw new Exception("no data in the redirect");
		}
		String code = null;
		String state = null;
		String error = null;
		for (String part : path.substring(qi + 1).split("&")) {
			int eq = part.indexOf('=');
			if (eq < 0) {
				continue;
			}
			String k = part.substring(0, eq);
			String val = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
			switch (k) {
				case "code" -> code = val;
				case "state" -> state = val;
				case "error" -> error = val;
				default -> {
				}
			}
		}
		if (error != null) {
			throw new Exception("Spotify said: " + error);
		}
		if (code == null || !expectedState.equals(state)) {
			throw new Exception("login reply did not match");
		}
		return code;
	}

	private static boolean refresh() {
		try {
			Config c = Config.get();
			Map<String, String> f = new LinkedHashMap<>();
			f.put("grant_type", "refresh_token");
			f.put("refresh_token", c.refreshToken);
			f.put("client_id", c.clientId);
			JsonObject tok = tokenRequest(f);
			applyTokens(tok);
			return true;
		} catch (Exception e) {
			status = "Could not refresh Spotify login: " + e.getMessage();
			backoffUntil = System.currentTimeMillis() + 15_000;
			return false;
		}
	}

	private static void applyTokens(JsonObject tok) {
		Config c = Config.get();
		c.accessToken = str(tok, "access_token");
		String rt = str(tok, "refresh_token");
		if (!rt.isEmpty()) {
			c.refreshToken = rt;
		}
		long secs = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600;
		c.expiresAtMs = System.currentTimeMillis() + secs * 1000;
		Config.save();
	}

	private static JsonObject tokenRequest(Map<String, String> f) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form(f))).build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			String msg = "HTTP " + res.statusCode();
			try {
				JsonObject e = JsonParser.parseString(res.body()).getAsJsonObject();
				msg += " " + str(e, "error") + " " + str(e, "error_description");
			} catch (Exception ignored) {
			}
			throw new Exception(msg.trim());
		}
		return JsonParser.parseString(res.body()).getAsJsonObject();
	}

	private static String form(Map<String, String> m) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : m.entrySet()) {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
					.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	private static void openBrowser(String url) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			ProcessBuilder pb;
			if (os.contains("win")) {
				pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
			} else if (os.contains("mac")) {
				pb = new ProcessBuilder("open", url);
			} else {
				pb = new ProcessBuilder("xdg-open", url);
			}
			pb.start();
		} catch (Exception e) {
			LOG.warn("Could not open the browser automatically", e);
		}
	}
}
