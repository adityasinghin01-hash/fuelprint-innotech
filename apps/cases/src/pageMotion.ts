/** Route feedback only: server refreshes and form edits never restart the animation. */
export function animatePage(element: HTMLElement | null) {
  if(!element?.animate||window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)return null;
  return element.animate([{opacity:.45,transform:'translateY(8px)'},{opacity:1,transform:'translateY(0)'}],{
    duration:340,easing:'cubic-bezier(.22,1,.36,1)',
  });
}
