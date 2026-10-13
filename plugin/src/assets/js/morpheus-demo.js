// Demo Apps dashboard: refresh every 30 s, keep open rows, local times, approve, reject and delete in place
(function () {
  'use strict';
  if (window.morpheusDemoDash) return;
  window.morpheusDemoDash = true;
  var P = '/plugin/morpheus-demo/';

  function root() { return document.getElementById('mdx-root'); }

  function localize(r) {
    Array.prototype.forEach.call((r || document).querySelectorAll('[data-ms]'), function (e) {
      var ms = parseInt(e.getAttribute('data-ms'), 10);
      if (ms) e.textContent = new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
    });
  }

  function message(text, ok) {
    var box = document.getElementById('mdx-msg');
    if (!box) return;
    box.textContent = '';
    var d = document.createElement('div');
    d.className = 'mdx-alert ' + (ok ? 'mdx-alert-ok' : 'mdx-alert-error');
    d.textContent = text;
    box.appendChild(d);
  }

  function refresh(keepMessage) {
    var r = root();
    if (!r || document.hidden) return;
    var open = Array.prototype.map.call(r.querySelectorAll('details[open]'), function (d) { return d.getAttribute('data-key'); });
    var msg = document.getElementById('mdx-msg');
    var kept = keepMessage && msg ? msg.innerHTML : '';
    fetch(P + 'content', { credentials: 'same-origin', cache: 'no-store' })
      .then(function (x) { return x.ok ? x.text() : null; })
      .then(function (html) {
        if (!html) return;
        var box = document.createElement('div');
        box.innerHTML = html;
        var fresh = box.querySelector('#mdx-root');
        if (!fresh) return;
        open.forEach(function (k) { var d = fresh.querySelector('details[data-key="' + k + '"]'); if (d) d.setAttribute('open', ''); });
        if (kept) { var m = fresh.querySelector('#mdx-msg'); if (m) m.innerHTML = kept; }
        localize(fresh);
        var cur = root();
        if (cur) cur.replaceWith(fresh);
      })
      .catch(function () {});
  }

  function post(action, fields, btn) {
    var r = root();
    var body = Object.keys(fields).map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(fields[k]); }).join('&');
    var h = { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' };
    var hn = r.getAttribute('data-csrf-header'), hv = r.getAttribute('data-csrf-value');
    if (hn && hv) h[hn] = hv;
    if (btn) btn.disabled = true;
    fetch(P + action, { method: 'POST', credentials: 'same-origin', headers: h, body: body })
      .then(function (x) { return x.ok ? x.json() : { ok: false, text: 'The server answered ' + x.status + '.' }; })
      // Morpheus takes a moment to update the order, so refresh twice
      .then(function (res) { message(res.text, res.ok); setTimeout(function () { refresh(true); }, 1500); setTimeout(function () { refresh(true); }, 6000); })
      .catch(function () { message('Could not reach Morpheus. Try again.', false); if (btn) btn.disabled = false; });
  }

  document.addEventListener('click', function (e) {
    var t = e.target;
    var a = t.closest && t.closest('#mdx-root [data-mdx-approve]');
    if (a) { post('approval', { item: a.getAttribute('data-mdx-approve'), 'do': a.getAttribute('data-do') }, a); return; }
    var d = t.closest && t.closest('#mdx-root [data-mdx-delete]');
    if (d) {
      // Delete needs a second click within 5 seconds
      if (!d.classList.contains('mdx-btn-armed')) {
        d.classList.add('mdx-btn-armed');
        d.textContent = 'Click to confirm';
        setTimeout(function () { if (document.body.contains(d)) { d.classList.remove('mdx-btn-armed'); d.textContent = 'Delete app'; } }, 5000);
        return;
      }
      post('delete-app', { app: d.getAttribute('data-mdx-delete') }, d);
    }
  });

  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    var r = root();
    if (!r) return;
    Array.prototype.forEach.call(r.querySelectorAll('details[open]'), function (x) { x.removeAttribute('open'); });
  });

  var tries = 0, wait = setInterval(function () {
    if (root() || ++tries > 80) { clearInterval(wait); localize(root()); }
  }, 250);
  setInterval(function () { refresh(false); }, 30000);
})();
