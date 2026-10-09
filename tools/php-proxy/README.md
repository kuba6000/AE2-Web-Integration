# PHP reverse proxy

Serve the mod's website and API through a PHP hosting account. The mod supplies all
HTML, scripts, styles and authentication; this directory contains no separate UI.

1. Use PHP 7.4+ with cURL and Apache with `mod_rewrite` and `.htaccess` enabled.
2. Upload `index.php` and `.htaccess` to the website root or a directory such as `/ae2/`.
3. Set `$AE2_SERVER_HOST` in `index.php` to the mod's HTTP root URL, with a trailing slash.
4. Open that directory's URL, for example `https://example.com/ae2/`.

The upstream address must be reachable from the PHP host. `localhost` refers to the
PHP host, so use the Minecraft server's address when they run on different machines.
On the mod, add the PHP host's address to `general.trusted_proxies` if it is remote;
a proxy on the same machine is already trusted. This is required to preserve the
client address used for login, localhost access and rate limiting.

If another reverse proxy sits in front of PHP, put its exact IP addresses in
`$AE2_TRUSTED_PROXIES`. It must preserve the public `Host`, overwrite
`X-Forwarded-Proto` with `http` or `https`, and supply the real client address through
`X-Forwarded-For` or `X-Real-IP`. Headers from other visitors are ignored and replaced.
Leave the list empty when visitors connect directly to PHP. If Apache already
resolves `REMOTE_ADDR` through `mod_remoteip`, do not also trust client-supplied
forwarding headers in PHP.

The proxy preserves methods, request bodies, query strings, cookies and API status
codes. It maps upstream root redirects and explicit cookie paths to the public
mount directory. Relative URLs and cookies without a Path stay unchanged.
Requests use a fixed upstream, a 5-second connection timeout and a 30-second total
timeout. Responses are buffered; WebSockets and event streaming are not supported.
