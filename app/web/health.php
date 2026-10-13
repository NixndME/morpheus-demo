<?php
// Live state for the Morpheus dashboard. Not saved as a trace.
require __DIR__ . '/lib.php';
header('Content-Type: application/json');
header('Cache-Control: no-store');
$out = ['app' => $cfg['order']['app_name'], 'web' => ['host' => gethostname(), 'ip' => $cfg['web_ip'], 'up' => true],
        'db' => ['host' => $cfg['db_host'], 'up' => false]];
$s = microtime(true);
if (db()) {
    $out['db']['up'] = true;
    $out['db']['version'] = explode('-', q('SELECT VERSION() AS v')[0]['v'])[0];
    $out['db']['ms'] = round((microtime(true) - $s) * 1000, 1);
    $out['visitors'] = (int)q('SELECT COUNT(*) AS n FROM visitors')[0]['n'];
    $r = q('SELECT COUNT(*) AS n, ROUND(AVG(total_ms), 1) AS avg, MAX(at) AS last FROM traces WHERE at > NOW() - INTERVAL 1 DAY')[0];
    $out['requests'] = (int)$r['n'];
    $out['avgMs'] = (float)$r['avg'];
    $out['last'] = $r['last'];
    $out['messages'] = (int)q('SELECT COUNT(*) AS n FROM guestbook')[0]['n'];
}
echo json_encode($out);
