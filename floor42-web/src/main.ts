// First: the screen's language and the panels read this browser's storage as their modules load.
import './ui/legacyStorage'
import { createApp } from 'vue'
import './style.css'
import App from './App.vue'

// The online demo build replays a recording instead of talking to a daemon (ADR-0031); other builds drop this branch.
if (import.meta.env.VITE_DEMO_TAPE) await (await import('./demo/replay')).installReplay()

createApp(App).mount('#app')
