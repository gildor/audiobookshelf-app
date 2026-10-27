const { test } = require('node:test')
const assert = require('node:assert/strict')
const { loadSource, componentInstance } = require('./source.cjs')
const { parseWidgetLink } = loadSource('utils/widgetLink.js')
const layout = loadSource('layouts/default.vue', {
  '@capacitor/core': {},
  '@/plugins/capacitor': { AbsLogger: {} },
  '@capacitor/app': { App: {} }
}).default

function layoutInstance(configId = 'saved') {
  const paths = []
  const instance = componentInstance(layout, {
    $store: { state: { user: { user: configId ? { id: 'user' } : null } }, getters: { 'user/getServerConnectionConfigId': configId } },
    $route: { fullPath: '/bookshelf' },
    $router: { push: (path) => paths.push(path) }
  })
  instance.paths = paths
  return instance
}

test('widget links open the exact local book or episode, with local IDs taking precedence', () => {
  const book = parseWidgetLink('audiobookshelf://widget?localLibraryItemId=local_one&libraryItemId=server-one&serverConnectionConfigId=saved')
  assert.equal(book.path, '/item/local_one?noredirect=1')
  assert.equal(book.isLocal, true)
  assert.equal(book.serverConnectionConfigId, 'saved')
  const episode = parseWidgetLink('audiobookshelf://widget?localLibraryItemId=local_one&localEpisodeId=local_ep&episodeId=server-ep')
  assert.equal(episode.path, '/item/local_one/local_ep?noredirect=1')
})

test('streamed episodes retain their parent book, episode, and saved server', () => {
  const link = parseWidgetLink('audiobookshelf://widget?libraryItemId=book&episodeId=episode&serverConnectionConfigId=server')
  assert.equal(link.path, '/item/book/episode')
  assert.equal(link.isLocal, false)
  assert.equal(link.serverConnectionConfigId, 'server')
})

test('unrelated auth links and malformed URLs are ignored, and IDs cannot insert navigation paths', () => {
  for (const url of ['bad', 'https://widget?libraryItemId=book', 'audiobookshelf://oauth?code=one', 'audiobookshelf://widget']) assert.equal(parseWidgetLink(url), null)
  assert.equal(parseWidgetLink('audiobookshelf://widget?libraryItemId=a%2Fb%3Fx').path, '/item/a%2Fb%3Fx')
})

test('a cold widget tap is queued until startup finishes, then opens its local book', () => {
  const instance = layoutInstance(null)
  instance.widgetUrlOpened('audiobookshelf://widget?localLibraryItemId=local_book')
  assert.equal(instance.paths.length, 0)
  instance.hasMounted = true
  instance.openWidgetBook()
  assert.equal(instance.paths[0], '/item/local_book?noredirect=1')
  assert.equal(instance.pendingWidgetLink, null)
})

test('a warm widget tap opens its streamed episode on the matching server', () => {
  const instance = layoutInstance()
  instance.hasMounted = true
  instance.widgetUrlOpened('audiobookshelf://widget?libraryItemId=book&episodeId=episode&serverConnectionConfigId=saved')
  assert.equal(instance.paths[0], '/item/book/episode')
})

test('a book on a different saved server retains its destination through the connection screen', () => {
  const instance = layoutInstance('other')
  instance.hasMounted = true
  instance.widgetUrlOpened('audiobookshelf://widget?libraryItemId=book&serverConnectionConfigId=saved')
  assert.equal(instance.paths[0].path, '/connect')
  assert.equal(instance.paths[0].query.redirect, '/item/book')
  assert.equal(instance.paths[0].query.serverConnectionConfigId, 'saved')
})
