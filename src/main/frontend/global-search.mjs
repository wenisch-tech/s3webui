export function globalSearchResultPath(result) {
  const params = new URLSearchParams();
  params.set('prefix', result.type === 'FOLDER' ? result.key : (result.parentPrefix || ''));
  if (result.type === 'FILE') params.set('highlight', result.key);
  return `/buckets/${encodeURIComponent(result.bucket)}?${params}`;
}

export function globalSearchPagePath(query) {
  return `/search?${new URLSearchParams({ q: query })}`;
}
