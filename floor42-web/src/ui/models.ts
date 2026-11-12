/**
 * The models an agent may be hired or set on: the daemon's one catalogue (`Setting.RETRO_MODEL` in the domain, which
 * `POST /api/agents` and `PUT /api/agents/{id}` check). `ModelCatalogueTest` keeps this list equal to it.
 */
export const MODELS: readonly string[] = ['claude-haiku-5-5', 'claude-sonnet-5-5', 'claude-opus-5-5']
