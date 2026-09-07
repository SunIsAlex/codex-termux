import { marked } from './vendor/marked.js';
import DOMPurify from './vendor/purify.js';

export function markdown(node, value) {
  const fragment = DOMPurify.sanitize(marked.parse(value, { gfm: true }), {
    RETURN_DOM_FRAGMENT: true,
    ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'del', 'blockquote', 'ul', 'ol', 'li', 'pre', 'code', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'input'],
    ALLOWED_ATTR: ['href', 'title', 'start', 'align', 'type', 'checked', 'disabled'],
  });
  for (const link of fragment.querySelectorAll('a')) {
    const href = link.getAttribute('href') || '';
    if (!/^(https?:|mailto:)/i.test(href)) link.removeAttribute('href');
    else { link.target = '_blank'; link.rel = 'noopener noreferrer'; }
  }
  for (const input of fragment.querySelectorAll('input')) {
    input.type = 'checkbox'; input.disabled = true;
  }
  for (const pre of fragment.querySelectorAll('pre')) {
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = '复制代码';
    button.onclick = async () => {
      try { await navigator.clipboard.writeText(pre.textContent); button.textContent = '已复制'; }
      catch { button.textContent = '复制失败，请长按选择'; }
    };
    pre.before(button);
  }
  node.replaceChildren(fragment);
}
