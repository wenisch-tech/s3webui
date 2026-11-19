// In the desktop edition links to other sites must open in the default browser, not inside the
// app window. `desktopBridge` is only there in that edition; elsewhere nothing changes.

/** True for http(s) and mailto links that leave the app. */
export function isExternalLink(href, location = globalThis.location) {
  let url;
  try {
    url = new URL(href, location.href);
  } catch {
    return false;
  }
  if (url.protocol === 'mailto:') return true;
  return (url.protocol === 'http:' || url.protocol === 'https:') && url.origin !== location.origin;
}

/** Click handler: sends external links to the desktop bridge instead of loading them in the window. */
export function interceptExternalClick(event, bridge = globalThis.desktopBridge) {
  if (!bridge || event.defaultPrevented || event.button !== 0) return false;
  const link = event.target.closest?.('a[href]');
  if (!link || !isExternalLink(link.href)) return false;
  event.preventDefault();
  bridge.openExternal(new URL(link.href, globalThis.location.href).href);
  return true;
}
