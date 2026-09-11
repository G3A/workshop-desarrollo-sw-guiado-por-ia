// Comportamiento de las páginas de fase:
//  - "← Volver al diagrama": cierra esta pestaña (el diagrama sigue abierto en la
//    pestaña original). Si el navegador no la deja cerrar, navega al diagrama.
//  - MODO FOCO: si se llega por una caja (con #id), se muestra SOLO esa sección,
//    con una barra para ir a Anterior/Siguiente o ver toda la fase.
(function () {
  var back = document.querySelector('a.back');
  var backHref = back ? back.getAttribute('href') : '../Playbook-sdlc-ia.html';
  function volverAlDiagrama(e) {
    if (e) e.preventDefault();
    window.close();                                                    // cierra esta pestaña (el diagrama sigue abierto)
    setTimeout(function () { window.location.href = backHref; }, 150); // respaldo si el navegador no la deja cerrar
  }
  if (back) back.addEventListener('click', volverAlDiagrama);

  var sections = Array.prototype.slice.call(document.querySelectorAll('section[id]'));
  if (!sections.length) return; // p. ej. el índice no tiene secciones

  // enlace "← Volver al diagrama" al final de CADA sección, con el mismo comportamiento
  sections.forEach(function (s) {
    var a = document.createElement('a');
    a.className = 'sec-back';
    a.href = backHref;
    a.textContent = '← Volver al diagrama';
    a.addEventListener('click', volverAlDiagrama);
    s.appendChild(a);
  });

  // barra de navegación de foco
  var bar = document.createElement('div');
  bar.className = 'focusbar';
  bar.innerHTML =
    '<span class="phase"></span>' +
    '<button data-nav="prev">◀ Anterior</button>' +
    '<span class="pos"></span>' +
    '<button data-nav="next">Siguiente ▶</button>' +
    '<a class="all" href="#" data-nav="all">Ver toda la fase</a>';
  sections[0].parentNode.insertBefore(bar, sections[0]);
  var h1 = document.querySelector('h1');
  bar.querySelector('.phase').textContent = h1 ? h1.textContent.trim() : '';
  var posEl = bar.querySelector('.pos');
  var btnPrev = bar.querySelector('[data-nav="prev"]');
  var btnNext = bar.querySelector('[data-nav="next"]');

  function idIndex(id) { for (var i = 0; i < sections.length; i++) { if (sections[i].id === id) return i; } return -1; }

  function focusOn(id) {
    var idx = idIndex(id);
    if (idx < 0) { unfocus(); return; }
    document.body.classList.add('focus');
    sections.forEach(function (s) { s.classList.toggle('active', s.id === id); });
    posEl.textContent = 'Sección ' + (idx + 1) + ' de ' + sections.length;
    btnPrev.disabled = (idx === 0);
    btnNext.disabled = (idx === sections.length - 1);
    window.scrollTo(0, 0);
  }
  function unfocus() {
    document.body.classList.remove('focus');
    sections.forEach(function (s) { s.classList.remove('active'); });
  }
  function go(idx) {
    if (idx < 0 || idx >= sections.length) return;
    location.hash = '#' + sections[idx].id; // dispara hashchange -> focusOn
  }

  bar.addEventListener('click', function (e) {
    var el = e.target.closest('[data-nav]'); if (!el) return;
    var nav = el.getAttribute('data-nav');
    e.preventDefault();
    var cur = idIndex((location.hash || '').slice(1));
    if (nav === 'prev') go(cur - 1);
    else if (nav === 'next') go(cur + 1);
    else if (nav === 'all') { unfocus(); history.replaceState(null, '', location.pathname); }
  });

  function fromHash() {
    var id = (location.hash || '').slice(1);
    if (id && idIndex(id) >= 0) focusOn(id); else unfocus();
  }
  window.addEventListener('hashchange', fromHash);
  fromHash();
})();
