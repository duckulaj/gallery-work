(function () {
  const $ = (selector, root = document) => root.querySelector(selector);
  const toast = $('#toast');

  function showToast(message) {
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add('show');
    window.setTimeout(() => toast.classList.remove('show'), 2200);
  }

  document.body.addEventListener('htmx:afterSwap', function (event) {
    if (event.detail.target.id === 'gallery-main') {
      const folderId = event.detail.target.dataset.folderId;
      document.querySelectorAll('.sidebar .folder').forEach(function (link) {
        link.classList.toggle('active', link.getAttribute('hx-get') === '/folders/' + folderId);
      });
    }
  });

  document.body.addEventListener('htmx:responseError', function () {
    showToast('Something went wrong. Please try again.');
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') {
      const preview = $('#preview');
      if (preview) preview.innerHTML = '';
    }
  });

  // ── Folder Picker ───────────────────────────────────────────────────────────
  window.fpToggle = function () {
    const wrapper = $('#fp-wrapper');
    const dropdown = $('#fp-dropdown');
    if (!wrapper || !dropdown) return;
    const opening = dropdown.hidden;
    dropdown.hidden = !opening;
    wrapper.classList.toggle('open', opening);
    // Lazy-load the directory listing only on the first open
    if (opening && !wrapper.dataset.loaded) {
      wrapper.dataset.loaded = '1';
      htmx.ajax('GET', '/browse', { target: '#picker-content', swap: 'outerHTML' });
    }
  };

  window.fpSelect = function (btn) {
    const path = btn.getAttribute('data-path');
    const hidden = $('#directory-path-value');
    const label = $('#fp-label');
    const submit = $('#index-dir-btn');
    if (hidden) hidden.value = path;
    if (label) label.textContent = path;
    if (submit) submit.removeAttribute('disabled');
    const dropdown = $('#fp-dropdown');
    const wrapper = $('#fp-wrapper');
    if (dropdown) dropdown.hidden = true;
    if (wrapper) wrapper.classList.remove('open');
  };

  // Close picker when clicking outside
  document.addEventListener('click', function (e) {
    const wrapper = $('#fp-wrapper');
    if (wrapper && !wrapper.contains(e.target)) {
      const dropdown = $('#fp-dropdown');
      if (dropdown) dropdown.hidden = true;
      wrapper.classList.remove('open');
    }
  });
  // ── AI Panel ────────────────────────────────────────────────────────────────
  window.aiPanelOpen = function () {
    const panel = $('#ai-panel');
    if (!panel) return;
    panel.classList.remove('ai-panel--hidden');
    // Load (or refresh) panel content
    htmx.ajax('GET', '/assets/ai-panel', { target: '#ai-panel', swap: 'outerHTML' });
  };

  window.aiPanelClose = function () {
    const panel = $('#ai-panel');
    if (!panel) return;
    // Stop HTMX polling by clearing inner content first
    panel.innerHTML = '';
    panel.classList.add('ai-panel--hidden');
  };

  if (document.body.dataset.initialPanel === 'imports') {
    window.fpToggle();
  } else if (document.body.dataset.initialPanel === 'processing') {
    window.aiPanelOpen();
  }
})();
document.addEventListener('htmx:configRequest', event => {
  const token = document.querySelector('meta[name="_csrf"]')?.content;
  const header = document.querySelector('meta[name="_csrf_header"]')?.content;
  if (token && header) event.detail.headers[header] = token;
});
