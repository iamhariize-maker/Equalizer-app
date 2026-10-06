(() => {
  'use strict';
  const menu = document.querySelector('.menu-toggle');
  const nav = document.querySelector('#main-nav');
  const setMenu = (open) => {
    if (!menu || !nav) return;
    menu.setAttribute('aria-expanded', String(open));
    menu.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    nav.classList.toggle('is-open', open);
  };
  menu?.addEventListener('click', () => setMenu(menu.getAttribute('aria-expanded') !== 'true'));
  nav?.querySelectorAll('a').forEach(a => a.addEventListener('click', () => setMenu(false)));
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && menu?.getAttribute('aria-expanded') === 'true') {
      setMenu(false); menu.focus();
    }
  });
  document.addEventListener('click', e => {
    if (menu?.getAttribute('aria-expanded') === 'true' && !nav.contains(e.target) && !menu.contains(e.target)) setMenu(false);
  });

  const tabs = [...document.querySelectorAll('[role="tab"]')];
  function selectTab(tab) {
    tabs.forEach(t => {
      const active = t === tab;
      t.setAttribute('aria-selected', String(active));
      t.tabIndex = active ? 0 : -1;
      document.getElementById(t.getAttribute('aria-controls')).hidden = !active;
    });
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => selectTab(tab));
    tab.addEventListener('keydown', e => {
      let target;
      if (e.key === 'ArrowRight') target = tabs[(index + 1) % tabs.length];
      if (e.key === 'ArrowLeft') target = tabs[(index - 1 + tabs.length) % tabs.length];
      if (e.key === 'Home') target = tabs[0];
      if (e.key === 'End') target = tabs[tabs.length - 1];
      if (target) { e.preventDefault(); selectTab(target); target.focus(); }
    });
  });

  const feels = {
    balanced: ['Balanced', 'A neutral starting point, true to the recording.'],
    warm: ['Warm', 'A fuller body, with a softer top.'],
    bright: ['Bright', 'An open feel, with an airy top.'],
    punchy: ['Punchy', 'A tighter, more pronounced low-end feel.']
  };
  const feelButtons = [...document.querySelectorAll('[data-feel]')];
  feelButtons.forEach(button => button.addEventListener('click', () => {
    feelButtons.forEach(b => b.setAttribute('aria-pressed', String(b === button)));
    const [name, description] = feels[button.dataset.feel];
    document.querySelector('.feel-name').textContent = name;
    document.querySelector('.feel-text').textContent = description;
  }));

  const gallery = document.querySelector('.gallery-track');
  const prev = document.getElementById('gallery-prev');
  const next = document.getElementById('gallery-next');
  function updateGalleryButtons() {
    if (!gallery || !prev || !next) return;
    prev.disabled = gallery.scrollLeft < 5;
    next.disabled = gallery.scrollLeft + gallery.clientWidth >= gallery.scrollWidth - 5;
  }
  function scrollGallery(direction) {
    if (!gallery) return;
    const card = gallery.querySelector('.gallery-card');
    const gap = parseFloat(getComputedStyle(gallery).gap) || 0;
    const step = card.getBoundingClientRect().width + gap;
    gallery.scrollBy({ left: step * direction, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  }
  prev?.addEventListener('click', () => scrollGallery(-1));
  next?.addEventListener('click', () => scrollGallery(1));
  gallery?.addEventListener('scroll', updateGalleryButtons, { passive: true });
  window.addEventListener('resize', updateGalleryButtons);
  updateGalleryButtons();

  const dialog = document.getElementById('screenshot-dialog');
  const dialogImage = document.getElementById('dialog-image');
  let lastOpener;
  if (dialog && typeof dialog.showModal === 'function') {
    document.querySelectorAll('[data-screen]').forEach(button => button.addEventListener('click', () => {
      lastOpener = button;
      document.getElementById('dialog-title').textContent = button.dataset.title;
      dialogImage.src = `site-assets/${button.dataset.screen}.png`;
      dialogImage.alt = `${button.dataset.title} — original Android emulator screenshot`;
      dialog.showModal(); document.body.classList.add('dialog-open');
    }));
    document.getElementById('dialog-close').addEventListener('click', () => dialog.close());
    dialog.addEventListener('click', e => {
      if (e.target !== dialog) return;
      const r = dialog.getBoundingClientRect();
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) dialog.close();
    });
    dialog.addEventListener('close', () => {
      document.body.classList.remove('dialog-open'); lastOpener?.focus();
    });
  } else {
    document.querySelectorAll('[data-screen]').forEach(button => button.addEventListener('click', () => {
      window.location.href = `site-assets/${button.dataset.screen}.png`;
    }));
  }

  if ('IntersectionObserver' in window && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) { entry.target.classList.add('is-visible'); observer.unobserve(entry.target); }
      });
    }, { threshold: 0.08 });
    document.querySelectorAll('.section-top, .svaresa-intro, .analysis-visual, .analysis-copy, .guide-copy, .craft-heading, .craft-item, .gallery-heading, .questions-heading, .faq-list').forEach(el => {
      el.classList.add('reveal-ready'); observer.observe(el);
    });
  }
})();
