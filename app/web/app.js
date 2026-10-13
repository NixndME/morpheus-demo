// Refresh the live part every 5 seconds and keep open trace rows open.
const live = document.getElementById('live');
const open = new Set();

live.addEventListener('click', e => {
  const row = e.target.closest('tr.row');
  if (!row) return;
  const id = row.dataset.id;
  open.has(id) ? open.delete(id) : open.add(id);
  show();
});

document.addEventListener('keydown', e => {
  if (e.key === 'Escape') { open.clear(); show(); }
});

function show() {
  live.querySelectorAll('tr.row').forEach(r => r.classList.toggle('open', open.has(r.dataset.id)));
  live.querySelectorAll('tr.detail').forEach(r => r.classList.toggle('open', open.has(r.dataset.for)));
}

setInterval(async () => {
  try {
    const r = await fetch('?live=1', { cache: 'no-store' });
    if (r.ok) { live.innerHTML = await r.text(); show(); }
  } catch (e) { /* web tier down, try again */ }
}, 5000);
