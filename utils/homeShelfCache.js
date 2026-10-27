const CACHE_VERSION = 1
const MAX_SNAPSHOT_LENGTH = 2 * 1024 * 1024

export function homeCacheKey(config) {
  if (!config?.id || !config.address || !config.userId) return null
  return `homeShelves:${JSON.stringify([config.id, config.address, config.userId])}`
}

export function serializeHomeSnapshot(config, library, shelves) {
  const scope = homeCacheKey(config)
  if (!scope || !library?.id || !Array.isArray(shelves)) return null
  // Download associations are refreshed from the device database on every launch.
  const value = JSON.stringify({ version: CACHE_VERSION, scope, library, shelves }, (key, value) => (key === 'localLibraryItem' ? undefined : value))
  return value.length <= MAX_SNAPSHOT_LENGTH ? value : null
}

export function parseHomeSnapshot(value, config) {
  if (!value || value.length > MAX_SNAPSHOT_LENGTH) return null
  try {
    const snapshot = JSON.parse(value)
    if (snapshot.version !== CACHE_VERSION || snapshot.scope !== homeCacheKey(config) || !snapshot.library?.id || !Array.isArray(snapshot.shelves)) return null
    if (!snapshot.shelves.every((shelf) => shelf && typeof shelf.id === 'string' && Array.isArray(shelf.entities))) return null
    return snapshot
  } catch {
    return null
  }
}

export function mergeHomeShelves(shelves, localItems, localCategories, mediaType, configId) {
  const result = shelves.map((shelf) => ({
    ...shelf,
    entities: shelf.entities.map((entity) => {
      if (!['book', 'podcast', 'episode'].includes(shelf.type)) return entity
      const localLibraryItem = localItems.find((item) => item.libraryItemId === entity.id && item.serverConnectionConfigId === configId)
      return { ...entity, localLibraryItem }
    })
  }))
  result.push(...localCategories.filter((shelf) => shelf.type === mediaType && !shelf.localOnly))
  return result
}
