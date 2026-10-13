<?php
// Shared code: config, database, request trace.
$cfg = require '/etc/demo-app/config.php';
$t0 = microtime(true);
$spans = [];
$db = null;
$dbError = '';

function span($name, $start) {
    global $spans;
    $spans[] = [$name, round((microtime(true) - $start) * 1000, 1)];
}

function db() {
    global $cfg, $db, $dbError;
    if ($db || $dbError) return $db;
    $s = microtime(true);
    mysqli_report(MYSQLI_REPORT_OFF);
    $m = mysqli_init();
    $m->options(MYSQLI_OPT_CONNECT_TIMEOUT, 2);
    if (@$m->real_connect($cfg['db_host'], $cfg['db_user'], $cfg['db_pass'], $cfg['db_name'])) {
        $db = $m;
    } else {
        $dbError = mysqli_connect_error() ?: 'no answer';
    }
    span('Connect to database', $s);
    return $db;
}

function q($sql, $types = '', ...$args) {
    $s = microtime(true);
    $st = db()->prepare($sql);
    if ($types) $st->bind_param($types, ...$args);
    $st->execute();
    $r = $st->get_result();
    span(preg_match('/(?:FROM|INTO|UPDATE)\s+(\w+)/i', $sql, $m) ? strtok($sql, ' ') . ' ' . $m[1] : 'Check database', $s);
    return $r ? $r->fetch_all(MYSQLI_ASSOC) : [];
}

function client_ip() {
    foreach (['HTTP_CF_CONNECTING_IP', 'HTTP_X_FORWARDED_FOR'] as $h) {
        if (!empty($_SERVER[$h])) return trim(explode(',', $_SERVER[$h])[0]);
    }
    return $_SERVER['REMOTE_ADDR'] ?? '';
}

function where_from($ip) {
    if ($ip === '127.0.0.1' || $ip === '::1') return 'This server';
    if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE)) return 'Local network';
    return 'Internet';
}

function browser($ua) {
    foreach (['Edg' => 'Edge', 'OPR' => 'Opera', 'Chrome' => 'Chrome', 'Firefox' => 'Firefox', 'Safari' => 'Safari', 'curl' => 'curl'] as $k => $v) {
        if (stripos($ua, $k) !== false) {
            $os = preg_match('/Windows|Mac OS|Android|iPhone|Linux/', $ua, $m) ? $m[0] : '';
            return trim("$v $os");
        }
    }
    return $ua ? substr($ua, 0, 40) : 'Unknown';
}

// Save this request as a trace. Skipped when the database is down.
function save_trace($path, $status) {
    global $t0, $spans, $db;
    if (!$db) return;
    $ms = round((microtime(true) - $t0) * 1000, 1);
    $dbMs = 0;
    foreach ($spans as $sp) $dbMs += $sp[1];
    $ip = client_ip();
    $ua = substr($_SERVER['HTTP_USER_AGENT'] ?? '', 0, 255);
    $st = $db->prepare('INSERT INTO traces (ip, browser, path, status, total_ms, db_ms, spans) VALUES (?,?,?,?,?,?,?)');
    $b = browser($ua);
    $js = json_encode($spans);
    $st->bind_param('sssidds', $ip, $b, $path, $status, $ms, $dbMs, $js);
    $st->execute();
    $st = $db->prepare('INSERT INTO visitors (ip, browser, first_seen, last_seen, hits) VALUES (?,?,NOW(),NOW(),1)
        ON DUPLICATE KEY UPDATE last_seen = NOW(), hits = hits + 1');
    $st->bind_param('ss', $ip, $b);
    $st->execute();
}

function h($s) { return htmlspecialchars((string)$s, ENT_QUOTES); }
