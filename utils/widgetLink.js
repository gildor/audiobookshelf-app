export function parseWidgetLink(value) {
  let url
  try {
    url = new URL(value)
  } catch {
    return null
  }
  if (url.protocol !== 'audiobookshelf:' || url.hostname !== 'widget') return null
  const localId = url.searchParams.get('localLibraryItemId')
  const isLocal = !!localId
  const itemId = localId || url.searchParams.get('libraryItemId')
  if (!itemId) return null
  const episodeId = url.searchParams.get(isLocal ? 'localEpisodeId' : 'episodeId')
  let path = `/item/${encodeURIComponent(itemId)}`
  if (episodeId) path += `/${encodeURIComponent(episodeId)}`
  if (isLocal) path += '?noredirect=1'
  return { path, isLocal, serverConnectionConfigId: url.searchParams.get('serverConnectionConfigId') }
}
