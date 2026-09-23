<?php
// URL of the mod's HTTP server. Keep the trailing slash.
$AE2_SERVER_HOST = "http://localhost:2324/";
// Immediate proxies in front of PHP that may supply forwarded client/protocol headers.
$AE2_TRUSTED_PROXIES = [];

function proxyError($code, $status) {
    http_response_code($code);
    header('Content-Type: application/json; charset=UTF-8');
    header('Cache-Control: no-store');
    if ($_SERVER['REQUEST_METHOD'] !== 'HEAD') echo json_encode(['status' => $status, 'data' => null]);
    exit;
}

function hopHeaders($connection) {
    return array_merge(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
        'proxy-connection', 'te', 'trailer', 'transfer-encoding', 'upgrade'],
        array_map('trim', explode(',', strtolower($connection))));
}

$mount = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'])), '/');
$uri = $_SERVER['REQUEST_URI'];
if (substr($uri, 0, strlen($mount) + 1) !== $mount . '/') proxyError(400, 'INVALID_REQUEST');
$resource = substr($uri, strlen($mount) + 1);
// index.php is an alternate entry address, not an upstream resource.
$resource = preg_replace('~^index\.php(?=\?|$)~', '', $resource);
$client = $_SERVER['REMOTE_ADDR'];
$scheme = !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off' ? 'https' : 'http';
if (in_array($client, $AE2_TRUSTED_PROXIES, true)) {
    if (isset($_SERVER['HTTP_X_FORWARDED_FOR'])) {
        $hops = array_map('trim', explode(',', $_SERVER['HTTP_X_FORWARDED_FOR']));
        foreach ($hops as $hop) {
            if (!filter_var($hop, FILTER_VALIDATE_IP)) proxyError(400, 'INVALID_REQUEST');
        }
        foreach (array_reverse($hops) as $hop) {
            $client = $hop;
            if (!in_array($hop, $AE2_TRUSTED_PROXIES, true)) break;
        }
    } elseif (isset($_SERVER['HTTP_X_REAL_IP'])) {
        $client = trim($_SERVER['HTTP_X_REAL_IP']);
        if (!filter_var($client, FILTER_VALIDATE_IP)) proxyError(400, 'INVALID_REQUEST');
    }
    if (isset($_SERVER['HTTP_X_FORWARDED_PROTO'])) {
        $scheme = strtolower(trim($_SERVER['HTTP_X_FORWARDED_PROTO']));
        if (!in_array($scheme, ['http', 'https'], true)) proxyError(400, 'INVALID_REQUEST');
    }
}
$headers = [];
$excluded = array_merge(hopHeaders($_SERVER['HTTP_CONNECTION'] ?? ''), ['content-length',
    'forwarded', 'x-forwarded-for', 'x-real-ip', 'x-forwarded-proto', 'x-forwarded-host']);
foreach (getallheaders() as $name => $value) {
    if (!in_array(strtolower($name), $excluded, true)) {
        $headers[] = $name . ': ' . $value;
    }
}
$headers[] = 'X-Forwarded-For: ' . $client;
$headers[] = 'X-Real-IP: ' . $client;
$headers[] = 'X-Forwarded-Proto: ' . $scheme;
$ch = curl_init(rtrim($AE2_SERVER_HOST, '/') . '/' . $resource);
curl_setopt_array($ch, [
    CURLOPT_CUSTOMREQUEST => $_SERVER['REQUEST_METHOD'],
    CURLOPT_HTTPHEADER => $headers,
    CURLOPT_POSTFIELDS => file_get_contents('php://input'),
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_FOLLOWLOCATION => false,
    CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
    CURLOPT_PATH_AS_IS => true,
    CURLOPT_CONNECTTIMEOUT => 5,
    CURLOPT_TIMEOUT => 30,
]);
if ($_SERVER['REQUEST_METHOD'] === 'HEAD') curl_setopt($ch, CURLOPT_NOBODY, true);
$responseHeaders = [];
curl_setopt($ch, CURLOPT_HEADERFUNCTION, function($ch, $line) use (&$responseHeaders) {
    if (strpos($line, 'HTTP/') === 0) $responseHeaders = [];
    elseif (strpos($line, ':') !== false) $responseHeaders[] = trim($line);
    return strlen($line);
});
$body = curl_exec($ch);
$code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);
if ($body === false || !$code) proxyError(502, 'UPSTREAM_UNAVAILABLE');
$connection = '';
foreach ($responseHeaders as $line) {
    if (stripos($line, 'Connection:') === 0) $connection .= ',' . substr($line, strlen('Connection:'));
}
$excluded = hopHeaders($connection);
foreach ($responseHeaders as $line) {
    [$name, $value] = explode(':', $line, 2);
    $name = strtolower($name);
    $value = trim($value);
    if (in_array($name, $excluded, true)) continue;
    if ($name === 'location') {
        $upstream = rtrim($AE2_SERVER_HOST, '/') . '/';
        if (strpos($value, $upstream) === 0) $value = $mount . '/' . substr($value, strlen($upstream));
        elseif (strpos($value, '/') === 0 && strpos($value, '//') !== 0) $value = $mount . $value;
    } elseif ($name === 'set-cookie' && $mount !== '') {
        $value = preg_replace_callback('~(;\s*Path=)/~i', function($match) use ($mount) {
            return $match[1] . $mount . '/';
        }, $value);
    }
    header($name . ': ' . $value, false);
}
http_response_code($code);
if ($_SERVER['REQUEST_METHOD'] !== 'HEAD') echo $body;
