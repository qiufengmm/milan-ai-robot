import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { compileScript, parse } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'
import * as vue from 'vue'

export function loadJsModule(file, dependencies = {}, source = readFileSync(file, 'utf8')) {
  const require = createRequire(file)
  const { code } = transformSync(source, { format: 'cjs' })
  const module = { exports: {} }
  const load = id => {
    if (Object.hasOwn(dependencies, id)) return dependencies[id]
    if (id === 'vue') return { ...vue, onMounted() {}, onBeforeUnmount() {}, onUnmounted() {} }
    return require(id)
  }
  new Function('require', 'module', 'exports', code)(load, module, module.exports)
  return module.exports
}

// Exercise the component's real setup handlers without a browser or UI library.
export function loadVueSetup(file, props, dependencies = {}) {
  const { descriptor } = parse(readFileSync(file, 'utf8'))
  const script = compileScript(descriptor, { id: file.pathname })
  const component = loadJsModule(file, dependencies, script.content).default
  const events = []
  const state = component.setup(props, {
    expose() {},
    emit(...event) {
      events.push(event)
      if (event[0] === 'update:modelValue') props.modelValue = event[1]
    },
  })
  return { state, events }
}
