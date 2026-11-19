/**
 * Stable shell for the mobile bridge.
 *
 * The Loader keeps one JavaScript module generation per installed package, so
 * editing the plugin logic in place would need a DSH restart. This shell
 * re-imports `logic.mjs` with a cache-busting query on every activation, which
 * makes the plugin's behaviour reloadable through a disable/enable cycle.
 */
export const name = 'mobile-bridge'

export async function apply(ctx, config = {}) {
  const stamp = `${Date.now()}-${Math.random().toString(36).slice(2)}`
  const module = await import(new URL(`./logic.mjs?v=${stamp}`, import.meta.url).href)
  const dispose = module.createBridge(ctx, config)
  return () => {
    if (typeof dispose === 'function') dispose()
  }
}
