const { test } = require('node:test')
const assert = require('node:assert/strict')
const { loadSource, componentInstance, deferred } = require('./source.cjs')
const cache = loadSource('utils/homeShelfCache.js')
const home = loadSource('pages/bookshelf/index.vue').default
const config = { id: 'server-a', address: 'https://books.example', userId: 'alice', token: 'secret' }
const library = { id: 'books', mediaType: 'book', settings: { coverAspectRatio: 0 } }
const shelves = [{ id: 'continue-listening', type: 'book', entities: [{ id: 'one', media: { metadata: { title: 'One' } }, localLibraryItem: { id: 'old-download' } }] }]
const plain = (value) => JSON.parse(JSON.stringify(value))

function instance({ cached = true, online = true, get = async () => shelves } = {}) {
  const snapshot = cache.parseHomeSnapshot(cache.serializeHomeSnapshot(config, library, shelves), config)
  const store = {
    state: { user: { user: online ? { id: 'alice' } : null, serverConnectionConfig: config }, libraries: { currentLibraryId: library.id }, networkConnected: online, globals: { localMediaProgress: [] } },
    getters: { 'libraries/getHomeSnapshot': cached ? snapshot : null, 'libraries/getCurrentLibraryMediaType': 'book', getAltViewEnabled: false },
    dispatch: async () => {}
  }
  return componentInstance(home, { $store: store, $strings: {}, $db: { getLocalLibraryItems: async () => [] }, $nativeHttp: { get } })
}

test('snapshots survive a new cache reader, omit download objects and credentials, and reject account/server mismatches', () => {
  const value = cache.serializeHomeSnapshot(config, library, shelves)
  assert.ok(!value.includes('secret'))
  assert.ok(!value.includes('old-download'))
  assert.equal(cache.parseHomeSnapshot(value, config).shelves[0].entities[0].id, 'one')
  for (const changed of [
    { ...config, id: 'another' },
    { ...config, userId: 'bob' },
    { ...config, address: 'https://elsewhere.example' }
  ])
    assert.equal(cache.parseHomeSnapshot(value, changed), null)
  for (const bad of ['broken', '{}', '{"version":999}', 'x'.repeat(2 * 1024 * 1024 + 1)]) assert.equal(cache.parseHomeSnapshot(bad, config), null)
})

test('merge refreshes downloaded associations from the matching server and leaves cached data untouched', () => {
  const normalized = cache.parseHomeSnapshot(cache.serializeHomeSnapshot(config, library, shelves), config).shelves
  const locals = [
    { id: 'wrong', libraryItemId: 'one', serverConnectionConfigId: 'server-b' },
    { id: 'new', libraryItemId: 'one', serverConnectionConfigId: config.id }
  ]
  const result = cache.mergeHomeShelves(
    normalized,
    locals,
    [
      { id: 'local-books', type: 'book' },
      { id: 'local-podcasts', type: 'podcast' },
      { id: 'local-continue', type: 'book', localOnly: true }
    ],
    'book',
    config.id
  )
  assert.equal(result[0].entities[0].localLibraryItem.id, 'new')
  assert.equal(normalized[0].entities[0].localLibraryItem, undefined)
  assert.deepEqual(plain(result.map((shelf) => shelf.id)), ['continue-listening', 'local-books'])
})

test('cold start keeps cached server shelves visible throughout a slow refresh and after failure', async () => {
  const request = deferred()
  const page = instance({ get: () => request.promise })
  const fetching = page.fetchCategories()
  await new Promise(setImmediate)
  assert.equal(page.shelves[0].id, 'continue-listening')
  request.reject(new Error('offline server'))
  await fetching
  assert.equal(page.shelves[0].id, 'continue-listening')
  assert.equal(page.isLoading, false)
})

test('offline cold start displays saved shelves without claiming a logged-in user or requesting the server', async () => {
  let calls = 0
  const page = instance({
    online: false,
    get: async () => {
      calls++
      return shelves
    }
  })
  await page.fetchCategories()
  assert.equal(page.user, null)
  assert.equal(page.shelves[0].entities[0].id, 'one')
  assert.equal(calls, 0)
})

test('coalesces duplicate refreshes and accepts an empty successful server response', async () => {
  const request = deferred()
  let calls = 0
  const page = instance({
    get: () => {
      calls++
      return request.promise
    }
  })
  const first = page.fetchCategories()
  await new Promise(setImmediate)
  await page.fetchCategories()
  assert.equal(calls, 1)
  request.resolve([])
  await first
  assert.equal(page.shelves.length, 0)
  assert.ok(page.lastServerFetch > 0)
})

test('a slow response for the previous library cannot replace the newly selected library', async () => {
  const request = deferred()
  const page = instance({ get: () => request.promise })
  const first = page.fetchCategories()
  await new Promise(setImmediate)
  page.$store.state.libraries.currentLibraryId = 'podcasts'
  page.$store.getters['libraries/getHomeSnapshot'] = null
  page.$nativeHttp.get = async () => [{ id: 'podcasts', type: 'podcast', entities: [] }]
  await page.fetchCategories()
  request.resolve(shelves)
  await first
  assert.equal(page.shelves[0].id, 'podcasts')
})

test('logout invalidates a pending server response', async () => {
  const request = deferred()
  const page = instance({ cached: false, get: () => request.promise })
  const first = page.fetchCategories()
  await new Promise(setImmediate)
  page.$store.state.user.user = null
  await page.fetchCategories()
  request.resolve(shelves)
  await first
  assert.equal(page.shelves.length, 0)
})
