<?php
require __DIR__ . '/lib.php';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $name = trim(substr($_POST['name'] ?? '', 0, 60));
    $msg = trim(substr($_POST['message'] ?? '', 0, 300));
    if ($msg !== '' && db()) q('INSERT INTO guestbook (name, message, ip) VALUES (?,?,?)', 'sss', $name ?: 'Guest', $msg, client_ip());
    save_trace('/ (guest book)', 303);
    header('Location: ./', true, 303);
    exit;
}

$o = $cfg['order'];
$webIp = $cfg['web_ip'] ?: ($_SERVER['SERVER_ADDR'] ?? '');

// Live part, also served alone to the page script every few seconds.
function live() {
    global $cfg, $webIp;
    $ok = (bool)db();
    $ping = null; $ver = ''; $visitors = []; $traces = []; $book = [];
    if ($ok) {
        $s = microtime(true);
        $ver = q('SELECT VERSION() AS v')[0]['v'];
        $ping = round((microtime(true) - $s) * 1000, 1);
        $visitors = q('SELECT ip, browser, first_seen, last_seen, hits FROM visitors ORDER BY last_seen DESC LIMIT 15');
        $traces = q('SELECT id, at, ip, browser, path, status, total_ms, db_ms, spans FROM traces ORDER BY id DESC LIMIT 15');
        $book = q('SELECT name, message, at FROM guestbook ORDER BY id DESC LIMIT 8');
    }
    $ip = client_ip();
    ob_start(); ?>
<section>
  <h2>How it is connected</h2>
  <?php if (!$ok): ?><div class="alert bad">Database down. The web tier cannot reach <?= h($cfg['db_host']) ?>. Traces are paused.</div><?php endif ?>
  <div class="path">
    <div class="node ok"><b>You</b><span><?= h($ip) ?></span><span><?= h(browser($_SERVER['HTTP_USER_AGENT'] ?? '')) ?></span><span><?= h(where_from($ip)) ?></span></div>
    <div class="link ok">HTTP :80</div>
    <div class="node ok"><b>Web tier</b><span><?= h(gethostname()) ?></span><span><?= h($webIp) ?></span><span>Apache + PHP <?= h(PHP_MAJOR_VERSION . '.' . PHP_MINOR_VERSION) ?></span></div>
    <div class="link <?= $ok ? 'ok' : 'bad' ?>"><?= $ok ? 'MySQL :3306, ' . h($ping) . ' ms' : 'No answer' ?></div>
    <div class="node <?= $ok ? 'ok' : 'bad' ?>"><b>Database tier</b><span><?= h($cfg['db_host']) ?></span><span><?= $ok ? 'MariaDB ' . h(explode('-', $ver)[0]) : 'Not reachable' ?></span><span><?= $ok ? 'Up' : 'Down' ?></span></div>
  </div>
</section>
<section>
  <h2>Who is connected</h2>
  <table>
    <tr><th>Address</th><th>From</th><th>Browser</th><th>First seen</th><th>Last seen</th><th>Requests</th></tr>
    <?php foreach ($visitors as $v): ?>
    <tr><td><?= h($v['ip']) ?><?= $v['ip'] === $ip ? ' <i>(you)</i>' : '' ?></td><td><?= h(where_from($v['ip'])) ?></td><td><?= h($v['browser']) ?></td><td><?= h($v['first_seen']) ?></td><td><?= h($v['last_seen']) ?></td><td><?= h($v['hits']) ?></td></tr>
    <?php endforeach ?>
    <?php if (!$visitors): ?><tr><td colspan="6" class="muted"><?= $ok ? 'No visitors yet.' : 'Not available while the database is down.' ?></td></tr><?php endif ?>
  </table>
</section>
<section>
  <h2>Traces</h2>
  <p class="muted">Every request, with the time spent in the web tier and in the database. Click a row to see its steps.</p>
  <table class="traces">
    <tr><th>Time</th><th>Address</th><th>Page</th><th>Status</th><th>Total</th><th>Database</th></tr>
    <?php foreach ($traces as $t): ?>
    <tr class="row" data-id="<?= h($t['id']) ?>"><td><?= h(substr($t['at'], 11)) ?></td><td><?= h($t['ip']) ?></td><td><?= h($t['path']) ?></td><td><?= h($t['status']) ?></td><td><?= h($t['total_ms']) ?> ms</td><td><?= h($t['db_ms']) ?> ms</td></tr>
    <tr class="detail" data-for="<?= h($t['id']) ?>"><td colspan="6">
      <?php $max = max(1, (float)$t['total_ms']); foreach (json_decode($t['spans'], true) ?: [] as $sp): ?>
      <div class="span"><span><?= h($sp[0]) ?></span><span class="bar"><i style="width:<?= min(100, round($sp[1] / $max * 100)) ?>%"></i></span><span><?= h($sp[1]) ?> ms</span></div>
      <?php endforeach ?>
      <div class="span"><span>Web tier total</span><span class="bar"><i class="web" style="width:100%"></i></span><span><?= h($t['total_ms']) ?> ms</span></div>
    </td></tr>
    <?php endforeach ?>
    <?php if (!$traces): ?><tr><td colspan="6" class="muted"><?= $ok ? 'No traces yet.' : 'Not available while the database is down.' ?></td></tr><?php endif ?>
  </table>
</section>
<section>
  <h2>Guest book</h2>
  <p class="muted">Messages are saved in the database tier.</p>
  <?php foreach ($book as $b): ?><div class="msg"><b><?= h($b['name']) ?></b> <span class="muted"><?= h($b['at']) ?></span><div><?= h($b['message']) ?></div></div><?php endforeach ?>
  <?php if (!$book): ?><p class="muted"><?= $ok ? 'No messages yet.' : 'Not available while the database is down.' ?></p><?php endif ?>
</section>
<?php
    return ob_get_clean();
}

if (isset($_GET['live'])) {
    $html = live();
    save_trace('/ (refresh)', 200);
    header('Cache-Control: no-store');
    echo $html;
    exit;
}
$html = live();
save_trace('/', 200);
?><!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><?= h($o['app_name']) ?></title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<header>
  <h1><?= h($o['app_name']) ?></h1>
  <span class="tag"><?= h($o['environment']) ?></span>
</header>
<main>
<section>
  <h2>What this is</h2>
  <p>A two tier web app built by Morpheus from the self service catalog. The web tier runs Apache and PHP. The database tier runs MariaDB on its own VM. Morpheus made both VMs on HVM, ran the install workflows and wired the web tier to the database.</p>
  <dl>
    <dt>Ordered by</dt><dd><?= h($o['owner']) ?></dd>
    <dt>Ordered on</dt><dd><?= h($o['ordered']) ?></dd>
    <dt>Size</dt><dd><?= h($o['size']) ?></dd>
    <dt>Monitoring</dt><dd><?= h($o['monitoring']) ?><?php if ($o['monitoring_url']): ?> (<a href="<?= h($o['monitoring_url']) ?>">open</a>)<?php endif ?></dd>
    <dt>Backup</dt><dd><?= h($o['backup']) ?></dd>
    <dt>Cost owner</dt><dd><?= h($o['cost_owner']) ?></dd>
    <dt>Cost ID</dt><dd><?= h($o['cost_id']) ?></dd>
  </dl>
</section>
<div id="live"><?= $html ?></div>
<section>
  <h2>Write a message</h2>
  <form method="post">
    <input name="name" placeholder="Your name" maxlength="60">
    <input name="message" placeholder="Message" maxlength="300" required>
    <button>Save</button>
  </form>
</section>
</main>
<script src="app.js"></script>
</body>
</html>
