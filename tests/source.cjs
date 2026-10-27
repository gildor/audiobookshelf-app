const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const babel = require('@babel/core')

function loadSource(relativePath, mocks = {}) {
  const filename = path.resolve(__dirname, '..', relativePath)
  let source = fs.readFileSync(filename, 'utf8')
  if (filename.endsWith('.vue')) source = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  const { code } = babel.transformSync(source, { filename, configFile: false, babelrc: false, plugins: ['@babel/plugin-transform-modules-commonjs'] })
  const module = { exports: {} }
  vm.runInNewContext(
    code,
    {
      module,
      exports: module.exports,
      require(id) {
        if (id in mocks) return mocks[id]
        if (id.startsWith('@/')) return loadSource(id.slice(2) + '.js', mocks)
        if (id.startsWith('.')) return require(path.resolve(path.dirname(filename), id))
        return require(id)
      },
      URL,
      URLSearchParams,
      console,
      setTimeout,
      clearTimeout
    },
    { filename }
  )
  return module.exports
}

function componentInstance(component, overrides) {
  const instance = { ...component.data(), ...overrides }
  for (const [name, method] of Object.entries(component.methods)) instance[name] = method.bind(instance)
  for (const [name, getter] of Object.entries(component.computed || {})) Object.defineProperty(instance, name, { get: (typeof getter === 'function' ? getter : getter.get).bind(instance), configurable: true })
  return instance
}

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

module.exports = { loadSource, componentInstance, deferred }
