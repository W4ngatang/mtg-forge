package forge.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Keeps the whole site behind one password ({@code FORGE_SITE_PASSWORD}). The page itself (HTML, script,
 * styles) is open so it can show the password screen; the lobby ({@code /api}), card images and games
 * need the access cookie. The cookie comes from entering the site password ({@code POST /api/login}) or
 * from opening a valid invite link, so invited friends get in without it. Without the variable set, the
 * site is open.
 */
final class SiteGate implements Filter {
    static final String PASSWORD_ENV = "FORGE_SITE_PASSWORD";
    private static final String COOKIE = "forge_site";
    private static final int COOKIE_DAYS = 30;

    private final String password;
    private final String token;

    SiteGate(String password) {
        this.password = password == null || password.isEmpty() ? null : password;
        this.token = this.password == null ? null : digest("forge-site|" + this.password);
    }

    boolean enabled() {
        return password != null;
    }

    boolean passwordMatches(String given) {
        return password == null || (given != null
                && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8)));
    }

    /** Whether this request carries the access cookie (always true when there is no site password). */
    boolean admitted(HttpServletRequest req) {
        if (token == null) {
            return true;
        }
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName()) && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        c.getValue().getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Gives the browser the access cookie. */
    void admit(HttpServletRequest req, HttpServletResponse resp) {
        if (token == null) {
            return;
        }
        boolean https = req.isSecure() || "https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"));
        resp.addHeader("Set-Cookie", COOKIE + "=" + token + "; Path=/; Max-Age=" + COOKIE_DAYS * 86400
                + "; HttpOnly; SameSite=Lax" + (https ? "; Secure" : ""));
    }

    /** Paths that work without the cookie: the page itself, signing in, the format list, and invite links (checked by the API). */
    private static boolean open(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path.startsWith("/api/")) {
            // formats: also Fly's health check (fly.toml)
            return path.equals("/api/session") || path.equals("/api/login") || path.equals("/api/formats")
                    || path.matches("/api/games/[^/]+(/join)?/?");
        }
        // a game socket needs a seat token, which only the gated lobby or an invite hands out
        return !path.equals("/img") && !path.startsWith("/img/");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        if (open(req) || admitted(req)) {
            chain.doFilter(request, response);
            return;
        }
        resp.setStatus(401);
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write("{\"error\":\"Enter the site password first.\",\"locked\":true}");
    }

    @Override
    public void init(FilterConfig config) {
    }

    @Override
    public void destroy() {
    }

    private static String digest(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
