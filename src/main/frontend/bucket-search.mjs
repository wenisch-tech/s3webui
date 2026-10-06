export function normalizeBucketPrefix(prefix) {
  const value = String(prefix ?? '');
  return !value || value.endsWith('/') ? value : `${value}/`;
}

export function relativeBucketResultPath(key, prefix) {
  const normalizedPrefix = normalizeBucketPrefix(prefix);
  const value = String(key ?? '');
  return value.startsWith(normalizedPrefix) ? value.slice(normalizedPrefix.length) : value;
}

export function relativeBucketResultParent(key, prefix) {
  const relative = relativeBucketResultPath(key, prefix);
  const withoutTrailingSlash = relative.endsWith('/') ? relative.slice(0, -1) : relative;
  const separator = withoutTrailingSlash.lastIndexOf('/');
  return separator < 0 ? '' : withoutTrailingSlash.slice(0, separator + 1);
}

export function bucketSearchApiPath(bucket, prefix, query) {
  const params = new URLSearchParams({ q: query });
  const normalizedPrefix = normalizeBucketPrefix(prefix);
  if (normalizedPrefix) params.set('prefix', normalizedPrefix);
  return `/api/buckets/${encodeURIComponent(bucket)}/objects/search?${params}`;
}

export function formatObjectSize(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value < 0) return '';
  if (value < 1024) return `${value} B`;
  const kilobytes = value / 1024;
  if (kilobytes < 1024) return `${kilobytes.toFixed(1)} KB`;
  const megabytes = kilobytes / 1024;
  if (megabytes < 1024) return `${megabytes.toFixed(1)} MB`;
  return `${(megabytes / 1024).toFixed(2)} GB`;
}
