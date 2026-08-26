// Minimal DOM helpers. The whole UI is hand-rolled rather than built on a framework so the
// binary stays dependency-free and there is no build step between editing a file and
// reloading the page.

/**
 * Build an element. Attributes go in `attrs`; anything starting with "on" is bound as a
 * listener, `class`/`style`/`html` are special-cased, everything else becomes an attribute.
 * Children may be nodes, strings, or nested arrays; null and undefined are skipped so
 * conditional children can be written inline as `cond && el(...)`.
 */
export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
    else if (k === 'class') node.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
    else if (k === 'html') node.innerHTML = v;
    else if (k === 'dataset') Object.assign(node.dataset, v);
    else node.setAttribute(k, v === true ? '' : v);
  }
  append(node, children);
  return node;
}

export function append(node, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
}

export function mount(node, ...children) {
  clear(node);
  append(node, children);
  return node;
}

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

/** A labelled block, the layout unit most of these views are built from. */
export function card(title, ...children) {
  return el('section', { class: 'card' },
    title && el('h2', { class: 'card-title' }, title),
    ...children);
}

/** A big number with a caption - the dashboard's primary readout. */
export function stat(label, value, sub) {
  return el('div', { class: 'stat' },
    el('div', { class: 'stat-label' }, label),
    el('div', { class: 'stat-value' }, value === null || value === undefined ? '—' : value),
    sub ? el('div', { class: 'stat-sub' }, sub) : null);
}

export function spinner(text = 'Loading') {
  return el('div', { class: 'loading' }, el('span', { class: 'spin' }), text);
}

export function errorBox(message, retry) {
  return el('div', { class: 'error-box' },
    el('strong', {}, 'Something went wrong'),
    el('p', {}, String(message)),
    retry ? el('button', { class: 'btn', onclick: retry }, 'Try again') : null);
}

export function empty(message) {
  return el('div', { class: 'empty' }, message);
}
