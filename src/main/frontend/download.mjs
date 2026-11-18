// Object and ZIP downloads are plain navigations that the server answers with
// "Content-Disposition: attachment". The desktop edition's embedded browser cannot save those
// itself, so it exposes a `desktopBridge` that saves the file through a native dialog.

const DOWNLOAD_PATH = /\/api\/buckets\/[^/]+\/objects\/(download|zip)$/;

export const isDownloadPath = pathname => DOWNLOAD_PATH.test(pathname);

/** Hands the download to the desktop bridge when there is one, otherwise navigates as usual. */
export function startDownload(url, { bridge = globalThis.desktopBridge, location = globalThis.location } = {}) {
  const absolute = new URL(url, location.href).href;
  if (bridge) {
    bridge.download(absolute);
  } else {
    location.href = url;
  }
}

/** Click handler: turns a click on a download link into startDownload when a bridge is present. */
export function interceptDownloadClick(event, bridge = globalThis.desktopBridge) {
  if (!bridge || event.defaultPrevented || event.button !== 0) return false;
  const link = event.target.closest?.('a[href]');
  if (!link) return false;
  const url = new URL(link.href, globalThis.location.href);
  if (url.origin !== globalThis.location.origin || !isDownloadPath(url.pathname)) return false;
  event.preventDefault();
  startDownload(url.href, { bridge });
  return true;
}
