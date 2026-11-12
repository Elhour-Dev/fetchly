/* Fetchy languages: English, French, Arabic.
   The app's text is English. This file swaps it for French or Arabic after the page draws it,
   using the table in i18n-data.js (English text -> [French, Arabic]). Unknown text stays English.
   Arabic turns the page right-to-left. The choice is saved; the default is the system language. */
(function () {
  'use strict';

  var LANGS = { en: 'English', fr: 'Français', ar: 'العربية' };
  var D = window.FETCHY_I18N || { words: {}, patterns: [] };
  var lang = 'en';
  try {
    var saved = localStorage.getItem('fetchly-lang');
    var sys = String(navigator.language || 'en').slice(0, 2).toLowerCase();
    lang = LANGS[saved] ? saved : (LANGS[sys] ? sys : 'en');
  } catch (_) {}
  var col = lang === 'fr' ? 0 : 1;

  var PATS = (D.patterns || []).map(function (p) { return [new RegExp(p[0]), p[1 + col]]; });
  var cache = Object.create(null);
  var origText = new WeakMap();    // translated text node -> its English text
  var origAttr = new WeakMap();    // element -> { attribute: English }

  function norm(s) { return String(s).replace(/\s+/g, ' ').trim(); }

  function fill(tpl, m) {
    return tpl.replace(/\{(\d)\}|\$(\d)/g, function (_, rec, raw) {
      return rec ? translate(m[+rec] || '') : (m[+raw] || '');
    });
  }

  // English (already trimmed) -> translated text, or the same text when nothing matches.
  function translate(s) {
    if (lang === 'en' || !s) return s;
    if (cache[s] !== undefined) return cache[s];
    var out = lookup(s);
    cache[s] = out;
    return out;
  }

  function lookup(s) {
    var w = D.words[s];
    if (w) return w[col];
    for (var i = 0; i < PATS.length; i++) {
      var m = PATS[i][0].exec(s);
      if (m) return fill(PATS[i][1], m);
    }
    var parts;
    if (s.indexOf(' · ') > 0) {
      parts = s.split(' · ');
      return parts.map(translate).join(' · ');
    }
    if (/^[^,]+(, [^,]+)+$/.test(s) && s.length < 80) {
      parts = s.split(', ');
      var tr = parts.map(translate);
      if (tr.join() !== parts.join()) return tr.join(col ? '، ' : ', ');
    }
    if (s.length > 40) {   // a few sentences in one text: translate each sentence it knows
      parts = s.replace(/([.!?])\s+/g, '$1|~|').split('|~|');
      if (parts.length > 1) {   // longest known run of sentences first
        var out = [], i = 0;
        while (i < parts.length) {
          var j = parts.length, hit = null;
          for (; j > i; j--) { var run = parts.slice(i, j).join(' '); if (D.words[run]) { hit = D.words[run][col]; break; } }
          if (hit) { out.push(hit); i = j; } else { out.push(translate(parts[i])); i++; }
        }
        return out.join(' ');
      }
    }
    return s;
  }

  function tr(s) {
    var m = /^(\s*)([\s\S]*?)(\s*)$/.exec(String(s));
    if (!m || !m[2]) return s;
    var out = translate(norm(m[2]));
    return out === norm(m[2]) ? s : m[1] + out + m[3];
  }

  var ATTRS = ['placeholder', 'aria-label', 'title', 'alt'];
  var SKIP = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, NOSCRIPT: 1 };

  function doText(node) {
    var p = node.parentNode;
    if (!p || SKIP[p.nodeName]) return;
    var v = node.nodeValue;
    if (!v || !/\S/.test(v)) return;
    var out = tr(v);
    if (out !== v) { origText.set(node, origText.get(node) || v); node.nodeValue = out; }
  }

  function doAttrs(el) {
    for (var i = 0; i < ATTRS.length; i++) {
      var a = ATTRS[i];
      if (!el.hasAttribute(a)) continue;
      var v = el.getAttribute(a), out = tr(v);
      if (out !== v) {
        var o = origAttr.get(el) || {};
        o[a] = o[a] || v; origAttr.set(el, o);
        el.setAttribute(a, out);
      }
    }
  }

  function walk(root) {
    if (root.nodeType === 3) { doText(root); return; }
    if (root.nodeType !== 1 || SKIP[root.nodeName]) return;
    doAttrs(root);
    for (var c = root.firstChild; c; c = c.nextSibling) walk(c);
  }

  function observe() {
    new MutationObserver(function (list) {
      list.forEach(function (m) {
        if (m.type === 'childList') m.addedNodes.forEach(walk);
        else if (m.type === 'characterData') doText(m.target);
        else if (m.type === 'attributes') doAttrs(m.target);
      });
    }).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  }

  // The English text an element showed before it was translated (for matching, copying).
  function orig(el) {
    var s = '';
    for (var c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) s += origText.get(c) || c.nodeValue;
      else if (c.nodeType === 1) s += orig(c);
    }
    return s;
  }

  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';

  window.i18n = { lang: lang, t: tr, orig: orig, langs: LANGS };

  function init() {
    if (lang !== 'en') {
      document.title = tr(document.title);
      walk(document.body);
      observe();
    }
    var sel = document.getElementById('langSelect');
    if (sel) {
      Object.keys(LANGS).forEach(function (k) {
        var o = document.createElement('option');
        o.value = k; o.textContent = LANGS[k]; o.lang = k;
        sel.appendChild(o);
      });
      sel.value = lang;
      sel.addEventListener('change', function () {
        try { localStorage.setItem('fetchly-lang', sel.value); } catch (_) {}
        location.reload();
      });
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
