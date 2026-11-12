/** The online demo (ADR-0031) replays a tape: no first-run surface may open by itself there, nor call a route it lacks. */
export const demoMode = (): boolean => !!import.meta.env.VITE_DEMO_TAPE

/** The first signed-in page of this browser, where the machine checklist and the tutorial offer may open by themselves. */
export const firstPage = (signedIn: boolean): boolean => signedIn && !demoMode()
